package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.core.util.DemoIdentifiers
import com.chymaster.octopusagiledashboard.data.local.ConsumptionCacheStore
import com.chymaster.octopusagiledashboard.data.local.StandingChargeCacheStore
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

@OptIn(FlowPreview::class)
@Singleton
class OctopusRepositoryImpl @Inject constructor(
    private val apiService: OctopusApiService,
    private val preferencesRepository: UserPreferencesRepository,
    private val agilePriceDao: AgilePriceDao,
    private val consumptionCacheStore: ConsumptionCacheStore,
    private val standingChargeCacheStore: StandingChargeCacheStore
) : OctopusRepository {

    // ── Agile price in-memory cache ─────────────────────────────────

    private val _agilePrices = MutableStateFlow<List<AgilePriceEntity>>(emptyList())

    /** Earliest instant covered by the current agile price cache. */
    @Volatile
    private var cachedPricesStart: Instant? = null

    /** Latest instant covered by the current agile price cache. */
    @Volatile
    private var cachedPricesEnd: Instant? = null

    // ── Public API: observe ──────────────────────────────────────────

    override fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _agilePrices
            .map { entities ->
                entities
                    .filter { it.validFrom >= startMs && it.validTo <= endMs }
                    .map { it.toDomain() }
            }
            .flowOn(Dispatchers.IO)
    }

    override fun observeAgilePriceEntities(start: Instant, end: Instant): Flow<List<AgilePriceEntity>> {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        return _agilePrices
            .map { entities ->
                entities.filter { it.validFrom >= startMs && it.validTo <= endMs }
            }
            .flowOn(Dispatchers.IO)
    }

    override fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> =
        consumptionCacheStore.observeRange(start, end)
            .flowOn(Dispatchers.IO)

    override fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        val pricesFlow = observeAgilePriceEntities(start, end)
            .distinctUntilChanged()

        val consumptionFlow = consumptionCacheStore.observeRangeEntities(start, end)
            .distinctUntilChanged()

        var emittedOnce = false
        return combine(pricesFlow, consumptionFlow) { prices, consumption ->
            buildHalfHourPoints(prices, consumption)
        }.debounce {
            if (emittedOnce) 1000L else {
                emittedOnce = true
                0L
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun observeStandingCharges(start: Instant, end: Instant): Flow<List<StandingCharge>> =
        standingChargeCacheStore.observeRange(start, end)
            .flowOn(Dispatchers.IO)

    // ── Public API: load / refresh / expand ──────────────────────────

    /**
     * Load agile prices for [start]..[end] into the in-memory cache.
     * In demo mode, generates synthetic data and writes to Room.
     * In real mode, reads from Room; if empty, fetches from the API.
     * If the requested range is already fully covered, this is a no-op.
     */
    override suspend fun loadAgilePrices(start: Instant, end: Instant) {
        val currentStart = cachedPricesStart
        val currentEnd = cachedPricesEnd
        if (currentStart != null && currentEnd != null &&
            !start.isBefore(currentStart) && !end.isAfter(currentEnd)
        ) {
            return // already covered
        }

        val isDemo = preferencesRepository.isDemoMode.first()
        val entities = if (isDemo) {
            val generated = DemoDataGenerator.generateAgilePriceEntities(start, end)
            agilePriceDao.insertAll(generated)
            generated
        } else {
            val roomEntities = agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
            if (roomEntities.isEmpty()) {
                val apiResult = fetchAndPersistAgilePrices(start, end)
                if (apiResult.isSuccess) {
                    agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
                } else {
                    emptyList()
                }
            } else {
                roomEntities
            }
        }

        mergeAndEmitAgilePrices(entities, start, end)
    }

    /**
     * Fetch fresh agile prices from the API (or generate synthetic data in demo mode),
     * persist to Room, and reload the in-memory cache.
     *
     * CRITICAL: In demo mode, does NOT call fetchAndPersist — this prevents real API
     * prices from leaking into Room.
     */
    override suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val isDemo = preferencesRepository.isDemoMode.first()
                if (isDemo) {
                    val generated = DemoDataGenerator.generateAgilePriceEntities(start, end)
                    agilePriceDao.insertAll(generated)
                    mergeAndEmitAgilePrices(generated, start, end)
                } else {
                    val result = fetchAndPersistAgilePrices(start, end)
                    if (result.isFailure) {
                        return@withContext result
                    }
                    val entities = agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
                    mergeAndEmitAgilePrices(entities, start, end)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Expand the cached agile price range backward by [additionalDays] days.
     * Used by infinite scroll-up in the Future Prices screen.
     * In demo mode, generates synthetic data. In real mode, reads from Room first;
     * if empty, fetches from the API.
     */
    override suspend fun expandAgilePriceHistoryBackward(additionalDays: Int) {
        val currentStart = cachedPricesStart ?: return
        val newStart = currentStart.minusSeconds(additionalDays.toLong() * 86400)

        val isDemo = preferencesRepository.isDemoMode.first()
        val newEntities = if (isDemo) {
            DemoDataGenerator.generateAgilePriceEntities(newStart, currentStart)
        } else {
            val roomEntities = agilePriceDao.queryRange(newStart.toEpochMilli(), currentStart.toEpochMilli())
            if (roomEntities.isEmpty()) {
                val apiResult = fetchAndPersistAgilePrices(newStart, currentStart)
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
            val merged = (newEntities + _agilePrices.value)
                .distinctBy { it.validFrom }
                .sortedBy { it.validFrom }
            _agilePrices.value = merged
            cachedPricesStart = newStart
        }
    }

    override suspend fun refreshStandingCharges(start: Instant, end: Instant): Result<Unit> {
        return standingChargeCacheStore.refreshFromApi(start, end)
    }

    override suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit> {
        return consumptionCacheStore.refreshFromApi(start, end)
    }

    override suspend fun wipeAllCaches() {
        withContext(Dispatchers.IO) {
            // Clear agile price in-memory cache + Room
            _agilePrices.value = emptyList()
            cachedPricesStart = null
            cachedPricesEnd = null
            agilePriceDao.deleteAll()
            // Clear other cache stores
            consumptionCacheStore.clear()
            standingChargeCacheStore.clear()
        }
    }

    override suspend fun fetchMeterGsp(mpan: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getMeterPoint(mpan)
                if (response.isSuccessful) {
                    val body = response.body()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                    Result.success(body.gsp)
                } else {
                    Result.failure(Exception("API error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun fetchFlexiblePrice(): Result<Double> {
        return withContext(Dispatchers.IO) {
            try {
                val gsp = preferencesRepository.gspFlow.first()
                    ?: Constants.DEFAULT_GSP
                val flexibleProduct = preferencesRepository.flexibleProductCodeFlow.first()
                    ?: Constants.FLEXIBLE_PRODUCT_CODE
                val flexibleTariff = "E-1R-$flexibleProduct-${gsp.removePrefix("_")}"

                val now = java.time.Instant.now()
                val halfHour = 1800L
                val day = 24 * 3600L
                val week = 7 * day

                // Cascading windows: try the tightest first (cheapest), widen
                // if no in-progress rate is found. The current rate is whichever
                // entry covers `now` — i.e. validFrom <= now and (validTo is
                // null, meaning the API left it open, or validTo > now).
                val windows = listOf(
                    now to now.plusSeconds(halfHour),
                    now.minusSeconds(halfHour) to now.plusSeconds(halfHour),
                    now.minusSeconds(day) to now.plusSeconds(day),
                    now.minusSeconds(week) to now.plusSeconds(day)
                )

                var lastError: Throwable? = null
                for ((start, end) in windows) {
                    val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                    val endStr = DateTimeFormatter.ISO_INSTANT.format(end)

                    val response = try {
                        apiService.getAgileRates(
                            product = flexibleProduct,
                            tariff = flexibleTariff,
                            periodFrom = startStr,
                            periodTo = endStr
                        )
                    } catch (e: Exception) {
                        lastError = e
                        continue
                    }

                    if (!response.isSuccessful) {
                        lastError = Exception("API error: ${response.code()} ${response.message()}")
                        continue
                    }

                    val rates = response.body()?.results ?: continue
                    val current = rates.firstOrNull { rate ->
                        val validFromInstant = OffsetDateTime.parse(rate.validFrom).toInstant()
                        if (validFromInstant > now) return@firstOrNull false
                        val validToInstant = rate.validTo
                            ?.let { OffsetDateTime.parse(it).toInstant() }
                        validToInstant == null || validToInstant > now
                    }

                    if (current != null) {
                        return@withContext Result.success(current.valueIncVat)
                    }
                }

                Result.failure(
                    lastError ?: Exception("No flexible rate found across ${windows.size} time windows")
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun testConnection(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val mpan = preferencesRepository.mpanFlow.first()
                    ?: return@withContext Result.failure(Exception("MPAN not configured"))
                val response = apiService.getMeterPoint(mpan)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Connection failed: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Combine a list of [AgilePriceEntity] with a list of [ConsumptionEntity]
     * into the [HalfHourPoint]s the chart expects. Always start from prices
     * and merge in the matching consumption row by `intervalStart` (== price
     * `validFrom`).
     */
    private fun buildHalfHourPoints(
        prices: List<AgilePriceEntity>,
        consumption: List<ConsumptionEntity>
    ): List<HalfHourPoint> {
        val consumptionMap = consumption.associate { it.intervalStart to it }
        return prices.map { price ->
            val cons = consumptionMap[price.validFrom]
            HalfHourPoint(
                intervalStart = Instant.ofEpochMilli(price.validFrom),
                intervalEnd = Instant.ofEpochMilli(price.validTo),
                priceIncVat = price.priceIncVat,
                consumptionKwh = cons?.consumption,
                costIncVat = if (cons != null) cons.consumption * price.priceIncVat else null
            )
        }
    }

    // ── Agile price private helpers ──────────────────────────────────

    /**
     * Merge [newEntities] into the in-memory agile price cache, expanding the tracked range.
     * Deduplicates by `validFrom` (primary key), preferring newer entries.
     */
    private fun mergeAndEmitAgilePrices(
        newEntities: List<AgilePriceEntity>,
        requestedStart: Instant,
        requestedEnd: Instant
    ) {
        if (newEntities.isEmpty()) {
            cachedPricesStart = minOf(cachedPricesStart ?: requestedStart, requestedStart)
            cachedPricesEnd = maxOf(cachedPricesEnd ?: requestedEnd, requestedEnd)
            return
        }

        val existing = _agilePrices.value
        val merged = if (existing.isEmpty()) {
            newEntities.sortedBy { it.validFrom }
        } else {
            (existing + newEntities)
                .associateBy { it.validFrom }  // dedup: last wins
                .values
                .sortedBy { it.validFrom }
        }
        _agilePrices.value = merged
        cachedPricesStart = minOf(cachedPricesStart ?: requestedStart, requestedStart)
        cachedPricesEnd = maxOf(cachedPricesEnd ?: requestedEnd, requestedEnd)
    }

    /**
     * Fetch agile prices from the Octopus public API and insert into Room.
     * Should NOT be called in demo mode — use [DemoDataGenerator] instead.
     */
    private suspend fun fetchAndPersistAgilePrices(start: Instant, end: Instant): Result<Unit> {
        return try {
            val allRates = fetchPublicAgilePrices(start, end)
            val tariffCode = preferencesRepository.tariffConfig.first().tariffCode
            val entities = allRates.map { it.toEntity(tariffCode) }
            agilePriceDao.insertAll(entities)
            preferencesRepository.saveLastPricesRefresh(System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch agile prices from the public (unauthenticated) Octopus endpoint.
     * Follows pagination links to get all results.
     */
    private suspend fun fetchPublicAgilePrices(start: Instant, end: Instant): List<AgileRateDto> {
        val tariffConfig = preferencesRepository.tariffConfig.first()
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllAgilePages { url ->
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
     * Follow pagination links from the Octopus API until all agile rate pages are fetched.
     */
    private suspend fun fetchAllAgilePages(
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
