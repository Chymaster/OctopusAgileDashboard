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
     * Load agile prices for [start]..[end] into the in-memory cache.
     * In demo mode, generates synthetic data. In real mode, reads from Room.
     */
    suspend fun loadAgilePrices(start: Instant, end: Instant)

    suspend fun refreshAgilePrices(start: Instant, end: Instant): Result<Unit>

    suspend fun refreshConsumption(start: Instant, end: Instant): Result<Unit>

    suspend fun refreshStandingCharges(start: Instant, end: Instant): Result<Unit>

    /**
     * Expand the cached agile price range backward by [additionalDays] days.
     * Used by infinite scroll-up in the Future Prices screen.
     */
    suspend fun expandAgilePriceHistoryBackward(additionalDays: Int)

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
