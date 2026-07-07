package com.chymaster.octopusagiledashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.local.dao.AgilePriceDao
import com.chymaster.octopusagiledashboard.data.local.dao.ConsumptionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConsumptionStatus(
    val totalRecords: Int = 0,
    val earliestMillis: Long? = null,
    val latestMillis: Long? = null
)

data class PriceStatus(
    val totalRecords: Int = 0,
    val earliestMillis: Long? = null,
    val latestMillis: Long? = null
)

data class StatusUiState(
    val consumption: ConsumptionStatus = ConsumptionStatus(),
    val prices: PriceStatus = PriceStatus(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val consumptionDao: ConsumptionDao,
    private val agilePriceDao: AgilePriceDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        loadStatus()
    }

    fun loadStatus() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val consumptionCount = consumptionDao.totalCount()
                val consumptionEarliest = consumptionDao.earliestTimestamp()
                val consumptionLatest = consumptionDao.latestTimestamp()

                val priceCount = agilePriceDao.totalCount()
                val priceEarliest = agilePriceDao.earliestTimestamp()
                val priceLatest = agilePriceDao.latestTimestamp()

                _uiState.value = StatusUiState(
                    consumption = ConsumptionStatus(consumptionCount, consumptionEarliest, consumptionLatest),
                    prices = PriceStatus(priceCount, priceEarliest, priceLatest),
                    isLoading = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = StatusUiState(
                    isLoading = false,
                    error = "Failed to load status: ${e.message}"
                )
            }
        }
    }
}
