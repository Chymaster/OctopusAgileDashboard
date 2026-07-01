package com.example.octopusdashboard.data.repository

import com.example.octopusdashboard.core.util.Constants
import com.example.octopusdashboard.data.local.dao.AgilePriceDao
import com.example.octopusdashboard.data.local.dao.ConsumptionDao
import com.example.octopusdashboard.data.mapper.toDomain
import com.example.octopusdashboard.data.mapper.toEntity
import com.example.octopusdashboard.data.prefs.UserPreferencesRepository
import com.example.octopusdashboard.data.remote.api.OctopusApiService
import com.example.octopusdashboard.data.remote.dto.AgileRateDto
import com.example.octopusdashboard.data.remote.dto.PaginatedResponse
import com.example.octopusdashboard.domain.model.AgilePrice
import com.example.octopusdashboard.domain.model.ConsumptionRecord
import com.example.octopusdashboard.domain.model.HalfHourPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val preferencesRepository: UserPreferencesRepository
) : OctopusRepository {

    override fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>> {
        return agilePriceDao.observeRange(start.toEpochMilli(), end.toEpochMilli())
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(Dispatchers.IO)
    }

    override fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>> {
        return flow {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            emitAll(consumptionDao.observeRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                .map { entities -> entities.map { it.toDomain() } })
        }.flowOn(Dispatchers.IO)
    }

    override fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        return flow {
            val mpan = preferencesRepository.mpanFlow.first() ?: ""
            val pricesFlow = agilePriceDao.observeRange(start.toEpochMilli(), end.toEpochMilli())
                .distinctUntilChanged()
            val consumptionFlow = consumptionDao.observeRange(mpan, start.toEpochMilli(), end.toEpochMilli())
                .distinctUntilChanged()

            var emittedOnce = false
            emitAll(combine(pricesFlow, consumptionFlow) { prices, consumption ->
                val consumptionMap = consumption.associate { it.intervalStart to it }

                // Always start from prices — merge consumption where available
                prices.map { price ->
                    val cons = consumptionMap[price.validFrom]
                    HalfHourPoint(
                        intervalStart = Instant.ofEpochMilli(price.validFrom),
                        intervalEnd = Instant.ofEpochMilli(price.validTo),
                        priceIncVat = price.priceIncVat,
                        consumptionKwh = cons?.consumption,
                        costIncVat = if (cons != null) cons.consumption * price.priceIncVat else null
                    )
                }
            }.debounce {
                // First emission (cached data) passes through immediately.
                // Subsequent emissions (from API refresh) are debounced so that
                // the two parallel Room inserts (prices + consumption) settle
                // before we push a single update to the UI.
                if (emittedOnce) 1000L else {
                    emittedOnce = true
                    0L
                }
            })
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val tariffConfig = preferencesRepository.tariffConfig.first()
                    ?: return@withContext Result.failure(Exception("Tariff not configured"))

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

                // Insert with REPLACE strategy — no delete-first, avoids flash of empty data
                val entities = allRates.map { it.toEntity(tariffConfig.tariffCode) }
                agilePriceDao.insertAll(entities)

                preferencesRepository.saveLastPricesRefresh(System.currentTimeMillis())
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
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

                    // Insert with REPLACE strategy — no delete-first, avoids flash of empty data
                    val entities = body.results.map { it.toEntity(mpan, serial) }
                    consumptionDao.insertAll(entities)

                    preferencesRepository.saveLastConsumptionRefresh(System.currentTimeMillis())
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("API error: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
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
                    ?: return@withContext Result.failure(Exception("GSP not configured"))
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
