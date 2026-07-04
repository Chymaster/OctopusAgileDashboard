package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import javax.inject.Inject

class RefreshDashboardDataUseCase @Inject constructor(
    private val repository: OctopusRepository
) {
    suspend operator fun invoke(start: Instant, end: Instant): Result<Unit> {
        return coroutineScope {
            val pricesResult = async { repository.refreshAgilePrices(start, end) }
            val consumptionResult = async { repository.refreshConsumption(start, end) }

            val prices = pricesResult.await()
            val consumption = consumptionResult.await()

            // Prices are required; consumption is optional (needs personal credentials)
            if (prices.isFailure) {
                Result.failure(prices.exceptionOrNull() ?: Exception("Failed to refresh prices"))
            } else {
                Result.success(Unit)
            }
        }
    }
}
