package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.data.remote.api.CarbonIntensityApiService
import com.chymaster.octopusagiledashboard.domain.model.FuelMix
import com.chymaster.octopusagiledashboard.domain.model.GreenEnergyData
import com.chymaster.octopusagiledashboard.domain.model.LOW_CARBON_FUELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GreenEnergyRepositoryImpl @Inject constructor(
    private val apiService: CarbonIntensityApiService
) : GreenEnergyRepository {

    // In-memory cache: grid mix changes every ~30 min, refresh no more than every 15 min
    private var cachedData: GreenEnergyData? = null
    private val cacheTtlMs = 15 * 60 * 1000L

    override suspend fun fetchGenerationMix(): Result<GreenEnergyData> {
        return withContext(Dispatchers.IO) {
            // Return cache if fresh
            cachedData?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt.toEpochMilli() < cacheTtlMs) {
                    return@withContext Result.success(cached)
                }
            }

            try {
                val response = apiService.getGenerationMix()
                if (response.isSuccessful) {
                    val body = response.body()
                        ?: return@withContext Result.failure(Exception("Empty response"))

                    val fuelMix = body.data.generationmix.map { entry ->
                        FuelMix(fuel = entry.fuel, percentage = entry.perc)
                    }
                    val lowCarbonPerc = fuelMix
                        .filter { it.fuel in LOW_CARBON_FUELS }
                        .sumOf { it.percentage }

                    val data = GreenEnergyData(
                        lowCarbonPercentage = lowCarbonPerc,
                        fuelMix = fuelMix,
                        fetchedAt = Instant.now()
                    )
                    cachedData = data
                    Result.success(data)
                } else {
                    Result.failure(Exception("API error: ${response.code()} ${response.message()}"))
                }
            } catch (e: Exception) {
                // Return stale cache if available, otherwise propagate error
                cachedData?.let { return@withContext Result.success(it) }
                Result.failure(e)
            }
        }
    }
}
