package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface OctopusRepository {

    fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>>

    fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>>

    fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>>

    fun observeStandingCharges(start: Instant, end: Instant): Flow<List<StandingCharge>>

    suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit>

    suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit>

    suspend fun refreshStandingCharges(start: Instant, end: Instant): Result<Unit>

    /**
     * Wipe both the in-memory demo cache and the persistent Room cache.
     * Called on every credential save so no stale data from the previous
     * mode (demo or real) flashes on the next observation.
     */
    suspend fun wipeAllCaches()

    suspend fun fetchMeterGsp(mpan: String): Result<String>

    suspend fun testConnection(): Result<Unit>

    suspend fun fetchFlexiblePrice(): Result<Double>
}
