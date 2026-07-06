package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for demo-mode consumption and standing charge data.
 * Agile prices are handled by [AgilePriceCacheStore] which owns the
 * single source of truth for half-hourly price data regardless of mode.
 *
 * The store is process-scoped. It is seeded by [seedConsumption] /
 * [seedStandingCharge] before the first observation in a screen, so the
 * first emission is a fully populated list — no flicker.
 */
@Singleton
class DemoCacheStore @Inject constructor() {

    private val _consumption = MutableStateFlow<List<ConsumptionEntity>>(emptyList())
    val consumption: StateFlow<List<ConsumptionEntity>> = _consumption.asStateFlow()

    private val _standingCharges = MutableStateFlow<List<StandingChargeEntity>>(emptyList())
    val standingCharges: StateFlow<List<StandingChargeEntity>> = _standingCharges.asStateFlow()

    /**
     * Populate the consumption cache with synthetic data for [start] to [end].
     * Replaces any previously cached list.
     */
    fun seedConsumption(start: Instant, end: Instant) {
        _consumption.value = DemoDataGenerator.generateConsumptionEntities(start, end)
    }

    /**
     * Populate the standing charge cache with a single synthetic entity that
     * covers any realistic query range.
     */
    fun seedStandingCharge() {
        _standingCharges.value = listOf(DemoDataGenerator.generateStandingChargeEntity(Instant.now()))
    }

    /**
     * Replace the consumption cache with [newConsumption]. Used by the
     * demo-mode refresh so the StateFlow re-emits and observers update.
     */
    fun overwriteConsumption(newConsumption: List<ConsumptionEntity>) {
        _consumption.value = newConsumption
    }

    /**
     * Replace the standing charge cache with [newCharges]. Currently only
     * ever called with the single synthetic entity (the demo standing charge
     * is fixed), but kept symmetric with [overwritePrices] for future
     * extension.
     */
    fun overwriteStandingCharges(newCharges: List<StandingChargeEntity>) {
        _standingCharges.value = newCharges
    }

    /**
     * Clear consumption and standing charge caches. Called when the user
     * adds credentials (demo → real) so no stale demo data lingers.
     * Agile prices are cleared separately via [AgilePriceCacheStore.clear].
     */
    fun clearAll() {
        _consumption.value = emptyList()
        _standingCharges.value = emptyList()
    }
}
