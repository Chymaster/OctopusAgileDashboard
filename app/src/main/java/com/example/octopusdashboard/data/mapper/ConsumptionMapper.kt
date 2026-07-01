package com.example.octopusdashboard.data.mapper

import com.example.octopusdashboard.data.local.entity.ConsumptionEntity
import com.example.octopusdashboard.data.remote.dto.ConsumptionDto
import com.example.octopusdashboard.domain.model.ConsumptionRecord
import java.time.Instant
import java.time.OffsetDateTime

fun ConsumptionDto.toEntity(mpan: String, serialNumber: String): ConsumptionEntity {
    return ConsumptionEntity(
        intervalStart = OffsetDateTime.parse(intervalStart).toInstant().toEpochMilli(),
        intervalEnd = OffsetDateTime.parse(intervalEnd).toInstant().toEpochMilli(),
        consumption = consumption,
        mpan = mpan,
        serialNumber = serialNumber
    )
}

fun ConsumptionEntity.toDomain(): ConsumptionRecord {
    return ConsumptionRecord(
        intervalStart = Instant.ofEpochMilli(intervalStart),
        intervalEnd = Instant.ofEpochMilli(intervalEnd),
        consumption = consumption
    )
}
