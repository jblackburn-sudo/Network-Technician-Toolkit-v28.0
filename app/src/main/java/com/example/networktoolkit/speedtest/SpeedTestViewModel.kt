package com.example.networktoolkit.speedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SpeedTestUiState {
    data object Idle : SpeedTestUiState()

    data class Running(
        val phase: SpeedTestEngine.TestPhase,
        val progress: Float,
        val currentMbps: Double,
        val latencyMs: Double,
        val jitterMs: Double,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val totalBytes: Long
    ) : SpeedTestUiState()

    data class Completed(val result: SpeedTestEngine.SpeedTestResult) : SpeedTestUiState()

    data class Error(val message: String) : SpeedTestUiState()
}

/**
 * ViewModel managing the native internet speed test lifecycle.
 */
class SpeedTestViewModel internal constructor(
    engineFactory: ((CoroutineScope) -> SpeedTestEngine)? = null
) : ViewModel() {

    constructor() : this(null)

    private val engine: SpeedTestEngine = engineFactory?.invoke(viewModelScope) ?: SpeedTestEngine(viewModelScope)

    private val _uiState = MutableStateFlow<SpeedTestUiState>(SpeedTestUiState.Idle)
    val uiState: StateFlow<SpeedTestUiState> = _uiState.asStateFlow()

    private var activeSessionToken = 0L
    private var currentPhase = SpeedTestEngine.TestPhase.IDLE
    private var currentProgress = 0f
    private var currentSpeed = 0.0
    private var currentLatency = -1.0
    private var currentJitter = -1.0
    private var currentDownload = -1.0
    private var currentUpload = -1.0
    private var currentBytes = 0L

    fun startSpeedTest() {
        if (engine.isRunning) return

        val sessionToken = ++activeSessionToken
        currentPhase = SpeedTestEngine.TestPhase.LATENCY_JITTER
        currentProgress = 0f
        currentSpeed = 0.0
        currentLatency = -1.0
        currentJitter = -1.0
        currentDownload = -1.0
        currentUpload = -1.0
        currentBytes = 0L

        _uiState.value = SpeedTestUiState.Running(
            phase = currentPhase,
            progress = 0f,
            currentMbps = 0.0,
            latencyMs = -1.0,
            jitterMs = -1.0,
            downloadMbps = -1.0,
            uploadMbps = -1.0,
            totalBytes = 0L
        )

        engine.startTest(object : SpeedTestEngine.Callback {
            override fun onPhaseChanged(phase: SpeedTestEngine.TestPhase) {
                if (activeSessionToken != sessionToken) return
                currentPhase = phase
                if (phase == SpeedTestEngine.TestPhase.DOWNLOAD || phase == SpeedTestEngine.TestPhase.UPLOAD) {
                    currentSpeed = 0.0
                    currentProgress = 0f
                }
                updateRunningState(sessionToken)
            }

            override fun onLatencyMeasured(latencyMs: Double, jitterMs: Double) {
                if (activeSessionToken != sessionToken) return
                currentLatency = latencyMs
                currentJitter = jitterMs
                updateRunningState(sessionToken)
            }

            override fun onDownloadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {
                if (activeSessionToken != sessionToken) return
                currentSpeed = if (currentMbps >= 0.0) currentMbps else 0.0
                currentDownload = currentMbps
                currentProgress = progress
                currentBytes = totalBytes
                updateRunningState(sessionToken)
            }

            override fun onUploadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {
                if (activeSessionToken != sessionToken) return
                currentSpeed = if (currentMbps >= 0.0) currentMbps else 0.0
                currentUpload = currentMbps
                currentProgress = progress
                currentBytes = totalBytes
                updateRunningState(sessionToken)
            }

            override fun onCompleted(result: SpeedTestEngine.SpeedTestResult) {
                if (activeSessionToken != sessionToken) return
                _uiState.value = SpeedTestUiState.Completed(result)
            }

            override fun onError(errorMessage: String) {
                if (activeSessionToken != sessionToken) return
                _uiState.value = SpeedTestUiState.Error(errorMessage)
            }

            override fun onCancelled() {
                if (activeSessionToken != sessionToken) return
                _uiState.value = SpeedTestUiState.Idle
            }
        })
    }

    private fun updateRunningState(sessionToken: Long) {
        if (activeSessionToken != sessionToken) return
        _uiState.value = SpeedTestUiState.Running(
            phase = currentPhase,
            progress = currentProgress,
            currentMbps = currentSpeed,
            latencyMs = currentLatency,
            jitterMs = currentJitter,
            downloadMbps = currentDownload,
            uploadMbps = currentUpload,
            totalBytes = currentBytes
        )
    }

    fun stopSpeedTest() {
        activeSessionToken++
        engine.stop()
        _uiState.value = SpeedTestUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        activeSessionToken++
        engine.stop()
    }
}
