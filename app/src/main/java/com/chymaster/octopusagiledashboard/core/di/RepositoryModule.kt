package com.chymaster.octopusagiledashboard.core.di

import com.chymaster.octopusagiledashboard.data.repository.GreenEnergyRepository
import com.chymaster.octopusagiledashboard.data.repository.GreenEnergyRepositoryImpl
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindOctopusRepository(
        impl: OctopusRepositoryImpl
    ): OctopusRepository

    @Binds
    @Singleton
    abstract fun bindGreenEnergyRepository(
        impl: GreenEnergyRepositoryImpl
    ): GreenEnergyRepository
}
