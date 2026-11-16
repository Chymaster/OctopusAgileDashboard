package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import java.time.Instant

/**
 * Turns published unit rates into one price per half-hour slot.
 *
 * Rate lookup uses "the greatest `validFrom` ≤ slot" for the tariff active in
 * that slot, deliberately ignoring `validTo`: Agile publishes a rate per slot,
 * while Flexible/Cosy-style tariffs publish a daily (or longer) rate whose
 * `valid_to` the API may omit — the mapper synthesises a 30-minute end for
 * those, so a `validTo` window would mis-handle them. Cross-tariff leakage is
 * prevented by requiring the row's `tariffCode` to match the slot's tariff.
 */
object SlotRateSelector {

    private const val SLOT_MILLIS = TariffScheduleResolver.SLOT_MILLIS

    /**
     * Single-tariff variant: maps every slot start in `[start, end)` to the
     * rate in force then. Used for a comparison tariff, which is a single
     * product for the whole range.
     */
    fun buildSlotPriceMap(
        rates: List<AgilePrice>,
        start: Instant,
        end: Instant,
        slotMillis: Long = SLOT_MILLIS
    ): Map<Long, Double> {
        if (rates.isEmpty() || !start.isBefore(end)) return emptyMap()
        val sorted = rates.sortedBy { it.validFrom }
        val map = HashMap<Long, Double>()
        var index = -1
        var t = start
        while (t.isBefore(end)) {
            while (index + 1 < sorted.size && !sorted[index + 1].validFrom.isAfter(t)) index++
            if (index >= 0) map[t.toEpochMilli()] = sorted[index].priceIncVat
            t = t.plusMillis(slotMillis)
        }
        return map
    }

    /** Schedule-aware variant of [buildSlotPriceMap]. */
    fun buildScheduleSlotPriceMap(
        rates: List<AgilePrice>,
        slotTariffs: Map<Long, String>,
        slotMillis: Long = SLOT_MILLIS
    ): Map<Long, Double> =
        selectSlotRates(rates, slotTariffs, slotMillis)
            .associate { it.validFrom.toEpochMilli() to it.priceIncVat }

    /**
     * Emits exactly one [AgilePrice] per slot in [slotTariffs], carrying the
     * tariff code that produced it. Slots whose tariff has no published rate at
     * or before the slot are omitted — the neighbouring tariff is never
     * borrowed to fill them.
     */
    fun selectSlotRates(
        rates: List<AgilePrice>,
        slotTariffs: Map<Long, String>,
        slotMillis: Long = SLOT_MILLIS
    ): List<AgilePrice> {
        if (rates.isEmpty() || slotTariffs.isEmpty()) return emptyList()

        val sortedByTariff: Map<String, List<AgilePrice>> = rates
            .filter { !it.tariffCode.isNullOrBlank() }
            .groupBy { it.tariffCode!! }
            .mapValues { (_, list) -> list.sortedBy { it.validFrom } }

        val cursors = HashMap<String, Int>()
        val out = ArrayList<AgilePrice>(slotTariffs.size)
        // Slots are processed in ascending order, so each tariff's cursor only
        // moves forward — one linear pass per tariff instead of a scan per slot.
        for ((slotMillisValue, tariffCode) in slotTariffs.entries.sortedBy { it.key }) {
            val list = sortedByTariff[tariffCode] ?: continue
            val slot = Instant.ofEpochMilli(slotMillisValue)
            var index = cursors[tariffCode] ?: -1
            while (index + 1 < list.size && !list[index + 1].validFrom.isAfter(slot)) index++
            cursors[tariffCode] = index
            if (index < 0) continue
            val rate = list[index]
            out.add(
                AgilePrice(
                    validFrom = slot,
                    validTo = slot.plusMillis(slotMillis),
                    priceExcVat = rate.priceExcVat,
                    priceIncVat = rate.priceIncVat,
                    tariffCode = tariffCode
                )
            )
        }
        return out
    }
}
