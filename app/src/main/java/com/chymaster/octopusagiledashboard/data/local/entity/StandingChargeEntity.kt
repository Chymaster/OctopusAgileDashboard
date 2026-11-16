package com.chymaster.octopusagiledashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One standing charge for one tariff. The key is `(validFrom, tariffCode)` so
 * charges for several tariffs (a user's history) can be cached side by side.
 */
@Entity(
    tableName = "standing_charges",
    primaryKeys = ["validFrom", "tariffCode"],
    indices = [
        Index(value = ["validFrom", "validTo"]),
        Index(value = ["tariffCode"])
    ]
)
data class StandingChargeEntity(
    val validFrom: Long,  // epoch millis
    val validTo: Long,
    val valueExcVat: Double,  // pence per day
    val valueIncVat: Double,  // pence per day
    val tariffCode: String
)
