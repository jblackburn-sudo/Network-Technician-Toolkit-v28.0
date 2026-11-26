package com.example.networktoolkit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class LanScannerUiState {
    data class Idle(val detectedSubnet: SubnetInfo?) : LanScannerUiState()
    data class Scanning(
        val subnetInfo: SubnetInfo,
        val scannedCount: Int,
        val totalHosts: Int,
        val devices: List<DiscoveredDevice>
    ) : LanScannerUiState()
    data class Completed(
        val subnetInfo: SubnetInfo,
        val devices: List<DiscoveredDevice>,
        val durationMs: Long
    ) : LanScannerUiState()
    data class Error(val message: String) : LanScannerUiState()
}

/**
 * ViewModel managing the LAN subnet sweep state and active devices list.
 */
class LanScannerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val engine = LanScannerEngine(application)

    private val _uiState = MutableStateFlow<LanScannerUiState>(
        LanScannerUiState.Idle(engine.detectSubnetInfo())
    )
    val uiState: StateFlow<LanScannerUiState> = _uiState.asStateFlow()

    private val discoveredDevices = mutableListOf<DiscoveredDevice>()

    fun refreshSubnet() {
        val detected = engine.detectSubnetInfo()
        _uiState.value = LanScannerUiState.Idle(detected)
    }

    fun startScan() {
        discoveredDevices.clear()
        engine.startScan(null, object : LanScannerEngine.Callback {
            override fun onSubnetDetected(subnetInfo: SubnetInfo) {
                _uiState.value = LanScannerUiState.Scanning(
                    subnetInfo = subnetInfo,
                    scannedCount = 0,
                    totalHosts = subnetInfo.totalHosts,
                    devices = emptyList()
                )
            }

            override fun onDeviceFound(device: DiscoveredDevice) {
                discoveredDevices.add(device)
                val current = _uiState.value
                if (current is LanScannerUiState.Scanning) {
                    _uiState.value = current.copy(devices = discoveredDevices.toList())
                }
            }

            override fun onProgress(scanned: Int, total: Int) {
                val current = _uiState.value
                if (current is LanScannerUiState.Scanning) {
                    _uiState.value = current.copy(scannedCount = scanned)
                }
            }

            override fun onCompleted(devices: List<DiscoveredDevice>, durationMs: Long) {
                val current = _uiState.value
                val subnet = if (current is LanScannerUiState.Scanning) {
                    current.subnetInfo
                } else {
                    engine.detectSubnetInfo() ?: return
                }
                _uiState.value = LanScannerUiState.Completed(
                    subnetInfo = subnet,
                    devices = devices,
                    durationMs = durationMs
                )
            }

            override fun onError(message: String) {
                _uiState.value = LanScannerUiState.Error(message)
            }
        })
    }

    fun stopScan() {
        engine.stopScan()
    }

    override fun onCleared() {
        super.onCleared()
        engine.stopScan()
    }
}
