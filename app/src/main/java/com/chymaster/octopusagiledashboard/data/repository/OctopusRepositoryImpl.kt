package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.core.util.DemoIdentifiers
import com.chymaster.octopusagiledashboard.data.local.DemoCacheStore
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import com.chymaster.octopusagiledashboard.data.local.dao.StandingChargeDao
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class OctopusRepositoryImpl @Inject constructor(
    private val apiService: OctopusApiService,
    private val agilePriceDao: AgilePriceDao,
    private val consumptionDao: ConsumptionDao,
    private val standingChargeDao: StandingChargeDao,
    private val preferencesRepository: UserPreferencesRepository,
    private val demoCacheStore: DemoCacheStore
) : OctopusRepository {

    override fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>> = flow {
        // Room is the source of truth for the Home / Future Prices timelines
        // regardless of credential state. The public Agile prices endpoint is
        // unauthenticated, so the Home screen shows real public prices even
        // in demo mode. In demo mode, [refreshAgilePrices] writes the public
        // API result to Room (with the demo tariff code) so this observation
        // sees the same data.
        emitAll(
            agilePriceDao.observeRange(start.toEpochMilli(), end.toEpochMilli())
                .map { entities -> entities.map { it.toDomain() } }
        )
    }.flowOn(Dispatchers.IO)

    override fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> = flow {
        if (isDemoMode()) {
            // Demo: filter the in-memory cache by range. All demo rows share
            // the sentinel MPAN so no per-MPAN filter is needed.
            val startMs = start.toEpochMilli()
            val endMs = end.toEpochMilli()
            emitAll(
                demoCacheStore.consumption
                    .map { all -> all.filter { it.intervalStart in startMs until endMs } }
                    .map { entities -> entities.map { it.toDomain() } }
            )
        } else {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            emitAll(
                consumptionDao.observeRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                    .map { entities -> entities.map { it.toDomain() } }
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        return flow {
            if (isDemoMode()) {
                // Demo: combine the in-memory price and consumption flows through
                // the same HalfHourPoint construction as the real path. The
                // combine lambda is identical; only the source of the upstream
                // flows differs.
                val startMs = start.toEpochMilli()
                val endMs = end.toEpochMilli()
                val pricesFlow = demoCacheStore.prices
                    .map { all -> all.filter { it.validFrom >= startMs && it.validTo <= endMs } }
                    .distinctUntilChanged()
                val consumptionFlow = demoCacheStore.consumption
                    .map { all -> all.filter { it.intervalStart in startMs until endMs } }
                    .distinctUntilChanged()

                var emittedOnce = false
                emitAll(
                    combine(pricesFlow, consumptionFlow) { prices, consumption ->
                        buildHalfHourPoints(prices, consumption)
                    }.debounce {
                        // First emission (cached data) passes through immediately.
                        // Subsequent emissions (from API refresh) are debounced so
                        // the seed-then-refresh sequence settles into a single UI
                        // update — this is what eliminates the flicker.
                        if (emittedOnce) 1000L else {
                            emittedOnce = true
                            0L
                        }
                    }
                )
            } else {
                val mpan = preferencesRepository.mpanFlow.first() ?: ""
                val pricesFlow = agilePriceDao.observeRange(start.toEpochMilli(), end.toEpochMilli())
                    .distinctUntilChanged()
                val consumptionFlow = consumptionDao.observeRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                    .distinctUntilChanged()

                var emittedOnce = false
                emitAll(
                    combine(pricesFlow, consumptionFlow) { prices, consumption ->
                        buildHalfHourPoints(prices, consumption)
                    }.debounce {
                        if (emittedOnce) 1000L else {
                            emittedOnce = true
                            0L
                        }
                    }
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun observeStandingCharges(start: Instant, end: Instant): Flow<List<StandingCharge>> = flow {
        if (isDemoMode()) {
            // Demo: a single synthetic entity covers any query range (validTo is
            // 10 years from "now"). The entity is the same regardless of [start]
            // and [end], so no range filter is applied at the observe layer.
            emitAll(
                demoCacheStore.standingCharges
                    .map { entities -> entities.map { it.toDomain() } }
            )
        } else {
            emitAll(
                standingChargeDao.observeRange(start.toEpochMilli(), end.toEpochMilli())
                    .map { entities -> entities.map { it.toDomain() } }
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun refreshStandingCharges(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (isDemoMode()) {
                    // Demo: the synthetic standing charge is fixed (50p/day). We
                    // still re-seed it on refresh so the StateFlow re-emits, but
                    // the value is the same.
                    demoCacheStore.overwriteStandingCharges(
                        listOf(DemoDataGenerator.generateStandingChargeEntity(Instant.now()))
                    )
                    Result.success(Unit)
                } else {
                    val tariffConfig = preferencesRepository.tariffConfig.first()
                    val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                    val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
                    val response = apiService.getStandingCharges(
                        product = tariffConfig.productCode,
                        tariff = tariffConfig.tariffCode,
                        periodFrom = startStr,
                        periodTo = endStr
                    )
                    if (response.isSuccessful) {
                        val body = response.body()
                            ?: return@withContext Result.failure(Exception("Empty response"))
                        val entities = body.results.map { it.toEntity(tariffConfig.tariffCode) }
                        standingChargeDao.insertAll(entities)
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("API error: ${response.code()} ${response.message()}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val tariffConfig = preferencesRepository.tariffConfig.first()
                val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
                val allRates = fetchAllPages { url ->
                    if (url != null) apiService.getAgileRatesByUrl(url)
                    else apiService.getAgileRates(
                        product = tariffConfig.productCode,
                        tariff = tariffConfig.tariffCode,
                        periodFrom = startStr,
                        periodTo = endStr
                    )
                }

                if (isDemoMode()) {
                    // Demo: write the public API result to BOTH the in-memory
                    // demo cache (so the Dashboard's observeDashboardData sees
                    // the fresh real prices) AND to Room (so the Home /
                    // Future Prices timelines continue to show real public
                    // prices via observeAgilePrices, which always reads from
                    // Room). The tariff code is the demo sentinel so the
                    // real-mode refresh's INSERT REPLACE will overwrite these
                    // rows by primary key when the user adds credentials.
                    val entities = allRates.map { it.toEntity(DemoIdentifiers.TARIFF) }
                    demoCacheStore.overwritePrices(entities)
                    agilePriceDao.insertAll(entities)
                    Result.success(Unit)
                } else {
                    // Insert with REPLACE strategy — no delete-first, avoids flash of empty data
                    val entities = allRates.map { it.toEntity(tariffConfig.tariffCode) }
                    agilePriceDao.insertAll(entities)
                    preferencesRepository.saveLastPricesRefresh(System.currentTimeMillis())
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (isDemoMode()) {
                    // Demo: consumption is already seeded by the ViewModel
                    // before the first observation, and is deterministic per
                    // slot. Nothing to do on refresh — the data is already
                    // correct.
                    Result.success(Unit)
                } else {
                    val mpan = preferencesRepository.mpanFlow.first()
                        ?: return@withContext Result.failure(Exception("MPAN not configured"))
                    val serial = preferencesRepository.serialNumberFlow.first()
                        ?: return@withContext Result.failure(Exception("Serial number not configured"))
                    val startStr = DateTimeFormatter.ISO_INSTANT.format(start)
                    val endStr = DateTimeFormatter.ISO_INSTANT.format(end)
                    val response = apiService.getConsumption(
                        mpan = mpan,
                        serial = serial,
                        periodFrom = startStr,
                        periodTo = endStr
                    )
                    if (response.isSuccessful) {
                        val body = response.body()
                            ?: return@withContext Result.failure(Exception("Empty response"))
                        val entities = body.results.map { it.toEntity(mpan, serial) }
                        consumptionDao.insertAll(entities)
                        preferencesRepository.saveLastConsumptionRefresh(System.currentTimeMillis())
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("API error: ${response.code()} ${response.message()}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun purgeAllUserData() {
        withContext(Dispatchers.IO) {
            // Wipe every cached entity from the local Room database. Called on
            // a real → demo credential flip so the user does not see a flash
            // of the previous user's data on the freshly-minted demo chart.
            // The in-memory demo store is wiped separately by the ViewModel.
            agilePriceDao.deleteAll()
            consumptionDao.deleteAll()
            standingChargeDao.deleteAll()
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
     * True when no Octopus credentials are configured. The repository's
     * `observe*` and `refresh*` methods use this to route demo traffic
     * through [demoCacheStore] and real traffic through Room + the API.
     *
     * Read once per call (via [kotlinx.coroutines.flow.first]) so a single
     * observe or refresh sees a stable credential state for its lifetime —
     * subsequent credential flips are handled by the ViewModel cancelling
     * the in-flight pipeline and relaunching.
     */
    private suspend fun isDemoMode(): Boolean = !preferencesRepository.hasCredentials.first()

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

    private suspend fun fetchAllPages(
        request: suspend (url: String?) -> retrofit2.Response<PaginatedResponse<AgileRateDto>>
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
