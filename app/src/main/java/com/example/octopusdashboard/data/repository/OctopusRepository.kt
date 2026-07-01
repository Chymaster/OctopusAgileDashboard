package com.example.octopusdashboard.data.repository

import com.example.octopusdashboard.domain.model.AgilePrice
import com.example.octopusdashboard.domain.model.ConsumptionRecord
import com.example.octopusdashboard.domain.model.HalfHourPoint
import com.example.octopusdashboard.domain.model.TariffConfig
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface OctopusRepository {

    fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>>

    fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>>

    fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>>

    suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit>

    suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit>

    suspend fun fetchMeterGsp(mpan: String): Result<String>

    suspend fun testConnection(): Result<Unit>

    suspend fun fetchFlexiblePrice(): Result<Double>
}
