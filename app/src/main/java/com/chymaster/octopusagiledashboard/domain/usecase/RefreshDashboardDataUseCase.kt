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
            val standingChargesResult = async { repository.refreshStandingCharges(start, end) }

            val prices = pricesResult.await()
            // Standing charges are optional — don't fail if they error
            standingChargesResult.await()

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
