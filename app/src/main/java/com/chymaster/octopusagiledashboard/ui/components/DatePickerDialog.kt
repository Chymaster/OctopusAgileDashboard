package com.chymaster.octopusagiledashboard.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDatePickerDialog(
    onRangeSelected: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                return utcTimeMillis <= System.currentTimeMillis()
            }

            override fun isSelectableYear(year: Int): Boolean {
                return year <= LocalDate.now().year
            }
        }
    )

    val confirmEnabled by remember {
        derivedStateOf {
            pickerState.selectedStartDateMillis != null &&
                pickerState.selectedEndDateMillis != null
        }
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val startMillis = pickerState.selectedStartDateMillis
                    val endMillis = pickerState.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        val startDate = Instant.ofEpochMilli(startMillis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        val endDate = Instant.ofEpochMilli(endMillis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        onRangeSelected(startDate, endDate)
                    }
                    onDismiss()
                },
                enabled = confirmEnabled
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.padding(top = 8.dp),
            title = {
                Text(
                    text = "Select date range",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp)
                )
            },
            headline = {
                val startDate = pickerState.selectedStartDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                }
                val endDate = pickerState.selectedEndDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                }
                Text(
                    text = if (startDate != null && endDate != null) {
                        "$startDate – $endDate"
                    } else {
                        "Tap to select start and end dates"
                    },
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                )
            }
        )
    }
}
