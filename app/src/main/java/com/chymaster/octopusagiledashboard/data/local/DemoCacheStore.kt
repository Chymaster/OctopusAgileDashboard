package com.chymaster.octopusagiledashboard.data.local

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for demo-mode data.
 *
 * This class previously held consumption and standing charge data for demo mode.
 * Consumption has been migrated to [ConsumptionCacheStore] and standing charges
 * to [StandingChargeCacheStore]. This class is retained as a no-op placeholder
 * until all references are fully removed.
 */
@Singleton
class DemoCacheStore @Inject constructor() {

    /** No-op. Consumption is now handled by [ConsumptionCacheStore]. */
    fun clearAll() {
        // Intentionally empty — no data to clear.
    }
}
