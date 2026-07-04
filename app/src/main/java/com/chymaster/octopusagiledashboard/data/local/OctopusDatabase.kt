package com.chymaster.octopusagiledashboard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity

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
