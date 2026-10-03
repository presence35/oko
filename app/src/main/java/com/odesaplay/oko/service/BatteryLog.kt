package com.odesaplay.oko

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
    val batteryUnoptimized: Boolean?,
    /** How many threats the feed carried this tick — NEPTUN's throughput, NOT the user's load.
     *  Kept because per-threat parse/decide work is a plausible drain, but never read it as
     *  "work near the user"; [activeZones] is that number. */
    val feedThreats: Int? = null,
    /** How many threats were inside the user's zones this tick: the local cost, and the one that
     *  correlates with radio/GPS/wakelock activity. */
    val activeZones: Int? = null,
    /** Screen brightness in 0..1 — a map-heavy screen-on hour is the usual drain, and without
     *  this you cannot tell "app leaked waketime" from "user had the map open at full
     *  brightness". Under auto-brightness this reports the user's SLIDER, so it is a relative
     *  proxy, not the emitted luminance. Null when the setting cannot be read. */
    val screenBrightness: Float? = null
)

/**
 * In-memory ring buffer of power-state samples, consumed by the log export for battery-drain
 * diagnosis.
 *
 * Fed from [AlertService]'s monitor tick — the same tick that feeds `GpsLog.observe`. That host
 * is deliberate: it runs whenever the alert service is alive, independent of the connection, so
 * samples keep arriving through exactly the network trouble and source shutdowns you most need
 * to correlate a drain with. (It was previously driven by the connection watchdog, which stops
 * when the socket supervisor stops — meaning the timeline went blank precisely when the phone was
 * misbehaving.)
 *
 * A gap in the timeline is therefore a finding, not a sampler failure: it means the service was
 * not running, which is itself the battery symptom. `LogBundle.powerSummary` reports the longest
 * gap so that reads as data instead of disappearing into an array nobody scans.
 *
 * [sample] self-throttles so callers can tick at 1s or 30s without caring.
 */
object BatteryLog {

    /** One sample per idle tick (30s) requires the interval to sit BELOW it: two equal periods
     *  beat against each other, so a 30s throttle on a 30s tick drops every other sample to a
     *  60s cadence under any jitter. 25s guarantees exactly one per idle tick. */
    private const val SAMPLE_INTERVAL_MS = 25_000L
    private const val MAX_ENTRIES = 3_456 // ~24h at SAMPLE_INTERVAL_MS

    private val _entries = MutableStateFlow<List<BatterySample>>(emptyList())
    val entries: StateFlow<List<BatterySample>> = _entries.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var lastSampleMono = 0L

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Records a sample unless one was already taken inside the interval. Cheap enough to
     *  call from a 1s tick — the throttle is what makes it cheap, not the caller.
     *
     *  The throttle reads MONOTONIC time while [now] is the wall clock: a backward wall-clock
     *  jump (NTP, a manual change, DST) would make the delta negative and stall sampling for the
     *  length of the jump, which is a silent hole in exactly the timeline being investigated. */
    fun sample(
        now: Long = System.currentTimeMillis(),
        feedThreats: Int? = null,
        activeZones: Int? = null
    ) {
        val mono = SystemClock.elapsedRealtime()
        if (mono - lastSampleMono < SAMPLE_INTERVAL_MS) return
        lastSampleMono = mono
        val ctx = appContext ?: return
        val pm = ctx.getSystemService(PowerManager::class.java)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val entry = BatterySample(
            atMillis = now,
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
            batteryUnoptimized = runCatching { pm?.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrNull(),
            feedThreats = feedThreats,
            activeZones = activeZones,
            // Auto-brightness is the common case and this reports the user's slider, so it is
            // a relative proxy only — good enough to separate "map open, screen bright" from
            // "idle in a pocket", which is the question this column exists to answer.
            screenBrightness = runCatching {
                val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                if (raw in 1..254) raw / 255f else null
            }.getOrNull()
        )
        val current = _entries.value
        _entries.value =
            if (current.size >= MAX_ENTRIES) current.takeLast(MAX_ENTRIES - 1) + entry else current + entry
    }
}
