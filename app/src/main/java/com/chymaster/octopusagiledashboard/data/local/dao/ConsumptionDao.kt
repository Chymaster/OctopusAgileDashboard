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

    /** One-shot query for loading data into the in-memory cache. */
    @Query("SELECT * FROM consumption WHERE mpan = :mpan AND intervalStart >= :startMillis AND intervalStart < :endMillis ORDER BY intervalStart ASC")
    suspend fun queryRange(mpan: String, startMillis: Long, endMillis: Long): List<ConsumptionEntity>

    @Query("SELECT COUNT(*) FROM consumption WHERE mpan = :mpan AND intervalStart >= :startMillis AND intervalStart < :endMillis")
    suspend fun countInRange(mpan: String, startMillis: Long, endMillis: Long): Int

    @Query("DELETE FROM consumption WHERE mpan = :mpan AND intervalStart >= :startMillis AND intervalStart < :endMillis")
    suspend fun deleteRange(mpan: String, startMillis: Long, endMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ConsumptionEntity>)

    @Query("DELETE FROM consumption WHERE intervalEnd < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM consumption")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM consumption")
    suspend fun totalCount(): Int

    @Query("SELECT MIN(intervalStart) FROM consumption")
    suspend fun earliestTimestamp(): Long?

    @Query("SELECT MAX(intervalStart) FROM consumption")
    suspend fun latestTimestamp(): Long?
}
