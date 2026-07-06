package com.chymaster.octopusagiledashboard.data.local

import com.chymaster.octopusagiledashboard.core.util.DemoDataGenerator
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for the demo data path. Mirrors the contract that
 * [com.chymaster.octopusagiledashboard.data.repository.OctopusRepositoryImpl]
 * provides for the real path: callers observe a [StateFlow] of entities, and
 * refresh calls replace the cached list atomically. The repository's
 * `observe*` / `refresh*` methods branch on `hasCredentials` and route
 * demo traffic through this store, so the downstream pipeline (combine,
 * debounce, distinctUntilChanged) is identical for demo and real data.
 *
 * The store is process-scoped. It is seeded by [seedPrices] / [seedConsumption] /
 * [seedStandingCharge] before the first observation in a screen, so the first
 * emission is a fully populated list — no flicker. The public Agile prices
 * refresh overwrites the seeded prices with real values via [overwritePrices],
 * which causes a single coherent re-emission.
 */
@Singleton
class DemoCacheStore @Inject constructor() {

    private val _prices = MutableStateFlow<List<AgilePriceEntity>>(emptyList())
    val prices: StateFlow<List<AgilePriceEntity>> = _prices.asStateFlow()

    private val _consumption = MutableStateFlow<List<ConsumptionEntity>>(emptyList())
    val consumption: StateFlow<List<ConsumptionEntity>> = _consumption.asStateFlow()

    private val _standingCharges = MutableStateFlow<List<StandingChargeEntity>>(emptyList())
    val standingCharges: StateFlow<List<StandingChargeEntity>> = _standingCharges.asStateFlow()

    /**
     * Populate the prices cache with synthetic data for [start] to [end].
     * Replaces any previously cached list. Called by the ViewModel before
     * the first observation so the first emission is non-empty.
     */
    fun seedPrices(start: Instant, end: Instant) {
        _prices.value = DemoDataGenerator.generateAgilePriceEntities(start, end)
    }

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
     * Replace the prices cache with [newPrices]. Used by the public Agile
     * prices refresh in demo mode: the call hits the same unauthenticated
     * endpoint as the real path, then writes the result here.
     */
    fun overwritePrices(newPrices: List<AgilePriceEntity>) {
        _prices.value = newPrices
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
     * Clear all caches. Called when the user adds credentials (demo → real)
     * so no stale demo data lingers, and as a defensive reset on real → demo
     * before the new range is seeded.
     */
    fun clearAll() {
        _prices.value = emptyList()
        _consumption.value = emptyList()
        _standingCharges.value = emptyList()
    }
}
