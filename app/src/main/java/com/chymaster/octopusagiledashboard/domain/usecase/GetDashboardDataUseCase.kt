package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject

class GetDashboardDataUseCase @Inject constructor(
    private val repository: OctopusRepository
) {
    operator fun invoke(start: Instant, end: Instant): Flow<List<HalfHourPoint>> {
        return repository.observeDashboardData(start, end)
    }
}
