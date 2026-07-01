package com.example.octopusdashboard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.octopusdashboard.data.local.dao.AgilePriceDao
import com.example.octopusdashboard.data.local.dao.ConsumptionDao
import com.example.octopusdashboard.data.local.entity.AgilePriceEntity
import com.example.octopusdashboard.data.local.entity.ConsumptionEntity

@Database(
    entities = [AgilePriceEntity::class, ConsumptionEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class OctopusDatabase : RoomDatabase() {
    abstract fun agilePriceDao(): AgilePriceDao
    abstract fun consumptionDao(): ConsumptionDao
}
