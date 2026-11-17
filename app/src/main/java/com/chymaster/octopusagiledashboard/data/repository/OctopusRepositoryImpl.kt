package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.core.network.OctopusGraphQLClient
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.core.util.TariffCodes
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
import com.chymaster.octopusagiledashboard.data.remote.dto.ProductDto
import com.chymaster.octopusagiledashboard.data.remote.dto.StandingChargeDto
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import com.chymaster.octopusagiledashboard.domain.model.TariffOption
import com.chymaster.octopusagiledashboard.domain.model.TariffSegment
import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage
import com.chymaster.octopusagiledashboard.domain.tariff.SlotRateSelector
import com.chymaster.octopusagiledashboard.domain.tariff.TariffScheduleResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class OctopusRepositoryImpl @Inject constructor(
    private val apiService: OctopusApiService,
    private val graphQLClient: OctopusGraphQLClient,
    private val preferencesRepository: UserPreferencesRepository,
    private val tariffScheduleRepository: TariffScheduleRepository,
    private val agilePriceDao: AgilePriceDao,
    private val consumptionDao: ConsumptionDao,
    private val standingChargeCacheStore: StandingChargeCacheStore
) : OctopusRepository {

    // ── Agile price in-memory cache ─────────────────────────────────

    /** Unit rates for every tariff currently cached, keyed by (validFrom, tariffCode). */
    private val _agilePrices = MutableStateFlow<List<AgilePriceEntity>>(emptyList())

    /** Window of rates already fetched, per tariff code. */
    private data class Coverage(
        val start: Instant,
        val end: Instant,
        val attemptedAt: Instant
    )

    private val priceCoverage = ConcurrentHashMap<String, Coverage>()

    /** One mutex per tariff so concurrent callers fetch each tariff at most once. */
    private val priceFetchMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Bumped by [wipeAllCaches]. Observed flows re-launch their fetch when it
     * changes, so a credential wipe recovers an already-collected flow.
     */
    private val cacheEpoch = MutableStateFlow(0L)

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
        /** Don't re-attempt a rate fetch that returned nothing for this long. */
        private const val PRICE_FETCH_RETRY_SECONDS = 30L * 60
        /** Coverage of a window that reaches into the future expires this fast. */
        private const val PRICE_COVERAGE_TTL_SECONDS = 15L * 60
    }

    // ── Public API: observe ──────────────────────────────────────────

    /**
     * Observes the unit rate of the tariff in force in each half-hour slot of
     * `[start, end)`. One [AgilePrice] per slot, carrying its tariff code.
     */
    override fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>> =
        observeSelectedPrices(start, end)

    override fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> =
        channelFlow {
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()

            // Trigger data loading in the background. This populates the
            // in-memory StateFlow which the observation below reacts to.
            launch(Dispatchers.IO) {
                getConsumption(start, end).onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) {
                        android.util.Log.w("OctopusRepo", "Background consumption fetch failed", e)
                    }
                }
            }

            _consumption
                .map { entities ->
                    entities
                        .filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                        .map { it.toDomain() }
                }
                .collect { send(it) }
        }.flowOn(Dispatchers.IO)

    override fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        // Re-launch whenever the cache is wiped or the effective tariff schedule
        // changes, so a newly-required tariff is fetched without a manual refresh.
        val pricesFlow = cacheEpoch.flatMapLatest {
            tariffScheduleRepository.state.flatMapLatest {
                channelFlow {
                    launch(Dispatchers.IO) {
                        getAgilePrices(start, end).onFailure { e ->
                            if (e !is kotlinx.coroutines.CancellationException) {
                                android.util.Log.w("OctopusRepo", "Background price fetch failed", e)
                            }
                        }
                    }
                    observeSelectedPrices(start, end).collect { send(it) }
                }.distinctUntilChanged()
            }
        }

        val consumptionFlow = channelFlow {
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()

            // Trigger data loading in the background.
            launch(Dispatchers.IO) {
                getConsumption(start, end).onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) {
                        android.util.Log.w("OctopusRepo", "Background consumption fetch failed", e)
                    }
                }
            }

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

    override fun observeStandingChargeTotal(start: Instant, end: Instant): Flow<Double?> =
        standingChargeCacheStore.observeTotalCost(start, end)

    // ── Public API: get ─────────────────────────────────────────────

    /**
     * Unit rates for the tariffs in force over `[start, end)`, normalised to one
     * rate per half-hour slot.
     *
     * The effective [TariffSchedule] is clipped to the range; each tariff's
     * rates are then loaded with the tiered strategy (in-memory coverage →
     * Room → API), fetched with a lookback so a daily rate that started before
     * the range still prices its first slot.
     */
    override suspend fun getAgilePrices(start: Instant, end: Instant): Result<List<AgilePrice>> {
        return withContext(Dispatchers.IO) {
            try {
                val schedule = tariffScheduleRepository.get()
                val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
                if (segments.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                val isDemo = preferencesRepository.isDemoMode.first()
                var anySuccess = false
                var lastFailure: Throwable? = null
                for (segment in segments) {
                    val result = ensureRates(segment, isDemo)
                    if (result.isSuccess) anySuccess = true else lastFailure = result.exceptionOrNull()
                }

                val codes = segments.map { it.tariffCode }.distinct()
                val lookbackStart = start.minusSeconds(Constants.RATE_FETCH_LOOKBACK_SECONDS)
                val rows = agilePriceDao.queryRangeForTariffs(
                    codes,
                    lookbackStart.toEpochMilli(),
                    end.toEpochMilli()
                )
                mergeAndEmitAgilePrices(rows)

                if (!anySuccess && lastFailure != null) {
                    return@withContext Result.failure(lastFailure)
                }
                Result.success(selectSlotPrices(rows, segments, start, end))
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
    }

    /**
     * What still has to be fetched so [rows] describe their tariff's rates up
     * to [endMs]. Shapes the fetch decision only — see [RateCoverage.plan].
     */
    private fun loadPlan(
        rows: List<AgilePriceEntity>,
        endMs: Long,
        expectedCount: Int
    ): RateCoverage.Load =
        RateCoverage.plan(
            rows.map { RateCoverage.Period(it.validFrom, it.validTo) },
            endMs,
            expectedCount
        )

    /**
     * Makes sure the rates for [segment]'s tariff are cached over the segment
     * (plus the lookback), fetching only when coverage is missing.
     */
    private suspend fun ensureRates(
        segment: TariffSegment,
        isDemo: Boolean
    ): Result<Unit> {
        val tariffCode = segment.tariffCode
        val fetchStart = segment.start.minusSeconds(Constants.RATE_FETCH_LOOKBACK_SECONDS)
        val fetchEnd = segment.end
        val startMs = fetchStart.toEpochMilli()
        val endMs = fetchEnd.toEpochMilli()

        val mutex = priceFetchMutexes.getOrPut(tariffCode) { Mutex() }
        return mutex.withLock {
            val now = Instant.now()
            val coverage = priceCoverage[tariffCode]
            val covered = coverage != null &&
                !fetchStart.isBefore(coverage.start) &&
                !fetchEnd.isAfter(coverage.end)
            // A window that reaches into the future can gain rates later (Agile
            // publishes a day ahead), so its coverage expires quickly. Historical
            // windows never change.
            val coveredFresh = covered && (!fetchEnd.isAfter(now) ||
                java.time.Duration.between(coverage!!.attemptedAt, now).seconds <
                PRICE_COVERAGE_TTL_SECONDS)
            if (coveredFresh) {
                return@withLock Result.success(Unit)
            }
            // A window that recently produced nothing is not retried on every
            // call (historical ranges with no rates, offline, etc.).
            if (coverage != null &&
                java.time.Duration.between(coverage.attemptedAt, now).seconds <
                PRICE_FETCH_RETRY_SECONDS
            ) {
                return@withLock Result.success(Unit)
            }

            if (isDemo) {
                val generated = DemoDataGenerator.generateAgilePriceEntities(
                    fetchStart, fetchEnd, tariffCode
                )
                // Drop any stale off-grid rows written by older demo versions so
                // they can't linger as duplicates or skew Room's range counts.
                agilePriceDao.deleteNonCanonicalInRange(startMs, endMs)
                agilePriceDao.insertAll(generated)
                // Real API rates are always grid-aligned, so clearing off-grid
                // rows from the in-memory cache is safe.
                _agilePrices.value = _agilePrices.value.filter { it.validFrom % HALF_HOUR_MILLIS == 0L }
                priceCoverage[tariffCode] = Coverage(fetchStart, fetchEnd, now)
                return@withLock Result.success(Unit)
            }

            val expectedCount = ((endMs - startMs) / HALF_HOUR_MILLIS).toInt()
            if (expectedCount <= 0) {
                priceCoverage[tariffCode] = Coverage(fetchStart, fetchEnd, now)
                return@withLock Result.success(Unit)
            }

            val sample = agilePriceDao.queryRangeForTariff(tariffCode, startMs, endMs)

            val result = when (loadPlan(sample, endMs, expectedCount)) {
                RateCoverage.Load.NONE -> Result.success(Unit)
                // Nothing cached, or a daily/multi-period tariff whose cached
                // periods stop before the window end: Octopus publishes future
                // periods in batches, so (re)fetch the window. Rows are replaced
                // on (validFrom, tariffCode), so this never duplicates them.
                RateCoverage.Load.FETCH_WINDOW -> fetchAndPersistAgilePrices(
                    productCode = segment.productCode,
                    tariffCode = tariffCode,
                    start = fetchStart,
                    end = fetchEnd
                )
                // Partial half-hourly data — fill only the missing blocks.
                RateCoverage.Load.FILL_GAPS -> {
                    fillAgilePriceGaps(segment.productCode, tariffCode, fetchStart, fetchEnd)
                    Result.success(Unit)
                }
            }

            // Only claim coverage when the window really is complete; otherwise
            // remember the attempt purely to throttle the next retry.
            val complete = loadPlan(
                agilePriceDao.queryRangeForTariff(tariffCode, startMs, endMs),
                endMs,
                expectedCount
            ) == RateCoverage.Load.NONE
            priceCoverage[tariffCode] = if (result.isSuccess && complete) {
                Coverage(fetchStart, fetchEnd, now)
            } else {
                Coverage(fetchEnd, fetchStart, now)
            }
            result
        }
    }

    /**
     * Slot-normalised prices for `[start, end)` from the cached rows, filtered
     * to the schedule's segments.
     */
    private fun selectSlotPrices(
        rows: List<AgilePriceEntity>,
        segments: List<TariffSegment>,
        start: Instant,
        end: Instant
    ): List<AgilePrice> {
        if (segments.isEmpty() || rows.isEmpty()) return emptyList()
        val slotTariffs = TariffScheduleResolver.buildSlotTariffMap(segments, start, end)
        if (slotTariffs.isEmpty()) return emptyList()
        val codes = segments.map { it.tariffCode }.toSet()
        val rates = rows
            .asSequence()
            .filter { it.tariffCode in codes }
            .map { it.toDomain() }
            .toList()
        return SlotRateSelector.selectSlotRates(rates, slotTariffs)
    }

    /** Reactive [selectSlotPrices] over the in-memory cache and the schedule. */
    private fun observeSelectedPrices(start: Instant, end: Instant): Flow<List<AgilePrice>> =
        combine(_agilePrices, tariffScheduleRepository.state) { rows, schedule ->
            val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
            selectSlotPrices(rows, segments, start, end)
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

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
    override suspend fun getConsumption(start: Instant, end: Instant): Result<List<ConsumptionRecord>> {
        return withContext(Dispatchers.IO) {
            val isDemo = preferencesRepository.isDemoMode.first()
            if (isDemo) {
                val generated = DemoDataGenerator.generateConsumptionEntities(start, end)
                consumptionDao.insertAll(generated)
                mergeAndEmitConsumption(generated, start, end)
                return@withContext Result.success(generated.map { it.toDomain() })
            }

            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()
            val expectedCount = ((endMs - startMs) / HALF_HOUR_MILLIS).toInt()

            if (expectedCount <= 0) {
                mergeAndEmitConsumption(emptyList(), start, end)
                return@withContext Result.success(emptyList())
            }

            // Tier 1: in-memory cache
            val currentStart = cachedConsumptionStart
            val currentEnd = cachedConsumptionEnd
            if (currentStart != null && currentEnd != null &&
                !start.isBefore(currentStart) && !end.isAfter(currentEnd)
            ) {
                val cached = _consumption.value
                    .filter { it.intervalStart >= startMs && it.intervalStart < endMs }
                return@withContext Result.success(cached.map { it.toDomain() })
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
                    return@withContext Result.failure(
                        apiResult.exceptionOrNull() ?: ApiError.NoDataError()
                    )
                }
            } else {
                // Tier 3b: partial data — detect and fill gaps.
                fillConsumptionGaps(mpan, start, end)
                consumptionDao.queryRange(mpan, startMs, endMs)
            }

            mergeAndEmitConsumption(entities, start, end)
            Result.success(entities.map { it.toDomain() })
        }
    }

    override suspend fun wipeAllCaches() {
        withContext(Dispatchers.IO) {
            // Clear the agile price in-memory cache + Room
            _agilePrices.value = emptyList()
            priceCoverage.clear()
            priceFetchMutexes.clear()
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
            // Forget the detected tariff history — it belongs to the old credentials
            tariffScheduleRepository.clear()
            // Clear Kraken GraphQL token so it's re-obtained with new creds
            graphQLClient.clearCache()
            // Re-trigger already-collected data flows
            cacheEpoch.value = cacheEpoch.value + 1
        }
    }

    override suspend fun fetchMeterGsp(mpan: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getMeterPoint(mpan)
                if (response.isSuccessful) {
                    val body = response.body()
                        ?: return@withContext Result.failure(ApiError.NoDataError("Empty response"))
                    Result.success(body.gsp)
                } else {
                    Result.failure(ApiError.fromHttpCode(response.code()))
                }
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
                val flexibleTariff = TariffCodes.build(flexibleProduct, gsp)

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
                        lastError = ApiError.fromHttpCode(response.code())
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
            } catch (e: ApiError) {
                Result.failure(e)
            } catch (e: java.io.IOException) {
                Result.failure(ApiError.NetworkError(cause = e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun fetchMeterSerials(mpan: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val query = """{ meterPoints(mpan: "$mpan") { meters { serialNumber } } }"""
                val result = graphQLClient.execute(query)

                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    return@withContext Result.failure(
                        ApiError.NoDataError(
                            error?.message
                                ?: "Failed to fetch meter serials — " +
                                    "check your API key and MPAN."
                        )
                    )
                }

                val data = result.getOrThrow()
                val meterPoints = data["meterPoints"]?.jsonObject
                    ?: return@withContext Result.failure(
                        ApiError.NoDataError("No meter point data returned")
                    )

                val meters = meterPoints["meters"]?.jsonArray
                    ?: return@withContext Result.failure(
                        ApiError.NoDataError("No meters data returned")
                    )

                val serials = meters.map { meter ->
                    meter.jsonObject["serialNumber"]!!.jsonPrimitive.content
                }

                if (serials.isEmpty()) {
                    Result.failure(
                        ApiError.NoDataError(
                            "Enter your meter serial number manually — " +
                                "find it on your Octopus dashboard or physical meter."
                        )
                    )
                } else {
                    Result.success(serials)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Result.failure(ApiError.NetworkError(cause = e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun fetchAccountNumbers(): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val query = """{ viewer { accounts { number } } }"""
                val result = graphQLClient.execute(query)

                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    return@withContext Result.failure(
                        ApiError.NoDataError(
                            error?.message
                                ?: "Failed to fetch account number — " +
                                    "check your API key."
                        )
                    )
                }

                val data = result.getOrThrow()
                val viewer = data["viewer"]?.jsonObject
                    ?: return@withContext Result.failure(
                        ApiError.NoDataError("No viewer data returned")
                    )

                val accounts = viewer["accounts"]?.jsonArray
                    ?: return@withContext Result.failure(
                        ApiError.NoDataError("No accounts data returned")
                    )

                val numbers = accounts.map { account ->
                    account.jsonObject["number"]!!.jsonPrimitive.content
                }

                if (numbers.isEmpty()) {
                    Result.failure(
                        ApiError.NoDataError("No accounts found — verify your API key.")
                    )
                } else {
                    Result.success(numbers)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Result.failure(ApiError.NetworkError(cause = e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun testConnection(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val mpan = preferencesRepository.mpanFlow.first()
                    ?: return@withContext Result.failure(ApiError.NoDataError("MPAN not configured"))
                val response = apiService.getMeterPoint(mpan)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(ApiError.fromHttpCode(response.code()))
                }
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
    }

    override suspend fun fetchAvailableTariffs(): Result<List<TariffOption>> {
        return withContext(Dispatchers.IO) {
            try {
                val now = Instant.now()
                val products = fetchAllPages<ProductDto> { url ->
                    if (url != null) apiService.getProductsByUrl(url)
                    else apiService.getProducts()
                }
                val options = products
                    .filter { it.direction == "IMPORT" }
                    .filter { it.isPrepay != true }
                    .filter { it.brand == "OCTOPUS_ENERGY" }
                    .filter { dto ->
                        dto.availableFrom?.let { OffsetDateTime.parse(it).toInstant() <= now } ?: true
                    }
                    .filter { dto ->
                        dto.availableTo?.let { OffsetDateTime.parse(it).toInstant() >= now } ?: true
                    }
                    .map { it.toDomain() }
                    .distinctBy { it.id }
                    .sortedBy { it.displayName.lowercase() }
                Result.success(options)
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
    }

    override suspend fun fetchTariffRates(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): Result<List<AgilePrice>> {
        return withContext(Dispatchers.IO) {
            try {
                val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
                val dtoRates = fetchAllAgilePages { url ->
                    if (url != null) apiService.getAgileRatesByUrl(url)
                    else apiService.getAgileRates(
                        product = productCode,
                        tariff = tariffCode,
                        periodFrom = startStr,
                        periodTo = endStr
                    )
                }
                val rates = dedupeRatesByPaymentMethod(dtoRates)
                    .map { it.toDomain(tariffCode) }
                    .sortedBy { it.validFrom }
                Result.success(rates)
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
    }

    override suspend fun fetchTariffStandingCharges(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): Result<List<StandingCharge>> {
        return withContext(Dispatchers.IO) {
            try {
                val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
                val dtoCharges = fetchAllPages<StandingChargeDto> { url ->
                    if (url != null) apiService.getStandingChargesByUrl(url)
                    else apiService.getStandingCharges(
                        product = productCode,
                        tariff = tariffCode,
                        periodFrom = startStr,
                        periodTo = endStr
                    )
                }
                val charges = dedupeStandingChargesByPaymentMethod(dtoCharges)
                    .map { it.toDomain(tariffCode) }
                    .sortedBy { it.validFrom }
                Result.success(charges)
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
    }

    /**
     * Combine a list of slot-normalised [AgilePrice] with a list of
     * [ConsumptionEntity] into the [HalfHourPoint]s the chart expects. Always
     * start from prices and merge in the matching consumption row by
     * `intervalStart` (== price `validFrom` as epoch millis).
     */
    private fun buildHalfHourPoints(
        prices: List<AgilePrice>,
        consumption: List<ConsumptionEntity>
    ): List<HalfHourPoint> {
        val consumptionMap = consumption.associate { it.intervalStart to it }
        return prices.map { price ->
            val cons = consumptionMap[price.validFrom.toEpochMilli()]
            HalfHourPoint(
                intervalStart = price.validFrom,
                intervalEnd = price.validTo,
                priceIncVat = price.priceIncVat,
                consumptionKwh = cons?.consumption,
                costIncVat = if (cons != null) cons.consumption * price.priceIncVat else null,
                tariffCode = price.tariffCode
            )
        }
    }

    // ── Agile price private helpers ──────────────────────────────────

    /**
     * Merge [rows] into the in-memory agile price cache, deduplicating by
     * `(validFrom, tariffCode)` and keeping rates for other ranges/tariffs so
     * a narrow fetch (e.g. jump-to-date) does not drop the wider series.
     */
    private fun mergeAndEmitAgilePrices(rows: List<AgilePriceEntity>) {
        if (rows.isEmpty()) return
        _agilePrices.value = (_agilePrices.value + rows)
            .associateBy { it.validFrom to it.tariffCode }
            .values
            .sortedWith(compareBy({ it.validFrom }, { it.tariffCode }))
    }

    /**
     * Fetch one tariff's unit rates from the Octopus public API and insert them
     * into Room. Should NOT be called in demo mode — use [DemoDataGenerator].
     */
    private suspend fun fetchAndPersistAgilePrices(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): Result<Unit> {
        return try {
            val allRates = fetchPublicAgilePrices(productCode, tariffCode, start, end)
            val entities = allRates.map { it.toEntity(tariffCode) }
            agilePriceDao.insertAll(entities)
            preferencesRepository.saveLastPricesRefresh(System.currentTimeMillis())
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
     * Fetch one tariff's unit rates from the public (unauthenticated) Octopus
     * endpoint. Follows pagination links to get all results.
     */
    private suspend fun fetchPublicAgilePrices(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ): List<AgileRateDto> {
        val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
        val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
        return fetchAllAgilePages { url ->
            if (url != null) apiService.getAgileRatesByUrl(url)
            else apiService.getAgileRates(
                product = productCode,
                tariff = tariffCode,
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
                throw ApiError.fromHttpCode(response.code())
            }
            val body = response.body() ?: break
            allRates.addAll(body.results)
            url = body.next
        } while (url != null)

        return allRates
    }

    /**
     * Generic pagination follower — the typed sibling of [fetchAllAgilePages].
     * Used for the products catalogue and standing-charge fetches.
     */
    private suspend fun <T> fetchAllPages(
        request: suspend (url: String?) -> Response<PaginatedResponse<T>>
    ): List<T> {
        val all = mutableListOf<T>()
        var url: String? = null

        do {
            val response = request(url)
            if (!response.isSuccessful) {
                throw ApiError.fromHttpCode(response.code())
            }
            val body = response.body() ?: break
            all.addAll(body.results)
            url = body.next
        } while (url != null)

        return all
    }

    /**
     * Collapse overlapping unit-rate entries that cover the same slot into a
     * single rate, preferring DIRECT_DEBIT (e.g. Flexible Octopus returns both
     * a DIRECT_DEBIT and a NON_DIRECT_DEBIT rate for the same period). The
     * domain model drops `paymentMethod`, so this must happen before mapping.
     */
    private fun dedupeRatesByPaymentMethod(rates: List<AgileRateDto>): List<AgileRateDto> =
        rates.groupBy { it.validFrom }.values.map { group ->
            group.firstOrNull { it.paymentMethod == "DIRECT_DEBIT" }
                ?: group.firstOrNull { it.paymentMethod == null }
                ?: group.first()
        }

    /**
     * Same payment-method collapse as [dedupeRatesByPaymentMethod] but for
     * standing charges (Flexible also returns DD / non-DD variants).
     */
    private fun dedupeStandingChargesByPaymentMethod(charges: List<StandingChargeDto>): List<StandingChargeDto> =
        charges.groupBy { it.validFrom }.values.map { group ->
            group.firstOrNull { it.paymentMethod == "DIRECT_DEBIT" }
                ?: group.firstOrNull { it.paymentMethod == null }
                ?: group.first()
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
        } catch (e: ApiError) {
            Result.failure(e)
        } catch (e: java.io.IOException) {
            Result.failure(ApiError.NetworkError(cause = e))
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
                throw ApiError.fromHttpCode(response.code())
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

        // Find contiguous missing blocks. Iterate slots strictly within
        // [start, end): the boundary slot at `end` is outside the range, so
        // checking it would synthesise a bogus zero-length trailing gap.
        val gaps = mutableListOf<Pair<Instant, Instant>>()
        var gapStart: Instant? = null
        var t = start
        while (t.isBefore(end)) {
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

    /**
     * Detect and fill gaps in Room rate data for [tariffCode] over
     * `[start, end)`. Identifies contiguous blocks of missing half-hour slots
     * and fetches each block from the API synchronously (one at a time).
     */
    private suspend fun fillAgilePriceGaps(
        productCode: String,
        tariffCode: String,
        start: Instant,
        end: Instant
    ) {
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()

        val existingSlots = agilePriceDao.queryRangeForTariff(tariffCode, startMs, endMs)
            .map { it.validFrom }
            .toSet()

        // Find contiguous missing blocks. Iterate slots strictly within
        // [start, end): the boundary slot at `end` is outside the range, so
        // checking it would synthesise a bogus zero-length trailing gap.
        val gaps = mutableListOf<Pair<Instant, Instant>>()
        var gapStart: Instant? = null
        var t = start
        while (t.isBefore(end)) {
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
            fetchAndPersistAgilePrices(productCode, tariffCode, gStart, gEnd)
            // Continue even on failure — partial fill is better than nothing.
        }
    }

}
