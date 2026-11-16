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

    /** One-shot query for loading data into the in-memory cache (all tariffs). */
    @Query("SELECT * FROM standing_charges WHERE validFrom <= :endMillis AND validTo >= :startMillis ORDER BY validFrom ASC")
    suspend fun queryRange(startMillis: Long, endMillis: Long): List<StandingChargeEntity>

    /** Charges for any of [tariffCodes] overlapping the range. */
    @Query(
        "SELECT * FROM standing_charges WHERE tariffCode IN (:tariffCodes) " +
            "AND validFrom <= :endMillis AND validTo >= :startMillis ORDER BY validFrom ASC"
    )
    suspend fun queryRangeForTariffs(
        tariffCodes: List<String>,
        startMillis: Long,
        endMillis: Long
    ): List<StandingChargeEntity>

    /** Charges for one tariff that start on or before [endMillis]. */
    @Query(
        "SELECT * FROM standing_charges WHERE tariffCode = :tariffCode " +
            "AND validFrom <= :endMillis ORDER BY validFrom ASC"
    )
    suspend fun queryUpToForTariff(
        tariffCode: String,
        endMillis: Long
    ): List<StandingChargeEntity>

    @Query("SELECT COUNT(*) FROM standing_charges WHERE tariffCode = :tariffCode AND validFrom <= :endMillis")
    suspend fun countUpToForTariff(tariffCode: String, endMillis: Long): Int

    @Query("SELECT * FROM standing_charges ORDER BY validFrom DESC LIMIT 1")
    suspend fun getCurrent(): StandingChargeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<StandingChargeEntity>)

    @Query("DELETE FROM standing_charges WHERE tariffCode = :tariffCode")
    suspend fun deleteForTariff(tariffCode: String)

    @Query("DELETE FROM standing_charges")
    suspend fun deleteAll()
}
