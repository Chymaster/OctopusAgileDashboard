package com.example.octopusdashboard.domain.usecase

import com.example.octopusdashboard.data.repository.OctopusRepository
import javax.inject.Inject

class TestConnectionUseCase @Inject constructor(
    private val repository: OctopusRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return repository.testConnection()
    }
}
