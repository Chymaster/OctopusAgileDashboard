package com.example.octopusdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agile_prices",
    indices = [Index(value = ["validFrom", "validTo"])]
)
data class AgilePriceEntity(
    @PrimaryKey val validFrom: Long,  // epoch millis
    val validTo: Long,
    val priceExcVat: Double,
    val priceIncVat: Double,
    val tariffCode: String
)
