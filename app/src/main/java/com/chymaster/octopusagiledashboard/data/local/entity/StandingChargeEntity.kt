package com.chymaster.octopusagiledashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "standing_charges",
    indices = [Index(value = ["validFrom", "validTo"])]
)
data class StandingChargeEntity(
    @PrimaryKey val validFrom: Long,  // epoch millis
    val validTo: Long,
    val valueExcVat: Double,  // pence per day
    val valueIncVat: Double,  // pence per day
    val tariffCode: String
)
