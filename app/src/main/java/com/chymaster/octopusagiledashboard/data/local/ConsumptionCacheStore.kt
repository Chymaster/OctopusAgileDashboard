package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.core.util.DemoIdentifiers
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated cache store for half-hourly consumption data. This is the single
 * source of truth for all consumption data in the app, replacing the previous
 * split between [DemoCacheStore] (demo mode) and direct [ConsumptionDao]
 * reads (real mode).
 *
 * The store maintains an in-memory [StateFlow] of [ConsumptionEntity]s
 * covering the currently loaded time range. On [loadRange], it checks
 * whether the app is in demo mode and routes to either the Room database
 * (real mode) or the demo data generator (demo mode).
 *
 * [refreshFromApi] fetches fresh data from the Octopus Energy usage API
 * (real mode) or regenerates demo data (demo mode), writes it to Room,
 * and reloads the in-memory cache.
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

    /**
     * Load consumption for [start]..[end] into the in-memory cache.
     * In demo mode, generates synthetic data. In real mode, reads from Room.
     * If the requested range is already fully covered, this is a no-op.
     */
    suspend fun loadRange(start: Instant, end: Instant) {
        val currentStart = cachedStart
        val currentEnd = cachedEnd
        if (currentStart != null && currentEnd != null &&
            !start.isBefore(currentStart) && !end.isAfter(currentEnd)
        ) {
            return // already covered
        }

        val isDemo = preferencesRepository.isDemoMode.first()
        val entities = if (isDemo) {
            val generated = DemoDataGenerator.generateConsumptionEntities(start, end)
            // Write to Room for persistence. The in-memory cache is updated
            // via mergeAndEmit below.
            consumptionDao.insertAll(generated)
            generated
        } else {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            // Merge: load what Room has, then fill gaps from the API if needed.
            val roomEntities = consumptionDao.queryRange(mpan, start.toEpochMilli(), end.toEpochMilli())
            if (roomEntities.isEmpty()) {
                // Nothing in Room for this range — try the API.
                val apiResult = fetchAndPersist(start, end)
                if (apiResult.isSuccess) {
                    consumptionDao.queryRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                } else {
                    emptyList()
                }
            } else {
                roomEntities
            }
        }

        mergeAndEmit(entities, start, end)
    }

    /**
     * Fetch consumption from the Octopus API for [start]..[end], persist to Room,
     * and reload the in-memory cache. In demo mode, regenerates synthetic data.
     */
    suspend fun refreshFromApi(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val isDemo = preferencesRepository.isDemoMode.first()
                if (isDemo) {
                    val generated = DemoDataGenerator.generateConsumptionEntities(start, end)
                    consumptionDao.insertAll(generated)
                    mergeAndEmit(generated, start, end)
                } else {
                    val result = fetchAndPersist(start, end)
                    if (result.isFailure) {
                        return@withContext result
                    }
                    // Reload cache from Room to pick up the new data.
                    val mpan = preferencesRepository.mpanFlow.first() ?: ""
                    val entities = consumptionDao.queryRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                    mergeAndEmit(entities, start, end)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Observe consumption for [start]..[end] as domain [ConsumptionRecord] objects.
     * Returns a filtered, mapped view of the in-memory cache.
     */
    fun observeRange(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _consumption
            .map { entities ->
                entities
                    .filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                    .map { it.toDomain() }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Observe consumption for [start]..[end] as raw [ConsumptionEntity] objects.
     * Used internally by the repository for [buildHalfHourPoints] which
     * needs entity-level access.
     */
    fun observeRangeEntities(start: Instant, end: Instant): Flow<List<ConsumptionEntity>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _consumption
            .map { entities ->
                entities.filter { it.intervalStart >= startMs && it.intervalStart < endMs }
            }
            .flowOn(Dispatchers.IO)
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
}
