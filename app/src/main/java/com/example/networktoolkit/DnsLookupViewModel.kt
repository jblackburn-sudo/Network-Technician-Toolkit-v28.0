package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DnsLookupUiState {
    data object Idle : DnsLookupUiState()
    data class Loading(val target: String, val queryType: String) : DnsLookupUiState()
    data class Success(val result: DnsLookupResult) : DnsLookupUiState()
    data class Error(val details: DnsErrorDetails) : DnsLookupUiState() {
        val message: String get() = details.message
    }
}

class DnsLookupViewModel(application: Application) : AndroidViewModel(application) {

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeLookupJob: Job? = null

    private val _uiState = MutableStateFlow<DnsLookupUiState>(DnsLookupUiState.Idle)
    val uiState: StateFlow<DnsLookupUiState> = _uiState.asStateFlow()

    private val _dnsEnvironment = MutableStateFlow(NetworkDnsInspector.inspect(application))
    val dnsEnvironment: StateFlow<ActiveDnsEnvironment> = _dnsEnvironment.asStateFlow()

    private val _selectedQueryType = MutableStateFlow("ALL")
    val selectedQueryType: StateFlow<String> = _selectedQueryType.asStateFlow()

    private val _selectedFilter = MutableStateFlow("ALL")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    private val _targetInput = MutableStateFlow("google.com")
    val targetInput: StateFlow<String> = _targetInput.asStateFlow()

    private val _allowPublicDohFallback = MutableStateFlow(false)
    val allowPublicDohFallback: StateFlow<Boolean> = _allowPublicDohFallback.asStateFlow()

    init {
        registerNetworkCallback()
        refreshDnsEnvironment()
    }

    fun refreshDnsEnvironment() {
        _dnsEnvironment.value = NetworkDnsInspector.inspect(getApplication())
    }

    fun onTargetChanged(newTarget: String) {
        _targetInput.value = newTarget
    }

    fun onQueryTypeSelected(type: String) {
        _selectedQueryType.value = type
        if (type != "ALL") {
            _selectedFilter.value = "ALL"
        }
    }

    fun onFilterSelected(filter: String) {
        _selectedFilter.value = filter
    }

    fun onPublicDohFallbackChanged(enabled: Boolean) {
        _allowPublicDohFallback.value = enabled
    }

    fun performLookup(target: String = _targetInput.value, queryType: String = _selectedQueryType.value) {
        activeLookupJob?.cancel()

        val clean = target.trim()
        val freshEnv = NetworkDnsInspector.inspect(getApplication())
        _dnsEnvironment.value = freshEnv

        if (clean.isBlank()) {
            _uiState.value = DnsLookupUiState.Error(
                DnsErrorDetails(
                    category = DnsFailureCategory.INVALID_INPUT,
                    message = "Please enter a valid domain name or IP address.",
                    query = "(empty)",
                    queryType = queryType,
                    technicalDetails = "Validation error: empty target hostname or IP.",
                    troubleshootingSteps = listOf("Enter a hostname (e.g. example.com) or IP (e.g. 8.8.8.8).")
                )
            )
            return
        }

        _targetInput.value = clean
        _uiState.value = DnsLookupUiState.Loading(clean, queryType)

        activeLookupJob = viewModelScope.launch {
            val result = DnsLookupEngine.lookup(
                target = clean,
                queryType = queryType,
                context = getApplication(),
                dnsEnvironment = freshEnv,
                allowPublicDohFallback = _allowPublicDohFallback.value
            )
            if (result.isSuccess) {
                _uiState.value = DnsLookupUiState.Success(result.getOrThrow())
            } else {
                val err = result.exceptionOrNull()
                val details = (err as? DnsLookupException)?.details ?: DnsErrorDetails(
                    category = DnsFailureCategory.RESOLUTION_FAILED,
                    message = err?.message ?: "Failed to resolve DNS records.",
                    query = clean,
                    queryType = queryType,
                    technicalDetails = err?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "Unknown error",
                    troubleshootingSteps = listOf("Verify network connectivity and domain spelling.")
                )
                _uiState.value = DnsLookupUiState.Error(details)
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder().build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshDnsEnvironment()
                override fun onLost(network: Network) = refreshDnsEnvironment()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshDnsEnvironment()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshDnsEnvironment()
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        activeLookupJob?.cancel()
        val cb = networkCallback
        if (cb != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
