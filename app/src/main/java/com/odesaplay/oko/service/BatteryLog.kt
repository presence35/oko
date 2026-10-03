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
import org.json.JSONObject
import java.io.File

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
 * Power-state timeline for battery-drain diagnosis, consumed by the log export.
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
 * **Persisted, because the usage pattern demands it.** A beta tester installs a build, opens the
 * app and taps "Send logs" — which restarts the process and hands us a ~1-sample timeline. An
 * in-memory ring is therefore worthless for exactly the person this exists for. Storage is
 * newline-delimited JSON appended one line per sample and compacted when it doubles, NOT
 * DataStore: DataStore rewrites its whole blob per write, which at one write per 25s would cost
 * more battery than the thing we are measuring. This is the same line-based shape the sibling
 * logs already use for their own persistence.
 */
object BatteryLog {

    /** One sample per idle tick (30s) requires the interval to sit BELOW it: two equal periods
     *  beat against each other, so a 30s throttle on a 30s tick drops every other sample to a
     *  60s cadence under any jitter. 25s guarantees exactly one per idle tick. */
    private const val SAMPLE_INTERVAL_MS = 25_000L
    private const val MAX_ENTRIES = 3_456 // ~24h at SAMPLE_INTERVAL_MS
    private const val FILE_NAME = "battery_log.jsonl"

    private val _entries = MutableStateFlow<List<BatterySample>>(emptyList())
    val entries: StateFlow<List<BatterySample>> = _entries.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: File? = null
    @Volatile private var lastSampleMono = 0L
    private var linesOnDisk = 0

    /** Idempotent — both AlertService and MainActivity attach. */
    fun attach(context: Context) {
        val app = context.applicationContext
        if (store != null) return
        appContext = app
        val file = File(app.filesDir, FILE_NAME)
        store = file
        synchronized(this) {
            if (linesOnDisk == 0 && file.exists()) restore(file)
        }
    }

    private fun restore(file: File) {
        val restored = runCatching {
            file.useLines { lines ->
                val kept = ArrayDeque<BatterySample>(MAX_ENTRIES)
                var count = 0
                lines.forEach { line ->
                    count++
                    if (line.isNotBlank()) parse(line)?.let {
                        if (kept.size == MAX_ENTRIES) kept.removeFirst()
                        kept.addLast(it)
                    }
                }
                linesOnDisk = count
                kept.toList()
            }
        }.getOrDefault(emptyList())
        if (restored.isNotEmpty()) _entries.value = restored
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
        val ctx = appContext ?: return
        // Throttle AFTER the context check: updating the clock before it means one early call
        // with no context would silently swallow the next 25s of samples.
        val mono = SystemClock.elapsedRealtime()
        if (mono - lastSampleMono < SAMPLE_INTERVAL_MS) return
        lastSampleMono = mono
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
        val next =
            if (current.size >= MAX_ENTRIES) current.takeLast(MAX_ENTRIES - 1) + entry else current + entry
        _entries.value = next
        persist(entry, next)
    }

    private fun persist(entry: BatterySample, ring: List<BatterySample>) {
        val file = store ?: return
        runCatching {
            synchronized(this) {
                if (linesOnDisk > MAX_ENTRIES * 2) {
                    // Compact: the ring already holds exactly the window we keep, so rewrite it
                    // wholesale through a temp file. Truncate-and-rename, so a crash mid-write
                    // leaves the previous timeline intact rather than a half-written one.
                    val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                    tmp.bufferedWriter().use { w -> ring.forEach { w.appendLine(encode(it)) } }
                    if (!tmp.renameTo(file)) tmp.delete()
                    linesOnDisk = ring.size
                } else {
                    file.appendText(encode(entry) + "\n")
                    linesOnDisk++
                }
            }
        }
    }

    private fun encode(s: BatterySample): String = JSONObject().apply {
        put("t", s.atMillis)
        s.batteryPct?.let { put("pct", it) }
        s.charging?.let { put("chg", it) }
        s.temperatureTenthsC?.let { put("tmp", it) }
        s.powerSave?.let { put("ps", it) }
        s.deviceIdle?.let { put("idl", it) }
        s.interactive?.let { put("scr", it) }
        s.thermal?.let { put("thm", it) }
        s.gpsEnabled?.let { put("gps", it) }
        s.batteryUnoptimized?.let { put("opt", it) }
        s.feedThreats?.let { put("feed", it) }
        s.activeZones?.let { put("zn", it) }
        s.screenBrightness?.let { put("bri", it.toDouble()) }
    }.toString()

    private fun parse(line: String): BatterySample? = runCatching {
        val o = JSONObject(line)
        BatterySample(
            atMillis = o.optLong("t"),
            batteryPct = o.optIntOrNull("pct"),
            charging = o.optBooleanOrNull("chg"),
            temperatureTenthsC = o.optIntOrNull("tmp"),
            powerSave = o.optBooleanOrNull("ps"),
            deviceIdle = o.optBooleanOrNull("idl"),
            interactive = o.optBooleanOrNull("scr"),
            thermal = o.optIntOrNull("thm"),
            gpsEnabled = o.optBooleanOrNull("gps"),
            batteryUnoptimized = o.optBooleanOrNull("opt"),
            feedThreats = o.optIntOrNull("feed"),
            activeZones = o.optIntOrNull("zn"),
            screenBrightness = o.optDoubleOrNull("bri")?.toFloat()
        )
    }.getOrNull()

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) optInt(key) else null
    private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (has(key)) optBoolean(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) optDouble(key) else null
}
