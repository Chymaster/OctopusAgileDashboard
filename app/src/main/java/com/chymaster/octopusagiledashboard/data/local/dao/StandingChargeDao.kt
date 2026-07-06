package com.chymaster.octopusagiledashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StandingChargeDao {

    @Query("SELECT * FROM standing_charges WHERE validFrom <= :endMillis AND validTo >= :startMillis ORDER BY validFrom ASC")
    fun observeRange(startMillis: Long, endMillis: Long): Flow<List<StandingChargeEntity>>

    /** One-shot query for loading data into the in-memory cache. */
    @Query("SELECT * FROM standing_charges WHERE validFrom <= :endMillis AND validTo >= :startMillis ORDER BY validFrom ASC")
    suspend fun queryRange(startMillis: Long, endMillis: Long): List<StandingChargeEntity>

    @Query("SELECT * FROM standing_charges ORDER BY validFrom DESC LIMIT 1")
    suspend fun getCurrent(): StandingChargeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<StandingChargeEntity>)

    @Query("DELETE FROM standing_charges")
    suspend fun deleteAll()
}
