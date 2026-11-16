package com.chymaster.octopusagiledashboard.core.util

import java.util.Locale

/** User-facing names for Octopus product codes. */
object TariffNames {

    private val knownTariffs by lazy {
        (Constants.COMMON_TARIFFS + Constants.HISTORICAL_TARIFFS)
            .associateBy { it.id.uppercase(Locale.ROOT) }
    }

    /**
     * Curated display name for [productCode] when the app knows it, otherwise
     * a title-cased version of the code (`AGILE-24-10-01` → `Agile 24 10 01`).
     */
    fun displayName(productCode: String): String {
        if (productCode.isBlank()) return ""
        knownTariffs[productCode.uppercase(Locale.ROOT)]?.let { return it.displayName }
        return productCode
            .split('-', '_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                val lower = token.lowercase(Locale.ROOT)
                lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            .ifBlank { productCode }
    }

    /** `["AGILE-24-10-01", "COSY-22-12-08"]` → `"Agile 24 10 01 → Cosy 22 12 08"`. */
    fun joinedDisplayName(productCodes: List<String>, separator: String = " → "): String =
        productCodes
            .map { displayName(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator)
}
