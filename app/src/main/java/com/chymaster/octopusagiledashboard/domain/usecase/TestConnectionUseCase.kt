package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import javax.inject.Inject

class TestConnectionUseCase @Inject constructor(
    private val repository: OctopusRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return repository.testConnection()
    }
}
