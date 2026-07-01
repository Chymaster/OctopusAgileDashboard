package com.example.octopusdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "consumption",
    primaryKeys = ["intervalStart", "mpan"],
    indices = [Index(value = ["intervalStart"])]
)
data class ConsumptionEntity(
    val intervalStart: Long,  // epoch millis
    val intervalEnd: Long,
    val consumption: Double,  // kWh
    val mpan: String,
    val serialNumber: String
)
