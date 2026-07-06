package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.core.util.DemoIdentifiers
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated cache store for Agile half-hourly prices. This is the single
 * source of truth for all agile price data in the app, replacing the
 * previous split between [DemoCacheStore] (demo mode) and direct
 * [AgilePriceDao] reads (real mode).
 *
 * The store maintains an in-memory [StateFlow] of [AgilePriceEntity]s
 * covering the currently loaded time range. On [loadRange], it checks
 * whether the user has API credentials and routes to either the Room
 * database (real mode) or the demo data generator (demo mode).
 *
 * [refreshFromApi] fetches fresh data from the Octopus Energy public
 * API, writes it to Room, and reloads the in-memory cache.
 *
 * [expandHistoryBackward] supports infinite scroll-up by extending the
 * cached range further into the past.
 */
@Singleton
class AgilePriceCacheStore @Inject constructor(
    private val agilePriceDao: AgilePriceDao,
    private val apiService: OctopusApiService,
    private val preferencesRepository: UserPreferencesRepository
) {

    private val _prices = MutableStateFlow<List<AgilePriceEntity>>(emptyList())

    /** Currently cached prices as entities. */
    val prices: StateFlow<List<AgilePriceEntity>> = _prices.asStateFlow()

    /** Earliest instant covered by the current cache. */
    @Volatile
    private var cachedStart: Instant? = null

    /** Latest instant covered by the current cache. */
    @Volatile
    private var cachedEnd: Instant? = null

    /**
     * Load prices for [start]..[end] into the in-memory cache.
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

        val isDemo = !preferencesRepository.hasCredentials.first()
        val entities = if (isDemo) {
            val generated = DemoDataGenerator.generateAgilePriceEntities(start, end)
            // Write to Room for persistence. The in-memory cache is updated
            // via mergeAndEmit below.
            agilePriceDao.insertAll(generated)
            generated
        } else {
            // Merge: load what Room has, then fill gaps from the API if needed.
            val roomEntities = agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
            if (roomEntities.isEmpty()) {
                // Nothing in Room for this range — try the API.
                val apiResult = fetchAndPersist(start, end)
                if (apiResult.isSuccess) {
                    agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
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
     * Expand the cached range backward by [additionalDays] days.
     * Used by infinite scroll-up in the Future Prices screen.
     * Loads from Room first; if empty, fetches from the API.
     */
    suspend fun expandHistoryBackward(additionalDays: Int) {
        val currentStart = cachedStart ?: return
        val newStart = currentStart.minusSeconds(additionalDays.toLong() * 86400)

        val isDemo = !preferencesRepository.hasCredentials.first()
        val newEntities = if (isDemo) {
            DemoDataGenerator.generateAgilePriceEntities(newStart, currentStart)
        } else {
            val roomEntities = agilePriceDao.queryRange(newStart.toEpochMilli(), currentStart.toEpochMilli())
            if (roomEntities.isEmpty()) {
                val apiResult = fetchAndPersist(newStart, currentStart)
                if (apiResult.isSuccess) {
                    agilePriceDao.queryRange(newStart.toEpochMilli(), currentStart.toEpochMilli())
                } else {
                    emptyList()
                }
            } else {
                roomEntities
            }
        }

        if (newEntities.isNotEmpty()) {
            val merged = (newEntities + _prices.value)
                .distinctBy { it.validFrom }
                .sortedBy { it.validFrom }
            _prices.value = merged
            cachedStart = newStart
        }
    }

    /**
     * Fetch prices from the Octopus API for [start]..[end], persist to Room,
     * and reload the in-memory cache.
     */
    suspend fun refreshFromApi(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val result = fetchAndPersist(start, end)
                if (result.isFailure) {
                    return@withContext result
                }

                // Reload cache from Room (or demo generator) to pick up the new data.
                val isDemo = !preferencesRepository.hasCredentials.first()
                val entities = if (isDemo) {
                    DemoDataGenerator.generateAgilePriceEntities(start, end)
                } else {
                    agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
                }
                mergeAndEmit(entities, start, end)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Observe prices for [start]..[end] as domain [AgilePrice] objects.
     * Returns a filtered, mapped view of the in-memory cache.
     */
    fun observeRange(start: Instant, end: Instant): Flow<List<AgilePrice>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _prices
            .map { entities ->
                entities
                    .filter { it.validFrom >= startMs && it.validTo <= endMs }
                    .map { it.toDomain() }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Observe prices for [start]..[end] as raw [AgilePriceEntity] objects.
     * Used internally by the repository for [buildHalfHourPoints] which
     * needs entity-level access.
     */
    fun observeRangeEntities(start: Instant, end: Instant): Flow<List<AgilePriceEntity>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _prices
            .map { entities ->
                entities.filter { it.validFrom >= startMs && it.validTo <= endMs }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Clear all cached prices. Called on credential changes so no stale
     * data from the previous mode lingers.
     */
    suspend fun clear() {
        _prices.value = emptyList()
        cachedStart = null
        cachedEnd = null
    }

    /**
     * Returns the currently cached time range, or null if nothing is cached.
     */
    fun getCachedRange(): Pair<Instant, Instant>? {
        val s = cachedStart ?: return null
        val e = cachedEnd ?: return null
        return s to e
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Merge [newEntities] into the cache, expanding the tracked range.
     * Deduplicates by `validFrom` (primary key), preferring newer entries.
     */
    private fun mergeAndEmit(
        newEntities: List<AgilePriceEntity>,
        requestedStart: Instant,
        requestedEnd: Instant
    ) {
        if (newEntities.isEmpty()) {
            // Still update range tracking so we don't re-fetch empty ranges.
            cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
            cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
            return
        }

        val existing = _prices.value
        val merged = if (existing.isEmpty()) {
            newEntities.sortedBy { it.validFrom }
        } else {
            (existing + newEntities)
                .associateBy { it.validFrom }  // dedup: last wins
                .values
                .sortedBy { it.validFrom }
        }
        _prices.value = merged
        cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
        cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
    }

    /**
     * Fetch prices from the Octopus public API and insert into Room.
     * Works for both demo and real modes (the public endpoint is unauthenticated).
     */
    private suspend fun fetchAndPersist(start: Instant, end: Instant): Result<Unit> {
        return try {
            val allRates = fetchPublicAgilePrices(start, end)
            val isDemo = !preferencesRepository.hasCredentials.first()
            val tariffCode = if (isDemo) {
                DemoIdentifiers.TARIFF
            } else {
                preferencesRepository.tariffConfig.first().tariffCode
            }
            val entities = allRates.map { it.toEntity(tariffCode) }
            agilePriceDao.insertAll(entities)

            if (!isDemo) {
                preferencesRepository.saveLastPricesRefresh(System.currentTimeMillis())
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch Agile prices from the public (unauthenticated) Octopus endpoint.
     * Follows pagination links to get all results.
     */
    private suspend fun fetchPublicAgilePrices(start: Instant, end: Instant): List<AgileRateDto> {
        val tariffConfig = preferencesRepository.tariffConfig.first()
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllPages { url ->
            if (url != null) apiService.getAgileRatesByUrl(url)
            else apiService.getAgileRates(
                product = tariffConfig.productCode,
                tariff = tariffConfig.tariffCode,
                periodFrom = startStr,
                periodTo = endStr
            )
        }
    }

    /**
     * Follow pagination links from the Octopus API until all pages are fetched.
     */
    private suspend fun fetchAllPages(
        request: suspend (url: String?) -> Response<PaginatedResponse<AgileRateDto>>
    ): List<AgileRateDto> {
        val allRates = mutableListOf<AgileRateDto>()
        var url: String? = null

        do {
            val response = request(url)
            if (!response.isSuccessful) {
                throw Exception("API error: ${response.code()} ${response.message()}")
            }
            val body = response.body() ?: break
            allRates.addAll(body.results)
            url = body.next
        } while (url != null)

        return allRates
    }
}
