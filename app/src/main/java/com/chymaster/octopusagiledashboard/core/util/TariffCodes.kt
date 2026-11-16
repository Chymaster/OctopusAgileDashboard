package com.chymaster.octopusagiledashboard.core.util

import java.util.Locale

/**
 * A parsed Octopus electricity tariff code.
 *
 * Tariff codes look like `E-1R-AGILE-24-10-01-L`:
 * `E` = electricity, `1` = single register, `R` = import (`E` = export),
 * then the product code, then the GSP group letter. Product codes contain
 * hyphens of their own (`AGILE-OUTGOING-19-05-13`, `OE-FIX-12M-25-09-09`),
 * so the product is everything between the register token and the last
 * hyphen — never `split("-")[2]`.
 */
data class ParsedTariffCode(
    val tariffCode: String,
    /** e.g. `AGILE-24-10-01` */
    val productCode: String,
    /** GSP group letter without the leading underscore, e.g. `L`. */
    val gsp: String,
    /** True for export (`E-1E-…`) tariffs. */
    val isExport: Boolean
)

object TariffCodes {

    /**
     * `E-<registers><direction>-<product>-<gsp>`.
     * GSP group letters skip I and O (A–H, J–N, P), but we accept any letter
     * and let callers validate membership so an unexpected code still parses.
     */
    private val PATTERN = Regex("""^E-\d+([RE])-(.+)-([A-Z])$""")

    fun parse(tariffCode: String): ParsedTariffCode? {
        val trimmed = tariffCode.trim().uppercase(Locale.ROOT)
        val match = PATTERN.matchEntire(trimmed) ?: return null
        val direction = match.groupValues[1]
        val product = match.groupValues[2]
        val gsp = match.groupValues[3]
        if (product.isBlank()) return null
        return ParsedTariffCode(
            tariffCode = trimmed,
            productCode = product,
            gsp = gsp,
            isExport = direction == "E"
        )
    }

    /** Builds `E-1R-<product>-<gsp>` (import, single register). */
    fun build(productCode: String, gsp: String): String {
        val product = productCode.trim().uppercase(Locale.ROOT)
        val region = normalizeGsp(gsp).uppercase(Locale.ROOT)
        return "E-1R-$product-$region"
    }

    /** `_L` / `L` → `L`. */
    fun normalizeGsp(gsp: String): String = gsp.trim().removePrefix("_")

    /** `L` / `_L` → `_L` (the form the settings UI and API DTOs use). */
    fun withUnderscoreGsp(gsp: String): String {
        val bare = normalizeGsp(gsp)
        return if (bare.isEmpty()) "" else "_$bare"
    }

    /** Product code of a tariff code, or the raw code when it cannot be parsed. */
    fun productCodeOf(tariffCode: String): String =
        parse(tariffCode)?.productCode ?: tariffCode
}
