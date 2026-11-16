package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.local.dao.StandingChargeDao
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.data.remote.dto.StandingChargeDto
import com.chymaster.octopusagiledashboard.data.repository.TariffScheduleRepository
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import com.chymaster.octopusagiledashboard.domain.tariff.TariffCostCalculator
import com.chymaster.octopusagiledashboard.domain.tariff.TariffScheduleResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Charges are fetched per tariff in the effective [TariffSchedule], so a range
 * that spans a tariff switch is priced with the charge of whichever tariff was
 * in force on each day. Rows are keyed by `(validFrom, tariffCode)` and
 * persisted in Room, so several tariffs can be cached at once.
 */
@Singleton
class StandingChargeCacheStore @Inject constructor(
    private val standingChargeDao: StandingChargeDao,
    private val apiService: OctopusApiService,
    private val tariffScheduleRepository: TariffScheduleRepository
) {

    private val _charges = MutableStateFlow<List<StandingChargeEntity>>(emptyList())

    /**
     * Fetch the standing charges for every tariff in force over
     * `[start, end)`, persist them, and reload the in-memory cache.
     */
    suspend fun refreshFromApi(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val schedule = tariffScheduleRepository.get()
                val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
                if (segments.isEmpty()) return@withContext Result.success(Unit)

                var anySuccess = false
                var lastFailure: Throwable? = null
                for (segment in segments) {
                    val fetchStart = segment.start
                        .minusSeconds(Constants.STANDING_CHARGE_FETCH_LOOKBACK_SECONDS)
                    val result = fetchAndPersist(
                        productCode = segment.productCode,
                        tariffCode = segment.tariffCode,
                        start = fetchStart,
                        end = segment.end
                    )
                    if (result.isSuccess) anySuccess = true else lastFailure = result.exceptionOrNull()
                }

                reload(start, end)

                when {
                    anySuccess || lastFailure == null -> Result.success(Unit)
                    else -> Result.failure(lastFailure!!)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Observe standing charges for `[start, end)` as domain [StandingCharge]
     * objects, limited to the tariffs in force over the range.
     */
    fun observeRange(start: Instant, end: Instant): Flow<List<StandingCharge>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return combine(
            _charges,
            tariffScheduleRepository.state
        ) { entities, schedule ->
            val codes = TariffScheduleResolver
                .clipToRange(schedule, start, end)
                .map { it.tariffCode }
                .toSet()
            entities
                .filter { it.validFrom <= endMs && it.validTo >= startMs }
                .filter { codes.isEmpty() || it.tariffCode in codes }
                .map { it.toDomain() }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    /**
     * Observe the total standing-charge cost (pence) over `[start, end)`,
     * pricing each London calendar day with the tariff in force at its start.
     * Null when no charge is known for the range.
     */
    fun observeTotalCost(start: Instant, end: Instant): Flow<Double?> {
        return combine(
            _charges,
            tariffScheduleRepository.state
        ) { entities, schedule ->
            val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
            if (segments.isEmpty()) return@combine null
            val codes = segments.map { it.tariffCode }.toSet()
            val charges = entities.filter { it.tariffCode in codes }.map { it.toDomain() }
            val days = TariffCostCalculator.dayStarts(start, end)
            TariffCostCalculator.standingChargeCost(charges, days) { day ->
                segments.lastOrNull { !day.isBefore(it.start) && day.isBefore(it.end) }?.tariffCode
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    /**
     * Re-read the cached charges for the tariffs covering `[start, end)` into
     * the in-memory cache.
     */
    suspend fun reload(start: Instant, end: Instant) {
        val schedule = tariffScheduleRepository.state.value
        val codes = TariffScheduleResolver
            .clipToRange(schedule, start, end)
            .map { it.tariffCode }
        if (codes.isEmpty()) return
        val entities = standingChargeDao.queryRangeForTariffs(
            codes.distinct(),
            start.toEpochMilli(),
            end.toEpochMilli()
        )
        mergeAndEmit(entities)
    }

    /** Clear all cached standing charges (credential change). */
    suspend fun clear() {
        _charges.value = emptyList()
        standingChargeDao.deleteAll()
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** Merge [newEntities] into the cache, deduplicating on `(validFrom, tariffCode)`. */
    private fun mergeAndEmit(newEntities: List<StandingChargeEntity>) {
        if (newEntities.isEmpty()) return
        val merged = (_charges.value + newEntities)
            .associateBy { it.validFrom to it.tariffCode }
            .values
            .sortedWith(compareBy({ it.validFrom }, { it.tariffCode }))
        _charges.value = merged
    }

    /** Fetch one tariff's standing charges and insert them into Room. */
    private suspend fun fetchAndPersist(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): Result<Unit> {
        return try {
            val allCharges = fetchPublicStandingCharges(productCode, tariffCode, start, end)
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

    /** Fetch standing charges from the public (unauthenticated) endpoint. */
    private suspend fun fetchPublicStandingCharges(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): List<StandingChargeDto> {
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllPages { url ->
            if (url != null) apiService.getStandingChargesByUrl(url)
            else apiService.getStandingCharges(
                product = productCode,
                tariff = tariffCode,
                periodFrom = startStr,
                periodTo = endStr
            )
        }
    }

    /** Follow pagination links from the Octopus API until all pages are fetched. */
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
