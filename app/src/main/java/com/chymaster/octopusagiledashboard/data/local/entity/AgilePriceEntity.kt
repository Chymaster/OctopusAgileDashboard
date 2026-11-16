package com.chymaster.octopusagiledashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One unit rate for one tariff. The key is `(validFrom, tariffCode)` so rates
 * for several tariffs (a user's history) can be cached side by side.
 */
@Entity(
    tableName = "agile_prices",
    primaryKeys = ["validFrom", "tariffCode"],
    indices = [
        Index(value = ["validFrom", "validTo"]),
        Index(value = ["tariffCode"])
    ]
)
data class AgilePriceEntity(
    val validFrom: Long,  // epoch millis
    val validTo: Long,
    val priceExcVat: Double,
    val priceIncVat: Double,
    val tariffCode: String
)
