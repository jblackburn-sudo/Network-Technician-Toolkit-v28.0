package com.example.networktoolkit

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubnetCalculatorUiState(
    val ipInput: String = "192.168.1.1",
    val prefix: Int = 24,
    val result: SubnetResult? = null,
    val errorMessage: String? = null
)

class SubnetCalculatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SubnetCalculatorUiState())
    val uiState: StateFlow<SubnetCalculatorUiState> = _uiState.asStateFlow()

    init {
        recalculate("192.168.1.1", 24)
    }

    fun onIpChanged(newIp: String) {
        recalculate(newIp, _uiState.value.prefix)
    }

    fun onPrefixChanged(newPrefix: Int) {
        val clamped = newPrefix.coerceIn(0, 32)
        recalculate(_uiState.value.ipInput, clamped)
    }

    fun applyPreset(ip: String, prefix: Int) {
        recalculate(ip, prefix)
    }

    private fun recalculate(ip: String, prefix: Int) {
        val res = SubnetCalculatorEngine.calculate(ip, prefix)
        if (res.isSuccess) {
            _uiState.value = SubnetCalculatorUiState(
                ipInput = ip,
                prefix = prefix,
                result = res.getOrNull(),
                errorMessage = null
            )
        } else {
            _uiState.value = SubnetCalculatorUiState(
                ipInput = ip,
                prefix = prefix,
                result = null,
                errorMessage = res.exceptionOrNull()?.message ?: "Invalid IPv4 address"
            )
        }
    }
}
