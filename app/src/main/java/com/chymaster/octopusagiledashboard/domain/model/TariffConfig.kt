package com.chymaster.octopusagiledashboard.domain.model

data class TariffConfig(
    val productCode: String,  // e.g. "AGILE-24-10-01"
    val tariffCode: String,   // e.g. "E-1R-AGILE-24-10-01-_A"
    val gsp: String           // e.g. "_A"
)
