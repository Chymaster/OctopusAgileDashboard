package com.chymaster.octopusagiledashboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.TariffSource

/**
 * Compact chip showing the tariff in force right now.
 *
 * The suffix hints where that tariff came from (`auto` / `manual` / `demo`),
 * and a fallback (detection failed) is tinted as a warning.
 */
@Composable
fun TariffChip(
    name: String?,
    source: TariffSource?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    if (name.isNullOrBlank()) return
    val isFallback = source == TariffSource.FALLBACK
    val container = if (isFallback) {
        SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            iconContentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    } else {
        SuggestionChipDefaults.suggestionChipColors()
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SuggestionChip(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            colors = container,
            icon = {
                Icon(
                    imageVector = if (isFallback) Icons.Default.Warning else Icons.Default.Bolt,
                    contentDescription = null
                )
            },
            label = {
                Text(
                    text = name + when (source) {
                        TariffSource.MANUAL_OVERRIDE -> " · manual"
                        TariffSource.DEMO -> " · demo"
                        TariffSource.FALLBACK -> " · not detected"
                        else -> " · auto"
                    }
                )
            }
        )
    }
}

/**
 * Caption listing every tariff in force over the selected range, e.g.
 * `Tariffs in range: Agile Octopus → Cosy Octopus`. Hidden when the range has
 * a single tariff.
 */
@Composable
fun TariffRangeCaption(
    rangeTariffNames: List<String>,
    modifier: Modifier = Modifier
) {
    if (rangeTariffNames.size < 2) return
    Text(
        text = "Tariffs in range: " + rangeTariffNames.joinToString(" → "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}
