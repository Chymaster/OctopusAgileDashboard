package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.data.local.dao.StandingChargeDao
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.data.remote.dto.StandingChargeDto
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
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
 * Dedicated cache store for standing charges. This is the single source of
 * truth for all standing charge data in the app.
 *
 * Unlike agile prices (managed directly by OctopusRepositoryImpl), this store always uses the real Octopus
 * Energy API — standing charges are a public (unauthenticated) endpoint,
 * so no synthetic demo data is needed.
 *
 * The store maintains an in-memory [StateFlow] of [StandingChargeEntity]s.
 * On [loadRange], it reads from Room first; if empty, it fetches from the
 * API and persists to Room.
 */
@Singleton
class StandingChargeCacheStore @Inject constructor(
    private val standingChargeDao: StandingChargeDao,
    private val apiService: OctopusApiService,
    private val preferencesRepository: UserPreferencesRepository
) {

    private val _charges = MutableStateFlow<List<StandingChargeEntity>>(emptyList())

    /** Currently cached standing charges as entities. */
    val charges: StateFlow<List<StandingChargeEntity>> = _charges.asStateFlow()

    /** Earliest instant covered by the current cache. */
    @Volatile
    private var cachedStart: Instant? = null

    /** Latest instant covered by the current cache. */
    @Volatile
    private var cachedEnd: Instant? = null

    /**
     * Load standing charges for [start]..[end] into the in-memory cache.
     * Reads from Room first; if empty, fetches from the API.
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

        val roomEntities = standingChargeDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
        val entities = if (roomEntities.isEmpty()) {
            val apiResult = fetchAndPersist(start, end)
            if (apiResult.isSuccess) {
                standingChargeDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
            } else {
                emptyList()
            }
        } else {
            roomEntities
        }

        mergeAndEmit(entities, start, end)
    }

    /**
     * Fetch standing charges from the Octopus API for [start]..[end],
     * persist to Room, and reload the in-memory cache.
     */
    suspend fun refreshFromApi(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val result = fetchAndPersist(start, end)
                if (result.isFailure) {
                    return@withContext result
                }

                // Reload cache from Room to pick up the new data.
                val entities = standingChargeDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
                mergeAndEmit(entities, start, end)
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Observe standing charges for [start]..[end] as domain [StandingCharge] objects.
     * Returns a filtered, mapped view of the in-memory cache.
     */
    fun observeRange(start: Instant, end: Instant): Flow<List<StandingCharge>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _charges
            .map { entities ->
                entities
                    .filter { it.validFrom <= endMs && it.validTo >= startMs }
                    .map { it.toDomain() }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Observe standing charges for [start]..[end] as raw [StandingChargeEntity] objects.
     */
    fun observeRangeEntities(start: Instant, end: Instant): Flow<List<StandingChargeEntity>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _charges
            .map { entities ->
                entities.filter { it.validFrom <= endMs && it.validTo >= startMs }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Clear all cached standing charges. Called on credential changes so no
     * stale data from the previous mode lingers.
     */
    suspend fun clear() {
        _charges.value = emptyList()
        cachedStart = null
        cachedEnd = null
        standingChargeDao.deleteAll()
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Merge [newEntities] into the cache, expanding the tracked range.
     * Deduplicates by `validFrom` (primary key), preferring newer entries.
     */
    private fun mergeAndEmit(
        newEntities: List<StandingChargeEntity>,
        requestedStart: Instant,
        requestedEnd: Instant
    ) {
        if (newEntities.isEmpty()) {
            cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
            cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
            return
        }

        val existing = _charges.value
        val merged = if (existing.isEmpty()) {
            newEntities.sortedBy { it.validFrom }
        } else {
            (existing + newEntities)
                .associateBy { it.validFrom }  // dedup: last wins
                .values
                .sortedBy { it.validFrom }
        }
        _charges.value = merged
        cachedStart = minOf(cachedStart ?: requestedStart, requestedStart)
        cachedEnd = maxOf(cachedEnd ?: requestedEnd, requestedEnd)
    }

    /**
     * Fetch standing charges from the Octopus public API and insert into Room.
     */
    private suspend fun fetchAndPersist(start: Instant, end: Instant): Result<Unit> {
        return try {
            val allCharges = fetchPublicStandingCharges(start, end)
            val tariffCode = preferencesRepository.tariffConfig.first().tariffCode
            val entities = allCharges.map { it.toEntity(tariffCode) }
            standingChargeDao.insertAll(entities)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiError) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Result.failure(ApiError.NetworkError(cause = e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch standing charges from the public (unauthenticated) Octopus endpoint.
     * Follows pagination links to get all results.
     */
    private suspend fun fetchPublicStandingCharges(start: Instant, end: Instant): List<StandingChargeDto> {
        val tariffConfig = preferencesRepository.tariffConfig.first()
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllPages { url ->
            if (url != null) apiService.getStandingChargesByUrl(url)
            else apiService.getStandingCharges(
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
        request: suspend (url: String?) -> Response<PaginatedResponse<StandingChargeDto>>
    ): List<StandingChargeDto> {
        val allCharges = mutableListOf<StandingChargeDto>()
        var url: String? = null

        do {
            val response = request(url)
            if (!response.isSuccessful) {
                throw ApiError.fromHttpCode(response.code())
            }
            val body = response.body() ?: break
            allCharges.addAll(body.results)
            url = body.next
        } while (url != null)

        return allCharges
    }
}
