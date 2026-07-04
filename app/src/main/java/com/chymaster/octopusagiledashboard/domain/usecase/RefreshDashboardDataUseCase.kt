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
            val standingChargesResult = async { repository.refreshStandingCharges(start, end) }

            val prices = pricesResult.await()
            // Consumption and standing charges are optional — don't fail if they error
            consumptionResult.await()
            standingChargesResult.await()

            // Prices are required; consumption and standing charges are optional
            if (prices.isFailure) {
                Result.failure(prices.exceptionOrNull() ?: Exception("Failed to refresh prices"))
            } else {
                Result.success(Unit)
            }
        }
    }
}
