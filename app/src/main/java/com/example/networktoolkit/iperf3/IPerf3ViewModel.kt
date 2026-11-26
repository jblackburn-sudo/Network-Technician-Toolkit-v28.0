package com.example.networktoolkit.iperf3

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networktoolkit.NetworkConnectivityMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class IPerfMode {
    CLIENT,
    SERVER
}

sealed class IPerfUiState {
    data object Idle : IPerfUiState()
    data class Running(
        val mode: IPerfMode,
        val targetOrPort: String,
        val liveBandwidthMbps: Double = 0.0,
        val currentIntervals: List<IPerfInterval> = emptyList(),
        val statusMessage: String = "Benchmarking throughput..."
    ) : IPerfUiState()
    data class Completed(val summary: IPerfSummary) : IPerfUiState()
    data class Error(val details: IPerfErrorDetails) : IPerfUiState() {
        val message: String get() = details.summaryMessage
    }
}

class IPerf3ViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = IPerf3Engine()
    private val connectivityMonitor = NetworkConnectivityMonitor.getInstance(application)
    private var testJob: Job? = null

    private val _uiState = MutableStateFlow<IPerfUiState>(IPerfUiState.Idle)
    val uiState: StateFlow<IPerfUiState> = _uiState.asStateFlow()

    private val _mode = MutableStateFlow(IPerfMode.CLIENT)
    val mode: StateFlow<IPerfMode> = _mode.asStateFlow()

    // Intentionally empty by default: users must specify an actual iPerf3 server host/IP.
    private val _targetHost = MutableStateFlow("")
    val targetHost: StateFlow<String> = _targetHost.asStateFlow()

    private val _portInput = MutableStateFlow("5201")
    val portInput: StateFlow<String> = _portInput.asStateFlow()

    private val _port = MutableStateFlow(5201)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _durationSeconds = MutableStateFlow(10)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _connectTimeoutMs = MutableStateFlow(5000)
    val connectTimeoutMs: StateFlow<Int> = _connectTimeoutMs.asStateFlow()

    private val _parallelStreams = MutableStateFlow(1)
    val parallelStreams: StateFlow<Int> = _parallelStreams.asStateFlow()

    private val _reverseMode = MutableStateFlow(false)
    val reverseMode: StateFlow<Boolean> = _reverseMode.asStateFlow()

    private val _serverNoticeMessage = MutableStateFlow<String?>(null)
    val serverNoticeMessage: StateFlow<String?> = _serverNoticeMessage.asStateFlow()

    val localIpAddress: String?
        get() = connectivityMonitor.snapshot.value.localIp

    val activeGatewayIp: String?
        get() = connectivityMonitor.snapshot.value.gatewayIp

    fun setMode(newMode: IPerfMode) {
        if (_uiState.value !is IPerfUiState.Running) {
            _mode.value = newMode
            _serverNoticeMessage.value = null
            if (_uiState.value is IPerfUiState.Error) {
                _uiState.value = IPerfUiState.Idle
            }
        }
    }

    fun clearServerNotice() {
        _serverNoticeMessage.value = null
    }

    fun onTargetHostChanged(host: String) {
        _targetHost.value = host
    }

    fun onPortInputChanged(portText: String) {
        // Allow digits only up to 5 characters so users can clear & retype freely
        val filtered = portText.filter { it.isDigit() }.take(5)
        _portInput.value = filtered
        filtered.toIntOrNull()?.takeIf { it in 1..65535 }?.let { _port.value = it }
    }

    fun onDurationChanged(seconds: Int) {
        _durationSeconds.value = seconds.coerceIn(1, 300)
    }

    fun onConnectTimeoutChanged(timeoutMs: Int) {
        _connectTimeoutMs.value = timeoutMs.coerceIn(1000, 60000)
    }

    fun onParallelStreamsChanged(streams: Int) {
        _parallelStreams.value = streams.coerceIn(1, 8)
    }

    fun onReverseModeChanged(reverse: Boolean) {
        _reverseMode.value = reverse
    }

    fun applyPresetHost(host: String, port: Int = 5201) {
        if (_uiState.value is IPerfUiState.Running) return
        _targetHost.value = host
        _portInput.value = port.toString()
        _port.value = port
        if (_uiState.value is IPerfUiState.Error) {
            _uiState.value = IPerfUiState.Idle
        }
    }

    fun startTest() {
        if (engine.isRunning()) return
        _serverNoticeMessage.value = null

        val intervalsList = mutableListOf<IPerfInterval>()

        if (_mode.value == IPerfMode.CLIENT) {
            val rawHost = _targetHost.value
            val rawPort = _portInput.value
            val dur = _durationSeconds.value
            val timeoutMs = _connectTimeoutMs.value
            val streams = _parallelStreams.value
            val reverse = _reverseMode.value

            val validation = IPerf3Validator.validateClientConfig(
                rawHost = rawHost,
                rawPort = rawPort,
                durationSeconds = dur,
                parallelStreams = streams,
                connectTimeoutMs = timeoutMs
            )

            if (!validation.isValid) {
                _uiState.value = IPerfUiState.Error(validation.errorDetails!!)
                return
            }

            val host = validation.sanitizedHost
            val p = validation.port
            connectivityMonitor.refreshNow()
            val hasNet = connectivityMonitor.snapshot.value.isConnected

            _uiState.value = IPerfUiState.Running(
                mode = IPerfMode.CLIENT,
                targetOrPort = "$host:$p",
                statusMessage = "Connecting to iPerf3 server ($host:$p, timeout ${timeoutMs} ms)..."
            )

            testJob = viewModelScope.launch {
                val res = engine.runClient(
                    host = host,
                    port = p,
                    durationSeconds = dur,
                    parallelStreams = streams,
                    connectTimeoutMs = timeoutMs,
                    reverseMode = reverse,
                    hasActiveNetwork = hasNet,
                    onStatusUpdate = { status ->
                        val cur = _uiState.value
                        if (cur is IPerfUiState.Running) {
                            _uiState.value = cur.copy(statusMessage = status)
                        }
                    },
                    onInterval = { item ->
                        intervalsList.add(item)
                        val directionLabel = if (reverse) "Downloading from" else "Transmitting to"
                        _uiState.value = IPerfUiState.Running(
                            mode = IPerfMode.CLIENT,
                            targetOrPort = "$host:$p",
                            liveBandwidthMbps = item.bandwidthMbps,
                            currentIntervals = intervalsList.toList(),
                            statusMessage = "$directionLabel $host:$p [${item.timeRange}]"
                        )
                    }
                )

                if (res.isSuccess) {
                    _uiState.value = IPerfUiState.Completed(res.getOrThrow())
                } else {
                    val err = res.exceptionOrNull()
                    if (err is kotlinx.coroutines.CancellationException) {
                        val totalTransferred = intervalsList.sumOf { it.bytesTransferred }
                        val approxElapsedMs = (intervalsList.size.coerceAtLeast(1) * 1000L)
                        _uiState.value = IPerfUiState.Completed(
                            IPerfSummary(
                                mode = "Client",
                                direction = if (reverse) "Download (-R)" else "Upload (Send)",
                                targetOrClient = "$host:$p",
                                resolvedIp = null,
                                port = p,
                                protocol = "TCP",
                                parallelStreams = streams,
                                durationSeconds = dur,
                                connectTimeoutMs = timeoutMs,
                                totalBytes = totalTransferred,
                                avgBandwidthMbps = IPerf3TransferAnalyzer.calculateBandwidthMbps(totalTransferred, approxElapsedMs),
                                peakBandwidthMbps = intervalsList.maxOfOrNull { it.bandwidthMbps } ?: 0.0,
                                intervals = intervalsList.toList(),
                                isInterrupted = true,
                                diagnosticNote = "Benchmark interrupted by technician.",
                                terminationReason = IPerfTerminationReason.USER_CANCELLED,
                                diagnosticLogs = engine.getLastDiagnosticLogs()
                            )
                        )
                    } else {
                        val details = IPerf3ErrorClassifier.classify(
                            throwable = err ?: IllegalStateException("Client test failed"),
                            targetHost = host,
                            resolvedIp = null,
                            port = p,
                            connectTimeoutMs = timeoutMs
                        )
                        _uiState.value = IPerfUiState.Error(details)
                    }
                }
            }
        } else {
            val validation = IPerf3Validator.validateServerConfig(_portInput.value)
            if (!validation.isValid) {
                _uiState.value = IPerfUiState.Error(validation.errorDetails!!)
                return
            }

            val p = validation.port
            _uiState.value = IPerfUiState.Running(
                mode = IPerfMode.SERVER,
                targetOrPort = "Port $p",
                statusMessage = "Listening on port $p (LAN accessible • auto-stops after 2 min idle)..."
            )

            testJob = viewModelScope.launch {
                val res = engine.runServer(
                    port = p,
                    onClientConnected = { clientAddr ->
                        _uiState.value = IPerfUiState.Running(
                            mode = IPerfMode.SERVER,
                            targetOrPort = "Client: $clientAddr",
                            statusMessage = "Connected client $clientAddr..."
                        )
                    },
                    onInterval = { item ->
                        intervalsList.add(item)
                        _uiState.value = IPerfUiState.Running(
                            mode = IPerfMode.SERVER,
                            targetOrPort = "Port $p",
                            liveBandwidthMbps = item.bandwidthMbps,
                            currentIntervals = intervalsList.toList(),
                            statusMessage = "Measuring stream [${item.timeRange}]"
                        )
                    }
                )

                if (res.isSuccess) {
                    _uiState.value = IPerfUiState.Completed(res.getOrThrow())
                } else {
                    val err = res.exceptionOrNull()
                    when (err) {
                        is kotlinx.coroutines.CancellationException -> {
                            _uiState.value = IPerfUiState.Idle
                        }
                        is IPerfServerIdleTimeoutException -> {
                            _serverNoticeMessage.value = err.message
                                ?: "Local iPerf3 server stopped automatically after 2 minutes with no connection."
                            _uiState.value = IPerfUiState.Idle
                        }
                        else -> {
                            val details = IPerf3ErrorClassifier.classify(
                                throwable = err ?: IllegalStateException("Server failed"),
                                targetHost = "Local Server",
                                resolvedIp = null,
                                port = p,
                                connectTimeoutMs = 0
                            )
                            _uiState.value = IPerfUiState.Error(details)
                        }
                    }
                }
            }
        }
    }

    fun stopTest() {
        if (_mode.value == IPerfMode.CLIENT && _uiState.value is IPerfUiState.Running) {
            engine.requestGracefulInterrupt()
        } else {
            engine.stop()
            testJob?.cancel()
            _uiState.value = IPerfUiState.Idle
        }
    }

    @JvmOverloads
    fun stopServerIfRunning(reason: String? = null) {
        if (_mode.value == IPerfMode.SERVER && (engine.isRunning() || _uiState.value is IPerfUiState.Running)) {
            stopTest()
            if (!reason.isNullOrBlank()) {
                _serverNoticeMessage.value = reason
            }
        }
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }
}
