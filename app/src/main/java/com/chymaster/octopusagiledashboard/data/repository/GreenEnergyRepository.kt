package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.domain.model.GreenEnergyData

interface GreenEnergyRepository {
    suspend fun fetchGenerationMix(): Result<GreenEnergyData>
}
