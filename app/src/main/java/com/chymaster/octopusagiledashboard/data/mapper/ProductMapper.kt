package com.chymaster.octopusagiledashboard.data.mapper

import com.chymaster.octopusagiledashboard.data.remote.dto.ProductDto
import com.chymaster.octopusagiledashboard.domain.model.TariffOption

fun ProductDto.toDomain(): TariffOption {
    return TariffOption(
        id = code,
        displayName = displayName ?: fullName ?: code
    )
}
