package com.odesaplay.oko

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One power-state sample. Every field is a cheap system read; null means the OS or device
 * would not answer, never a silent zero. [thermal] is [PowerManager.THERMAL_STATUS_NONE]-based
 * (API 29+) and is the field that catches a device throttling itself to cool down.
 */
data class BatterySample(
    val atMillis: Long,
    val batteryPct: Int?,
    val charging: Boolean?,
    val temperatureTenthsC: Int?,
    val powerSave: Boolean?,
    val deviceIdle: Boolean?,
    val interactive: Boolean?,
    val thermal: Int?,
    val gpsEnabled: Boolean?,
    val batteryUnoptimized: Boolean?
)

/**
 * In-memory ring buffer of power-state samples, consumed by the log export for battery-drain
 * diagnosis. Fed by the connection watchdog tick (30s), which is already scheduled and already
 * awake — so the timeline costs zero extra wakeups. Deliberately not persisted: a drain report
 * is collected from a live session and [MAX_ENTRIES] already spans ~10 hours.
 */
object BatteryLog {

    private const val MAX_ENTRIES = 1200

    private val _entries = MutableStateFlow<List<BatterySample>>(emptyList())
    val entries: StateFlow<List<BatterySample>> = _entries.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun sample() {
        val ctx = appContext ?: return
        val pm = ctx.getSystemService(PowerManager::class.java)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val entry = BatterySample(
            atMillis = System.currentTimeMillis(),
            batteryPct = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 },
            charging = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            },
            temperatureTenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it != -2147483648 },
            powerSave = pm?.isPowerSaveMode,
            deviceIdle = pm?.isDeviceIdleMode,
            interactive = pm?.isInteractive,
            thermal = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus else null,
            gpsEnabled = runCatching {
                ctx.getSystemService(LocationManager::class.java)
                    ?.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrNull(),
            batteryUnoptimized = runCatching { pm?.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrNull()
        )
        val current = _entries.value
        _entries.value =
            if (current.size >= MAX_ENTRIES) current.takeLast(MAX_ENTRIES - 1) + entry else current + entry
    }
}
