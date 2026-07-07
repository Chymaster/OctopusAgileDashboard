package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.ConsumptionDto
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated cache store for half-hourly consumption data. This is the single
 * source of truth for all consumption data in the app.
 *
 * The store maintains an in-memory [StateFlow] of [ConsumptionEntity]s
 * covering the currently loaded time range. [getConsumption] is the single
 * entry point for data access — it checks Room for completeness, detects
 * gaps, and fetches missing blocks from the Octopus API (or generates demo
 * data when in demo mode).
 *
 * The [observeRange] and [observeRangeEntities] methods return reactive
 * [Flow]s that automatically trigger [getConsumption] when collected,
 * ensuring the cache is always populated for the requested range.
 */
@Singleton
class ConsumptionCacheStore @Inject constructor(
    private val consumptionDao: ConsumptionDao,
    private val apiService: OctopusApiService,
    private val preferencesRepository: UserPreferencesRepository
) {

    private val _consumption = MutableStateFlow<List<ConsumptionEntity>>(emptyList())

    /** Currently cached consumption as entities. */
    val consumption: StateFlow<List<ConsumptionEntity>> = _consumption.asStateFlow()

    /** Earliest instant covered by the current cache. */
    @Volatile
    private var cachedStart: Instant? = null

    /** Latest instant covered by the current cache. */
    @Volatile
    private var cachedEnd: Instant? = null

    // ── Public API: get ─────────────────────────────────────────────

    /**
     * Single entry point for consumption data. Guarantees [start]..[end] is
     * fully populated in Room and the in-memory cache.
     *
     * 1. Demo mode → generate synthetic data, persist, return.
     * 2. Real mode, all data present → return from Room.
     * 3. Real mode, no data → fetch entire range from API.
     * 4. Real mode, partial data → detect and fill gaps synchronously.
     */
    suspend fun getConsumption(start: Instant, end: Instant): List<ConsumptionEntity> {
        val isDemo = preferencesRepository.isDemoMode.first()
        if (isDemo) {
            val generated = DemoDataGenerator.generateConsumptionEntities(start, end)
            consumptionDao.insertAll(generated)
            mergeAndEmit(generated, start, end)
            return generated
        }

        val mpan = preferencesRepository.mpanFlow.first() ?: ""
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        val expectedCount = ((endMs - startMs) / HALF_HOUR_MILLIS).toInt()

        if (expectedCount <= 0) {
            mergeAndEmit(emptyList(), start, end)
            return emptyList()
        }

        val actualCount = consumptionDao.countInRange(mpan, startMs, endMs)

        val entities = when {
            actualCount >= expectedCount -> {
                // All data present — read from Room.
                consumptionDao.queryRange(mpan, startMs, endMs)
            }
            actualCount == 0 -> {
                // Nothing in Room — fetch the entire range from the API.
                val apiResult = fetchAndPersist(start, end)
                if (apiResult.isSuccess) {
                    consumptionDao.queryRange(mpan, startMs, endMs)
                } else {
                    emptyList()
                }
            }
            else -> {
                // Partial data — detect and fill gaps.
                fillGaps(mpan, start, end)
                consumptionDao.queryRange(mpan, startMs, endMs)
            }
        }

        mergeAndEmit(entities, start, end)
        return entities
    }

    // ── Public API: observe ─────────────────────────────────────────

    /**
     * Observe consumption for [start]..[end] as domain [ConsumptionRecord]
     * objects. Automatically triggers [getConsumption] when collected so the
     * cache is populated for the requested range.
     */
    fun observeRange(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> = channelFlow {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()

        // Trigger data loading in the background. This populates the
        // in-memory StateFlow which the observation below reacts to.
        launch(Dispatchers.IO) { getConsumption(start, end) }

        _consumption
            .map { entities ->
                entities
                    .filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                    .map { it.toDomain() }
            }
            .collect { send(it) }
    }

    /**
     * Observe consumption for [start]..[end] as raw [ConsumptionEntity]
     * objects. Automatically triggers [getConsumption] when collected so the
     * cache is populated for the requested range.
     */
    fun observeRangeEntities(start: Instant, end: Instant): Flow<List<ConsumptionEntity>> = channelFlow {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()

        // Trigger data loading in the background. This populates the
        // in-memory StateFlow which the observation below reacts to.
        launch(Dispatchers.IO) { getConsumption(start, end) }

        _consumption
            .map { entities ->
                entities.filter { it.intervalStart >= startMs && it.intervalStart < endMs }
            }
            .collect { send(it) }
    }

    /**
     * Clear all cached consumption. Called on credential changes so no stale
     * data from the previous mode lingers.
     */
    suspend fun clear() {
        _consumption.value = emptyList()
        cachedStart = null
        cachedEnd = null
        consumptionDao.deleteAll()
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Detect and fill gaps in Room data for [start]..[end].
     * Identifies contiguous blocks of missing half-hour slots and fetches
     * each block from the API synchronously (one at a time).
     */
    private suspend fun fillGaps(mpan: String, start: Instant, end: Instant) {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()

        val existingSlots = consumptionDao.queryRange(mpan, startMs, endMs)
            .map { it.intervalStart }
            .toSet()

        // Find contiguous missing blocks.
        val gaps = mutableListOf<Pair<Instant, Instant>>()
        var gapStart: Instant? = null
        var t = start
        while (!t.isAfter(end)) {
            if (t.toEpochMilli() !in existingSlots) {
                if (gapStart == null) gapStart = t
            } else {
                if (gapStart != null) {
                    gaps.add(gapStart to t)
                    gapStart = null
                }
            }
            t = t.plusMillis(HALF_HOUR_MILLIS)
        }
        if (gapStart != null) {
            gaps.add(gapStart to end)
        }

        // Fetch each gap synchronously — one at a time.
        for ((gStart, gEnd) in gaps) {
            fetchAndPersist(gStart, gEnd)
            // Continue even on failure — partial fill is better than nothing.
        }
    }

    /**
     * Merge [newEntities] into the cache, expanding the tracked range.
     * Deduplicates by `intervalStart` (the primary key component), preferring newer entries.
     */
    private fun mergeAndEmit(
        newEntities: List<ConsumptionEntity>,
        requestedStart: Instant,
        requestedEnd: Instant
    ) {
        if (newEntities.isEmpty()) {
            // Still update range tracking so we don't re-fetch empty ranges.
            cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
            cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
            return
        }

        val existing = _consumption.value
        val merged = if (existing.isEmpty()) {
            newEntities.sortedBy { it.intervalStart }
        } else {
            (existing + newEntities)
                .associateBy { it.intervalStart }  // dedup: last wins
                .values
                .sortedBy { it.intervalStart }
        }
        _consumption.value = merged
        cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
        cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
    }

    /**
     * Fetch consumption from the Octopus usage API and insert into Room.
     * Requires authentication (mpan + serial).
     */
    private suspend fun fetchAndPersist(start: Instant, end: Instant): Result<Unit> {
        return try {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            val serial = preferencesRepository.serialNumberFlow.first() ?: ""
            val allConsumption = fetchAllConsumption(mpan, serial, start, end)
            val entities = allConsumption.map { it.toEntity(mpan, serial) }
            consumptionDao.insertAll(entities)
            preferencesRepository.saveLastConsumptionRefresh(System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch consumption data from the Octopus API, following pagination links.
     */
    private suspend fun fetchAllConsumption(
        mpan: String,
        serial: String,
        start: Instant,
        end: Instant
    ): List<ConsumptionDto> {
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllPages { url ->
            if (url != null) apiService.getConsumptionByUrl(url)
            else apiService.getConsumption(
                mpan = mpan,
                serial = serial,
                periodFrom = startStr,
                periodTo = endStr
            )
        }
    }

    /**
     * Follow pagination links from the Octopus API until all pages are fetched.
     */
    private suspend fun fetchAllPages(
        request: suspend (url: String?) -> Response<PaginatedResponse<ConsumptionDto>>
    ): List<ConsumptionDto> {
        val allItems = mutableListOf<ConsumptionDto>()
        var url: String? = null

        do {
            val response = request(url)
            if (!response.isSuccessful) {
                throw Exception("API error: ${response.code()} ${response.message()}")
            }
            val body = response.body() ?: break
            allItems.addAll(body.results)
            url = body.next
        } while (url != null)

        return allItems
    }

    companion object {
        /** 30 minutes in milliseconds — the half-hour slot interval. */
        private const val HALF_HOUR_MILLIS = 1_800_000L
    }
}
