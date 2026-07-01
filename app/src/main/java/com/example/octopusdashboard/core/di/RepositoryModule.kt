package com.example.octopusdashboard.core.di

import com.example.octopusdashboard.data.repository.GreenEnergyRepository
import com.example.octopusdashboard.data.repository.GreenEnergyRepositoryImpl
import com.example.octopusdashboard.data.repository.OctopusRepository
import com.example.octopusdashboard.data.repository.OctopusRepositoryImpl
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
