package com.chymaster.octopusagiledashboard.core.di

import android.content.Context
import androidx.room.Room
import com.chymaster.octopusagiledashboard.data.local.OctopusDatabase
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OctopusDatabase {
        return Room.databaseBuilder(
            context,
            OctopusDatabase::class.java,
            "octopus_database"
        ).build()
    }

    @Provides
    fun provideAgilePriceDao(database: OctopusDatabase): AgilePriceDao {
        return database.agilePriceDao()
    }

    @Provides
    fun provideConsumptionDao(database: OctopusDatabase): ConsumptionDao {
        return database.consumptionDao()
    }
}
