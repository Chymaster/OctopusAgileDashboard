package com.chymaster.octopusagiledashboard.data.repository

import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ConsumptionRecord
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface OctopusRepository {

    fun observeAgilePrices(start: Instant, end: Instant): Flow<List<AgilePrice>>

    /**
     * Observe raw [AgilePriceEntity] objects for [start]..[end].
     * Used internally for [observeDashboardData] which needs entity-level access
     * to build [HalfHourPoint]s.
     */
    fun observeAgilePriceEntities(start: Instant, end: Instant): Flow<List<AgilePriceEntity>>

    fun observeConsumption(start: Instant, end: Instant): Flow<List<ConsumptionRecord>>

    fun observeDashboardData(start: Instant, end: Instant): Flow<List<HalfHourPoint>>

    fun observeStandingCharges(start: Instant, end: Instant): Flow<List<StandingCharge>>

    /**
     * Get agile prices for [start]..[end].
     * Checks in-memory cache first, then Room, then fetches from the API only
     * if Room has no data for the requested range.
     * Returns the list of prices (empty on failure).
     */
    suspend fun getAgilePrices(start: Instant, end: Instant): List<AgilePrice>

    suspend fun getConsumption(start: Instant, end: Instant): List<ConsumptionRecord>

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
