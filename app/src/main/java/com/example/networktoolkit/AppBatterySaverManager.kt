package com.example.networktoolkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Policy controlling when non-essential UI animations (scanning spinners, live signal pulses,
 * router beacon rings) are paused to conserve device battery.
 */
enum class BatterySaverAnimationMode(
    val prefKey: String,
    val title: String,
    val description: String
) {
    AUTO_LOW_BATTERY(
        prefKey = "auto",
        title = "Auto (Pause on Low Battery)",
        description = "Pause non-essential animations when battery is <= 20% or OS Battery Saver is active"
    ),
    ALWAYS_PAUSE(
        prefKey = "always",
        title = "Always Pause Animations",
        description = "Keep all non-essential UI animations paused regardless of battery level"
    ),
    OFF(
        prefKey = "off",
        title = "Always Allow Animations",
        description = "Keep non-essential UI animations active even at low battery"
    );

    companion object {
        fun fromKey(key: String?): BatterySaverAnimationMode {
            return entries.firstOrNull { it.prefKey == key } ?: AUTO_LOW_BATTERY
        }
    }
}

data class BatterySaverUiState(
    val mode: BatterySaverAnimationMode = BatterySaverAnimationMode.AUTO_LOW_BATTERY,
    val batteryLevelPercent: Int? = null,
    val isCharging: Boolean = false,
    val isSystemPowerSave: Boolean = false,
    val lowBatteryThresholdPercent: Int = AppBatterySaverManager.DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT,
    val isPausingAnimations: Boolean = false
)

/**
 * Global battery-saver coordinator that monitors device battery level and OS power-save mode
 * and exposes whether non-essential UI animations should pause.
 */
class AppBatterySaverManager private constructor(context: Context) {

    companion object {
        const val PREFS_NAME = "ToolkitSettings"
        const val KEY_BATTERY_SAVER_MODE = "battery_saver_animation_mode"
        const val KEY_LOW_BATTERY_THRESHOLD = "battery_saver_low_threshold_percent"
        const val DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT = 20

        @Volatile
        private var INSTANCE: AppBatterySaverManager? = null

        @JvmStatic
        fun getInstance(context: Context): AppBatterySaverManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppBatterySaverManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        @JvmStatic
        fun shouldPauseAnimationsNow(context: Context): Boolean {
            return getInstance(context).shouldPauseAnimations.value
        }

        @VisibleForTesting
        fun resetForTesting() {
            synchronized(this) {
                INSTANCE?.unregisterReceiversSafely()
                INSTANCE = null
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<BatterySaverUiState> = _state.asStateFlow()

    private val _shouldPauseAnimations = MutableStateFlow(_state.value.isPausingAnimations)
    val shouldPauseAnimations: StateFlow<Boolean> = _shouldPauseAnimations.asStateFlow()

    private var batteryReceiver: BroadcastReceiver? = null
    private var powerSaveReceiver: BroadcastReceiver? = null

    init {
        registerReceiversSafely()
        refreshBatteryStateFromSystem()
    }

    private fun loadInitialState(): BatterySaverUiState {
        val mode = BatterySaverAnimationMode.fromKey(
            prefs.getString(KEY_BATTERY_SAVER_MODE, BatterySaverAnimationMode.AUTO_LOW_BATTERY.prefKey)
        )
        val threshold = prefs.getInt(KEY_LOW_BATTERY_THRESHOLD, DEFAULT_LOW_BATTERY_THRESHOLD_PERCENT)
            .coerceIn(5, 50)
        val initial = BatterySaverUiState(
            mode = mode,
            lowBatteryThresholdPercent = threshold,
            isSystemPowerSave = runCatching { powerManager?.isPowerSaveMode ?: false }.getOrDefault(false)
        )
        return initial.copy(isPausingAnimations = computeShouldPause(initial))
    }

    fun getMode(): BatterySaverAnimationMode = _state.value.mode

    @JvmOverloads
    fun setMode(mode: BatterySaverAnimationMode, persist: Boolean = true) {
        if (persist) {
            prefs.edit().putString(KEY_BATTERY_SAVER_MODE, mode.prefKey).apply()
        }
        publishUpdatedState(_state.value.copy(mode = mode))
    }

    fun setModeKey(modeKey: String) {
        setMode(BatterySaverAnimationMode.fromKey(modeKey))
    }

    fun getModeKey(): String = _state.value.mode.prefKey

    fun setLowBatteryThresholdPercent(percent: Int) {
        val clamped = percent.coerceIn(5, 50)
        prefs.edit().putInt(KEY_LOW_BATTERY_THRESHOLD, clamped).apply()
        publishUpdatedState(_state.value.copy(lowBatteryThresholdPercent = clamped))
    }

    fun refreshBatteryStateFromSystem() {
        val batteryIntent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val parsed = parseBatteryIntent(batteryIntent)
        val powerSave = runCatching { powerManager?.isPowerSaveMode ?: false }.getOrDefault(false)
        publishUpdatedState(
            _state.value.copy(
                batteryLevelPercent = parsed.first ?: _state.value.batteryLevelPercent,
                isCharging = parsed.second ?: _state.value.isCharging,
                isSystemPowerSave = powerSave
            )
        )
    }

    @VisibleForTesting
    fun updateBatteryStatus(
        batteryLevelPercent: Int?,
        isCharging: Boolean = false,
        isSystemPowerSave: Boolean = false
    ) {
        publishUpdatedState(
            _state.value.copy(
                batteryLevelPercent = batteryLevelPercent,
                isCharging = isCharging,
                isSystemPowerSave = isSystemPowerSave
            )
        )
    }

    fun getStatusSummary(): String {
        val s = _state.value
        val batText = s.batteryLevelPercent?.let { "$it%" } ?: "Unknown"
        val chargingSuffix = if (s.isCharging) " (Charging)" else ""
        val pauseText = if (s.isPausingAnimations) "Animations PAUSED" else "Animations Active"
        return "${s.mode.title} • Battery: $batText$chargingSuffix • $pauseText"
    }

    private fun publishUpdatedState(next: BatterySaverUiState) {
        val paused = computeShouldPause(next)
        val finalized = next.copy(isPausingAnimations = paused)
        _state.value = finalized
        _shouldPauseAnimations.value = paused
    }

    private fun computeShouldPause(s: BatterySaverUiState): Boolean {
        return when (s.mode) {
            BatterySaverAnimationMode.ALWAYS_PAUSE -> true
            BatterySaverAnimationMode.OFF -> false
            BatterySaverAnimationMode.AUTO_LOW_BATTERY -> {
                if (s.isSystemPowerSave) {
                    true
                } else {
                    val lvl = s.batteryLevelPercent
                    !s.isCharging && lvl != null && lvl in 0..s.lowBatteryThresholdPercent
                }
            }
        }
    }

    private fun parseBatteryIntent(intent: Intent?): Pair<Int?, Boolean?> {
        if (intent == null) return Pair(null, null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        return Pair(pct, charging)
    }

    private fun registerReceiversSafely() {
        val batRec = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val (pct, charging) = parseBatteryIntent(intent)
                    val powerSave = runCatching { powerManager?.isPowerSaveMode ?: false }.getOrDefault(false)
                    publishUpdatedState(
                        _state.value.copy(
                            batteryLevelPercent = pct ?: _state.value.batteryLevelPercent,
                            isCharging = charging ?: _state.value.isCharging,
                            isSystemPowerSave = powerSave
                        )
                    )
                }
            }
        }
        val pwrRec = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    val powerSave = runCatching { powerManager?.isPowerSaveMode ?: false }.getOrDefault(false)
                    publishUpdatedState(_state.value.copy(isSystemPowerSave = powerSave))
                }
            }
        }
        batteryReceiver = batRec
        powerSaveReceiver = pwrRec
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batRec,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                appContext,
                pwrRec,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    private fun unregisterReceiversSafely() {
        runCatching { batteryReceiver?.let { appContext.unregisterReceiver(it) } }
        runCatching { powerSaveReceiver?.let { appContext.unregisterReceiver(it) } }
        batteryReceiver = null
        powerSaveReceiver = null
    }
}

@Composable
fun rememberShouldPauseNonEssentialAnimations(): Boolean {
    val context = LocalContext.current
    val manager = AppBatterySaverManager.getInstance(context)
    val paused by manager.shouldPauseAnimations.collectAsStateWithLifecycle()
    return paused
}
