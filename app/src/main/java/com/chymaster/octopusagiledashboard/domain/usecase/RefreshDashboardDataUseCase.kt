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
            val pricesResult = async { repository.getAgilePrices(start, end) }
            val consumptionResult = async { repository.refreshConsumption(start, end) }
            val standingChargesResult = async { repository.refreshStandingCharges(start, end) }

            val prices = pricesResult.await()
            // Consumption and standing charges are optional — don't fail if they error
            consumptionResult.await()
            standingChargesResult.await()

            // Prices are required; empty list means failure
            if (prices.isEmpty()) {
                Result.failure(Exception("Failed to load prices"))
            } else {
                Result.success(Unit)
            }
        }
    }
}
