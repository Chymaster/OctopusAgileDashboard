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
     * Wipe every cached entity from the local Room database. Called on a
     * real → demo credential flip so the user does not see a flash of the
     * previous user's data on the freshly-minted demo chart.
     */
    suspend fun purgeAllUserData()

    suspend fun fetchMeterGsp(mpan: String): Result<String>

    suspend fun testConnection(): Result<Unit>

    suspend fun fetchFlexiblePrice(): Result<Double>
}
