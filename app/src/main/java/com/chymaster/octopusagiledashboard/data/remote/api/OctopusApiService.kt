package com.chymaster.octopusagiledashboard.data.remote.api

import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.data.remote.dto.ConsumptionDto
import com.chymaster.octopusagiledashboard.data.remote.dto.MeterPointDto
import com.chymaster.octopusagiledashboard.data.remote.dto.PaginatedResponse
import com.chymaster.octopusagiledashboard.data.remote.dto.StandingChargeDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface OctopusApiService {

    @GET("products/{product}/electricity-tariffs/{tariff}/standard-unit-rates/")
    suspend fun getAgileRates(
        @Path("product") product: String,
        @Path("tariff") tariff: String,
        @Query("period_from") periodFrom: String,
        @Query("period_to") periodTo: String
    ): Response<PaginatedResponse<AgileRateDto>>

    @GET
    suspend fun getAgileRatesByUrl(
        @Url url: String
    ): Response<PaginatedResponse<AgileRateDto>>

    @GET("products/{product}/electricity-tariffs/{tariff}/standing-charges/")
    suspend fun getStandingCharges(
        @Path("product") product: String,
        @Path("tariff") tariff: String,
        @Query("period_from") periodFrom: String? = null,
        @Query("period_to") periodTo: String? = null
    ): Response<PaginatedResponse<StandingChargeDto>>

    @GET
    suspend fun getStandingChargesByUrl(
        @Url url: String
    ): Response<PaginatedResponse<StandingChargeDto>>

    @GET("electricity-meter-points/{mpan}/")
    suspend fun getMeterPoint(
        @Path("mpan") mpan: String
    ): Response<MeterPointDto>

    @GET("electricity-meter-points/{mpan}/meters/{serial}/consumption/")
    suspend fun getConsumption(
        @Path("mpan") mpan: String,
        @Path("serial") serial: String,
        @Query("period_from") periodFrom: String,
        @Query("period_to") periodTo: String,
        @Query("page_size") pageSize: Int = 25000,
        @Query("order_by") orderBy: String = "period"
    ): Response<PaginatedResponse<ConsumptionDto>>

    @GET
    suspend fun getConsumptionByUrl(
        @Url url: String
    ): Response<PaginatedResponse<ConsumptionDto>>
}
