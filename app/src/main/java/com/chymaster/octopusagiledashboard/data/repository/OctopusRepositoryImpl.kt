package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.data.local.AgilePriceCacheStore
import com.chymaster.octopusagiledashboard.data.local.DemoCacheStore
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import com.chymaster.octopusagiledashboard.data.local.dao.StandingChargeDao
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.mapper.toDomain
import com.chymaster.octopusagiledashboard.data.mapper.toEntity
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class OctopusRepositoryImpl @Inject constructor(
    private val apiService: OctopusApiService,
    private val consumptionDao: ConsumptionDao,
    private val standingChargeDao: StandingChargeDao,
    private val preferencesRepository: UserPreferencesRepository,
    private val demoCacheStore: DemoCacheStore,
    private val agilePriceCacheStore: AgilePriceCacheStore
) : OctopusRepository {

    override fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>> =
        agilePriceCacheStore.observeRange(start, end)
            .flowOn(Dispatchers.IO)

    override fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> =
        preferencesRepository.hasCredentials
            .distinctUntilChanged()
            .transformLatest { hasCreds ->
                if (!hasCreds) {
                    ensureDemoConsumptionSeeded(start, end)
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
        // Prices come from the unified cache store (handles demo/real internally).
        val pricesFlow = agilePriceCacheStore.observeRangeEntities(start, end)
            .distinctUntilChanged()

        // Consumption still branches on demo/real mode.
        val consumptionFlow = preferencesRepository.hasCredentials
            .distinctUntilChanged()
            .transformLatest { hasCreds ->
                if (!hasCreds) {
                    ensureDemoConsumptionSeeded(start, end)
                    val startMs = start.toEpochMilli()
                    val endMs = end.toEpochMilli()
                    emitAll(
                        demoCacheStore.consumption
                            .map { all -> all.filter { it.intervalStart in startMs until endMs } }
                            .distinctUntilChanged()
                    )
                } else {
                    val mpan = preferencesRepository.mpanFlow.first() ?: ""
                    emitAll(
                        consumptionDao.observeRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                            .distinctUntilChanged()
                    )
                }
            }.flowOn(Dispatchers.IO)

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
        preferencesRepository.hasCredentials
            .distinctUntilChanged()
            .transformLatest { hasCreds ->
                if (!hasCreds) {
                    ensureDemoStandingChargeSeeded()
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
        return agilePriceCacheStore.refreshFromApi(start, end)
    }

    override suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (isDemoMode()) {
                    // Demo: re-seed the cache so the refresh button triggers
                    // a visible update. The data is deterministic per slot.
                    demoCacheStore.overwriteConsumption(
                        DemoDataGenerator.generateConsumptionEntities(start, end)
                    )
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

    override suspend fun wipeAllCaches() {
        withContext(Dispatchers.IO) {
            agilePriceCacheStore.clear()
            demoCacheStore.clearAll()
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
     * True when no Octopus credentials are configured. Used by `refresh*`
     * methods to route between demo cache writes and real API calls.
     */
    private suspend fun isDemoMode(): Boolean = !preferencesRepository.hasCredentials.first()

    /** Seed the demo consumption cache if empty, so the first [observeConsumption] emission is non-empty. */
    private fun ensureDemoConsumptionSeeded(start: Instant, end: Instant) {
        if (demoCacheStore.consumption.value.isEmpty()) {
            demoCacheStore.seedConsumption(start, end)
        }
    }

    /** Seed the demo standing charge cache if empty, so the first [observeStandingCharges] emission is non-empty. */
    private fun ensureDemoStandingChargeSeeded() {
        if (demoCacheStore.standingCharges.value.isEmpty()) {
            demoCacheStore.seedStandingCharge()
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

}
