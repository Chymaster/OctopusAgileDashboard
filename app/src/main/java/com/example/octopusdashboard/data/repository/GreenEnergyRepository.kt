package com.example.octopusdashboard.data.repository

import com.example.octopusdashboard.domain.model.GreenEnergyData

interface GreenEnergyRepository {
    suspend fun fetchGenerationMix(): Result<GreenEnergyData>
}
