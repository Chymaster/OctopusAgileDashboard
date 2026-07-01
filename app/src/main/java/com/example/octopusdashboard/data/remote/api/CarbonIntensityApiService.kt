package com.example.octopusdashboard.data.remote.api

import com.example.octopusdashboard.data.remote.dto.CarbonIntensityResponse
import retrofit2.Response
import retrofit2.http.GET

interface CarbonIntensityApiService {

    @GET("generation")
    suspend fun getGenerationMix(): Response<CarbonIntensityResponse>
}
