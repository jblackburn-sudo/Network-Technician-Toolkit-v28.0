package com.example.networktoolkit

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.networktoolkit.data.DiagnosticHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TracerouteUiState {
    data object Idle : TracerouteUiState()
    data class Running(
        val target: String,
        val targetIp: String,
        val currentHop: Int,
        val maxHops: Int,
        val hops: List<TracerouteHop>
    ) : TracerouteUiState()
    data class Completed(
        val summary: TracerouteSummary
    ) : TracerouteUiState()
    data class Error(val message: String) : TracerouteUiState()
}

/**
 * ViewModel managing the state of the traceroute utility and persisting completed sessions to Room.
 */
class TracerouteViewModel(application: Application? = null) : ViewModel() {

    private var appContext: Context? = application?.applicationContext
    private val engine = TracerouteEngine()

    private val _uiState = MutableStateFlow<TracerouteUiState>(TracerouteUiState.Idle)
    val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

    private val currentHops = mutableListOf<TracerouteHop>()

    fun setContext(context: Context) {
        this.appContext = context.applicationContext
    }

    fun startTraceroute(target: String, maxHops: Int = 20, context: Context? = null) {
        if (context != null) {
            this.appContext = context.applicationContext
        }
        currentHops.clear()
        engine.startTraceroute(target, maxHops, 2, object : TracerouteEngine.Callback {
            override fun onStarted(target: String, targetIp: String, maxHops: Int) {
                _uiState.value = TracerouteUiState.Running(
                    target = target,
                    targetIp = targetIp,
                    currentHop = 0,
                    maxHops = maxHops,
                    hops = emptyList()
                )
            }

            override fun onHopReceived(hop: TracerouteHop) {
                currentHops.add(hop)
                val current = _uiState.value
                if (current is TracerouteUiState.Running) {
                    _uiState.value = current.copy(
                        currentHop = hop.hopNumber,
                        hops = currentHops.toList()
                    )
                }
            }

            override fun onProgress(currentHop: Int, maxHops: Int) {
                val current = _uiState.value
                if (current is TracerouteUiState.Running) {
                    _uiState.value = current.copy(currentHop = currentHop)
                }
            }

            override fun onCompleted(summary: TracerouteSummary) {
                _uiState.value = TracerouteUiState.Completed(summary)
                appContext?.let { ctx ->
                    try {
                        DiagnosticHistoryRepository.saveTracerouteAsync(ctx, summary)
                    } catch (e: Exception) {
                        android.util.Log.e("TracerouteViewModel", "Failed to auto-save traceroute session", e)
                    }
                }
            }

            override fun onError(message: String) {
                _uiState.value = TracerouteUiState.Error(message)
            }
        })
    }

    fun stopTraceroute() {
        engine.stop()
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}
