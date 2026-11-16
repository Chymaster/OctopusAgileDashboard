package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.data.repository.TariffScheduleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import javax.inject.Inject

class RefreshDashboardDataUseCase @Inject constructor(
    private val repository: OctopusRepository,
    private val tariffScheduleRepository: TariffScheduleRepository
) {
    suspend operator fun invoke(start: Instant, end: Instant): Result<Unit> {
        return coroutineScope {
            // Re-check the tariff history first (TTL-gated): a switch since the
            // last load changes which rates the range needs.
            val scheduleResult = async { tariffScheduleRepository.get() }
            val pricesResult = async { repository.getAgilePrices(start, end) }
            val standingChargesResult = async { repository.refreshStandingCharges(start, end) }

            // Detection and standing charges are optional — don't fail if they error.
            scheduleResult.await()
            standingChargesResult.await()
            val prices = pricesResult.await()

            // Propagate the repository's error (e.g. ApiError.Unauthorized,
            // ApiError.NetworkError) rather than inventing a generic message.
            // A successful call returning an empty list is NOT an error.
            prices.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        }
    }
}
