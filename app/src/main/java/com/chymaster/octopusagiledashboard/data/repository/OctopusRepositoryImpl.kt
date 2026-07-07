package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.data.local.StandingChargeCacheStore
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.data.remote.dto.ConsumptionDto
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val consumptionDao: ConsumptionDao,
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

    // ── Consumption in-memory cache ─────────────────────────────────

    private val _consumption = MutableStateFlow<List<ConsumptionEntity>>(emptyList())

    /** Earliest instant covered by the current consumption cache. */
    @Volatile
    private var cachedConsumptionStart: Instant? = null

    /** Latest instant covered by the current consumption cache. */
    @Volatile
    private var cachedConsumptionEnd: Instant? = null

    // ── Flexible price in-memory cache ─────────────────────────────

    private var cachedFlexiblePrice: Double? = null
    private var cachedFlexiblePriceTime: Instant? = null

    companion object {
        /** 7 days — TTL for the flexible price in-memory cache. */
        private const val FLEXIBLE_CACHE_TTL_SECONDS = 7L * 24 * 3600
        /** 30 minutes in milliseconds — the half-hour slot interval. */
        private const val HALF_HOUR_MILLIS = 1_800_000L
    }

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
        channelFlow {
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
        }.flowOn(Dispatchers.IO)

    override fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        val pricesFlow = observeAgilePriceEntities(start, end)
            .distinctUntilChanged()

        val consumptionFlow = channelFlow {
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()

            // Trigger data loading in the background.
            launch(Dispatchers.IO) { getConsumption(start, end) }

            _consumption
                .map { entities ->
                    entities.filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                }
                .collect { send(it) }
        }.distinctUntilChanged()

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

    // ── Public API: get ─────────────────────────────────────────────

    /**
     * Get agile prices for [start]..[end].
     *
     * 1. If the in-memory cache already covers the full range, return immediately.
     * 2. Otherwise query Room.
     * 3. If Room is empty for this range, fetch from the API, persist to Room,
     *    then query Room again.
     * 4. Merge results into the in-memory cache.
     */
    override suspend fun getAgilePrices(start: Instant, end: Instant): List<AgilePrice> {
        return withContext(Dispatchers.IO) {
            // Fast path: in-memory cache already covers the range
            val currentStart = cachedPricesStart
            val currentEnd = cachedPricesEnd
            if (currentStart != null && currentEnd != null &&
                !start.isBefore(currentStart) && !end.isAfter(currentEnd)
            ) {
                val cached = _agilePrices.value
                    .filter { it.validFrom >= start.toEpochMilli() && it.validTo <= end.toEpochMilli() }
                return@withContext cached.map { it.toDomain() }
            }

            // Query Room
            val roomEntities = agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
            val entities = if (roomEntities.isEmpty()) {
                // Room empty — fetch from API, persist, query again
                val apiResult = fetchAndPersistAgilePrices(start, end)
                if (apiResult.isSuccess) {
                    agilePriceDao.queryRange(start.toEpochMilli(), end.toEpochMilli())
                } else {
                    emptyList()
                }
            } else {
                roomEntities
            }

            mergeAndEmitAgilePrices(entities, start, end)
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refreshStandingCharges(start: Instant, end: Instant): Result<Unit> {
        return standingChargeCacheStore.refreshFromApi(start, end)
    }

    /**
     * Get consumption for [start]..[end].
     *
     * 1. Demo mode → generate synthetic data, persist, return.
     * 2. If the in-memory cache already covers the full range, return immediately.
     * 3. If Room has all expected half-hour slots, return from Room.
     * 4. If Room has no data, fetch the entire range from the API, persist, re-query Room.
     * 5. If Room has partial data, detect and fill gaps from the API, then re-query Room.
     * 6. Merge results into the in-memory cache.
     */
    override suspend fun getConsumption(start: Instant, end: Instant): List<ConsumptionRecord> {
        return withContext(Dispatchers.IO) {
            val isDemo = preferencesRepository.isDemoMode.first()
            if (isDemo) {
                val generated = DemoDataGenerator.generateConsumptionEntities(start, end)
                consumptionDao.insertAll(generated)
                mergeAndEmitConsumption(generated, start, end)
                return@withContext generated.map { it.toDomain() }
            }

            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()
            val expectedCount = ((endMs - startMs) / HALF_HOUR_MILLIS).toInt()

            if (expectedCount <= 0) {
                mergeAndEmitConsumption(emptyList(), start, end)
                return@withContext emptyList()
            }

            // Tier 1: in-memory cache
            val currentStart = cachedConsumptionStart
            val currentEnd = cachedConsumptionEnd
            if (currentStart != null && currentEnd != null &&
                !start.isBefore(currentStart) && !end.isAfter(currentEnd)
            ) {
                val cached = _consumption.value
                    .filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                return@withContext cached.map { it.toDomain() }
            }

            // Tier 2: Room
            val actualCount = consumptionDao.countInRange(mpan, startMs, endMs)
            val entities = if (actualCount >= expectedCount) {
                // All data present — read from Room.
                consumptionDao.queryRange(mpan, startMs, endMs)
            } else if (actualCount == 0) {
                // Tier 3a: nothing in Room — fetch entire range from API.
                val apiResult = fetchAndPersistConsumption(start, end)
                if (apiResult.isSuccess) {
                    consumptionDao.queryRange(mpan, startMs, endMs)
                } else {
                    emptyList()
                }
            } else {
                // Tier 3b: partial data — detect and fill gaps.
                fillConsumptionGaps(mpan, start, end)
                consumptionDao.queryRange(mpan, startMs, endMs)
            }

            mergeAndEmitConsumption(entities, start, end)
            entities.map { it.toDomain() }
        }
    }

    override suspend fun wipeAllCaches() {
        withContext(Dispatchers.IO) {
            // Clear agile price in-memory cache + Room
            _agilePrices.value = emptyList()
            cachedPricesStart = null
            cachedPricesEnd = null
            agilePriceDao.deleteAll()
            // Clear consumption in-memory cache + Room
            _consumption.value = emptyList()
            cachedConsumptionStart = null
            cachedConsumptionEnd = null
            consumptionDao.deleteAll()
            // Clear flexible price cache
            cachedFlexiblePrice = null
            cachedFlexiblePriceTime = null
            // Clear other cache stores
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun fetchFlexiblePrice(): Result<Double> {
        return withContext(Dispatchers.IO) {
            // Return cached value if still fresh (7-day TTL)
            val cached = cachedFlexiblePrice
            val cachedTime = cachedFlexiblePriceTime
            if (cached != null && cachedTime != null &&
                java.time.Instant.now().epochSecond - cachedTime.epochSecond < FLEXIBLE_CACHE_TTL_SECONDS
            ) {
                return@withContext Result.success(cached)
            }

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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
                        cachedFlexiblePrice = current.valueIncVat
                        cachedFlexiblePriceTime = java.time.Instant.now()
                        return@withContext Result.success(current.valueIncVat)
                    }
                }

                Result.failure(
                    lastError ?: Exception("No flexible rate found across ${windows.size} time windows")
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

    // ── Consumption private helpers ─────────────────────────────────

    /**
     * Merge [newEntities] into the in-memory consumption cache, expanding the tracked range.
     * Deduplicates by `intervalStart` (primary key component), preferring newer entries.
     */
    private fun mergeAndEmitConsumption(
        newEntities: List<ConsumptionEntity>,
        requestedStart: Instant,
        requestedEnd: Instant
    ) {
        if (newEntities.isEmpty()) {
            cachedConsumptionStart = minOf(cachedConsumptionStart ?: requestedStart, requestedStart)
            cachedConsumptionEnd = maxOf(cachedConsumptionEnd ?: requestedEnd, requestedEnd)
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
        cachedConsumptionStart = minOf(cachedConsumptionStart ?: requestedStart, requestedStart)
        cachedConsumptionEnd = maxOf(cachedConsumptionEnd ?: requestedEnd, requestedEnd)
    }

    /**
     * Fetch consumption from the Octopus usage API and insert into Room.
     * Requires authentication (mpan + serial).
     */
    private suspend fun fetchAndPersistConsumption(start: Instant, end: Instant): Result<Unit> {
        return try {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            val serial = preferencesRepository.serialNumberFlow.first() ?: ""
            val allConsumption = fetchAllConsumptionFromApi(mpan, serial, start, end)
            val entities = allConsumption.map { it.toEntity(mpan, serial) }
            consumptionDao.insertAll(entities)
            preferencesRepository.saveLastConsumptionRefresh(System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch consumption data from the Octopus API, following pagination links.
     */
    private suspend fun fetchAllConsumptionFromApi(
        mpan: String,
        serial: String,
        start: Instant,
        end: Instant
    ): List<ConsumptionDto> {
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllConsumptionPages { url ->
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
     * Follow pagination links from the Octopus API until all consumption pages are fetched.
     */
    private suspend fun fetchAllConsumptionPages(
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

    /**
     * Detect and fill gaps in Room data for [start]..[end].
     * Identifies contiguous blocks of missing half-hour slots and fetches
     * each block from the API synchronously (one at a time).
     */
    private suspend fun fillConsumptionGaps(mpan: String, start: Instant, end: Instant) {
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
            fetchAndPersistConsumption(gStart, gEnd)
            // Continue even on failure — partial fill is better than nothing.
        }
    }

}
