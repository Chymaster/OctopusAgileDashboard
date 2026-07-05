package com.chymaster.octopusagiledashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConsumptionDao {

    @Query("SELECT * FROM consumption WHERE mpan = :mpan AND intervalStart >= :startMillis AND intervalStart < :endMillis ORDER BY intervalStart ASC")
    fun observeRange(mpan: String, startMillis: Long, endMillis: Long): Flow<List<ConsumptionEntity>>

    @Query("DELETE FROM consumption WHERE mpan = :mpan AND intervalStart >= :startMillis AND intervalStart < :endMillis")
    suspend fun deleteRange(mpan: String, startMillis: Long, endMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ConsumptionEntity>)

    @Query("DELETE FROM consumption WHERE intervalEnd < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM consumption")
    suspend fun deleteAll()
}
