package com.chymaster.octopusagiledashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgilePriceDao {

    @Query("SELECT * FROM agile_prices WHERE validFrom >= :startMillis AND validTo <= :endMillis ORDER BY validFrom ASC")
    fun observeRange(startMillis: Long, endMillis: Long): Flow<List<AgilePriceEntity>>

    /** One-shot query for loading data into the in-memory cache. */
    @Query("SELECT * FROM agile_prices WHERE validFrom >= :startMillis AND validTo <= :endMillis ORDER BY validFrom ASC")
    suspend fun queryRange(startMillis: Long, endMillis: Long): List<AgilePriceEntity>

    @Query("DELETE FROM agile_prices WHERE validFrom >= :startMillis AND validTo <= :endMillis")
    suspend fun deleteRange(startMillis: Long, endMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AgilePriceEntity>)

    @Query("DELETE FROM agile_prices WHERE validTo < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM agile_prices WHERE validFrom >= :startMillis AND validTo <= :endMillis")
    suspend fun countInRange(startMillis: Long, endMillis: Long): Int

    @Query("DELETE FROM agile_prices")
    suspend fun deleteAll()
}
