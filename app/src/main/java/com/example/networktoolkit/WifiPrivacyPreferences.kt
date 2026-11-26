package com.example.networktoolkit

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable snapshot of on-screen Wi-Fi identifier visibility preferences.
 *
 * Both [showSsid] and [showBssid] default to `false` (OFF) and are completely independent:
 * enabling one never alters the other, and neither setting alters default report export redaction.
 */
data class WifiPrivacySettings(
    val showSsid: Boolean = false,
    val showBssid: Boolean = false
)

/**
 * Persists and broadcasts independent on-screen visibility settings for Wi-Fi SSID and BSSID
 * inside the app's existing `ToolkitSettings` SharedPreferences store.
 */
object WifiPrivacyPreferences {

    const val PREFS_NAME = "ToolkitSettings"
    const val KEY_SHOW_SSID = "privacy_show_ssid"
    const val KEY_SHOW_BSSID = "privacy_show_bssid"

    const val MASKED_SSID = "Hidden Network (Privacy Setting)"
    const val MASKED_BSSID = "Hidden MAC (Privacy Setting)"

    private val lock = Any()
    private val _settings = MutableStateFlow(WifiPrivacySettings())

    @Volatile
    private var listenerRegistered = false

    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun ensureInitialized(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!listenerRegistered) {
            synchronized(lock) {
                if (!listenerRegistered) {
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                        if (key == null || key == KEY_SHOW_SSID || key == KEY_SHOW_BSSID) {
                            _settings.value = readFromPrefs(sp)
                        }
                    }
                    prefs.registerOnSharedPreferenceChangeListener(listener)
                    prefListener = listener
                    _settings.value = readFromPrefs(prefs)
                    listenerRegistered = true
                }
            }
        }
        return prefs
    }

    private fun readFromPrefs(prefs: SharedPreferences): WifiPrivacySettings {
        return WifiPrivacySettings(
            showSsid = prefs.getBoolean(KEY_SHOW_SSID, false),
            showBssid = prefs.getBoolean(KEY_SHOW_BSSID, false)
        )
    }

    @JvmStatic
    fun settingsFlow(context: Context): StateFlow<WifiPrivacySettings> {
        val prefs = ensureInitialized(context)
        val latest = readFromPrefs(prefs)
        if (_settings.value != latest) {
            _settings.value = latest
        }
        return _settings.asStateFlow()
    }

    @JvmStatic
    fun getSettings(context: Context): WifiPrivacySettings {
        val prefs = ensureInitialized(context)
        val latest = readFromPrefs(prefs)
        _settings.value = latest
        return latest
    }

    @JvmStatic
    fun refreshFromPrefs(context: Context): WifiPrivacySettings {
        return getSettings(context)
    }

    @JvmStatic
    fun isShowSsidEnabled(context: Context): Boolean {
        return getSettings(context).showSsid
    }

    @JvmStatic
    fun isShowBssidEnabled(context: Context): Boolean {
        return getSettings(context).showBssid
    }

    @JvmStatic
    fun setShowSsid(context: Context, enabled: Boolean) {
        val prefs = ensureInitialized(context)
        prefs.edit().putBoolean(KEY_SHOW_SSID, enabled).commit()
        _settings.value = readFromPrefs(prefs)
    }

    @JvmStatic
    fun setShowBssid(context: Context, enabled: Boolean) {
        val prefs = ensureInitialized(context)
        prefs.edit().putBoolean(KEY_SHOW_BSSID, enabled).commit()
        _settings.value = readFromPrefs(prefs)
    }

    @JvmStatic
    fun updateSettings(context: Context, showSsid: Boolean, showBssid: Boolean) {
        val prefs = ensureInitialized(context)
        prefs.edit()
            .putBoolean(KEY_SHOW_SSID, showSsid)
            .putBoolean(KEY_SHOW_BSSID, showBssid)
            .commit()
        _settings.value = WifiPrivacySettings(showSsid = showSsid, showBssid = showBssid)
    }

    /**
     * Test helper to reset listener state if SharedPreferences are cleared in Robolectric tests.
     */
    internal fun resetForTesting(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefListener?.let {
                try {
                    prefs.unregisterOnSharedPreferenceChangeListener(it)
                } catch (_: Exception) {
                }
            }
            prefListener = null
            listenerRegistered = false
            _settings.value = WifiPrivacySettings()
        }
    }
}

/**
 * Observes [WifiPrivacyPreferences] reactively in Jetpack Compose and re-synchronizes whenever
 * the screen resumes so changes made in Settings take effect immediately without app restart.
 */
@Composable
fun rememberWifiPrivacySettings(): WifiPrivacySettings {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by WifiPrivacyPreferences.settingsFlow(context).collectAsStateWithLifecycle()

    DisposableEffect(context, lifecycleOwner) {
        WifiPrivacyPreferences.refreshFromPrefs(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                WifiPrivacyPreferences.refreshFromPrefs(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return settings
}
