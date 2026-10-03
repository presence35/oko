package com.odesaplay.oko

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.TimeZone

/**
 * Builds the single JSON payload a beta tester uploads: who they are, what the phone is, what
 * the app was configured to do, what it was doing, and every in-app ring buffer. The point is to
 * answer a device question (battery drain, a missed alert) without a follow-up round trip to a
 * tester who will not answer it.
 *
 * Two properties are load-bearing:
 *  - **Source-agnostic.** Reads only the engine's own log currency, never a wire format.
 *  - **Redacted on the way out.** Every string goes through [scrub]; no log buffer carries raw
 *    coordinates at all (GpsLog stores accuracy and drift, never lat/lon), so there is no
 *    location to leak. Redaction lives here rather than at each call site so a future field
 *    cannot bypass it.
 *
 * Absent keys mean "not readable on this device"; a key present with JSON null means "read, and
 * the OS declined to say".
 */
object LogBundle {

    private const val SCHEMA = 1

    private val SECRET = Regex(
        "(?i)bearer\\s+\\S+" +
            "|[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}" +
            "|\\b[0-9a-fA-F]{40,}\\b" +
            "|(?:api[_-]?key|token|password|passwd|secret)\\s*[:=]\\s*\\S+"
    )

    private fun scrub(value: String?): String? = value?.let { SECRET.replace(it, "[redacted]") }

    /** `put` with null-removal semantics is a trap for nullable diagnostics; keep the key. */
    private fun JSONObject.p(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)

    private fun Iterable<Any?>.toJson(): JSONArray {
        val arr = JSONArray()
        forEach { arr.put(it ?: JSONObject.NULL) }
        return arr
    }

    /** Server-side filename: one slot per device per build, overwritten in place. */
    fun fileName(context: Context): String {
        val brand = (Build.BRAND ?: "unknown").replace(Regex("[^A-Za-z0-9]"), "")
        val model = (Build.MODEL ?: "unknown").replace(Regex("[^A-Za-z0-9]"), "")
        return "${brand}_${model}_${BuildConfig.VERSION_CODE}.json"
    }

    suspend fun build(context: Context, prefs: UserPrefs): JSONObject {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rt = Runtime.getRuntime()
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dbg = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val proc = readProcStatus()
        val uptime = SystemClock.elapsedRealtime()

        val root = JSONObject()
        root.p("schema", SCHEMA)
        root.p("exportedAt", Instant.now().toString())
        root.p("uptimeMs", uptime)
        root.p("fileName", fileName(context))

        root.p("app", JSONObject().apply {
            p("versionCode", BuildConfig.VERSION_CODE)
            p("versionName", BuildConfig.VERSION_NAME)
            p("flavor", BuildConfig.FLAVOR)
            p("package", context.packageName)
            p("debuggable", BuildConfig.DEBUG)
            p("selfUpdate", BuildConfig.SELF_UPDATE)
            p("installer", scrub(runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()))
            p("firstInstall", context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
        })

        root.p("device", JSONObject().apply {
            p("manufacturer", Build.MANUFACTURER)
            p("brand", Build.BRAND)
            p("model", Build.MODEL)
            p("device", Build.DEVICE)
            p("display", Build.DISPLAY)
            p("fingerprint", Build.FINGERPRINT)
            p("incremental", Build.VERSION.INCREMENTAL)
            p("securityPatch", Build.VERSION.SECURITY_PATCH)
            p("abis", Build.SUPPORTED_ABIS.joinToString(","))
            p("androidRelease", Build.VERSION.RELEASE)
            p("sdkInt", Build.VERSION.SDK_INT)
            p("locale", context.resources.configuration.locales.get(0).toString())
            p("tz", TimeZone.getDefault().id)
            p("screenOn", pm?.isInteractive)
            p("density", context.resources.displayMetrics.density)
            p("size", "${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
        })

        root.p("battery", JSONObject().apply {
            p("pct", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            p("currentNowUa", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            p("currentAverageUa", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            p("chargeCounterUah", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            p("energyCounterNwh", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
            p("temperatureTenthsC", sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
            p("status", sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            p("health", sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
            p("plugged", sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            p("powerSave", pm?.isPowerSaveMode)
            p("deviceIdle", pm?.isDeviceIdleMode)
            p("thermal", if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus else null)
            p("batteryUnoptimized", runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull())
        })

        root.p("cpu", JSONObject().apply {
            p("processCpuMs", Process.getElapsedCpuTime())
            p("uptimeMs", uptime)
            p("appCpuPercent", if (uptime > 0) Process.getElapsedCpuTime() * 100 / uptime else 0)
            p("debuggerAttached", Debug.isDebuggerConnected())
        })

        root.p("memory", JSONObject().apply {
            p("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576)
            p("heapMaxMb", rt.maxMemory() / 1048576)
            p("heapTotalMb", rt.totalMemory() / 1048576)
            p("systemTotalMb", memInfo.totalMem / 1048576)
            p("systemAvailMb", memInfo.availMem / 1048576)
            p("systemLow", memInfo.lowMemory)
            p("pssKb", dbg.totalPss)
            p("privateDirtyKb", dbg.totalPrivateDirty)
            p("dalvikPssKb", dbg.dalvikPss)
            p("nativePssKb", dbg.nativePss)
            p("vmRssKb", proc["VmRSS"]?.substringBefore(" ")?.toIntOrNull())
            p("vmHwmKb", proc["VmHWM"]?.substringBefore(" ")?.toIntOrNull())
            p("threads", proc["Threads"]?.toIntOrNull())
        })

        // Language and theme are prefs, not a second source of truth — they ship in the
        // snapshot below rather than being mirrored here.
        root.p("prefs", JSONObject().apply { prefs.snapshot().forEach { (k, v) -> p(k, scrub(v)) } })

        val registry = AppSources.registry
        root.p("sources", JSONObject().apply {
            p("connectionState", scrub(registry.connectionState.value.toString()))
            p("perSource", JSONObject().apply {
                registry.perSourceState.value.forEach { (id, state) -> p(id, scrub(state.toString())) }
            })
            p("activeAlertSource", registry.activeAlertSource.value)
            p("coveredByFallback", registry.coveredByFallback.value)
            p("wsHealthy", registry.wsHealthy.value)
            p("degraded", registry.degraded.value)
            p("degradedSince", registry.degradedSince.value)
            p("retryState", registry.retryState.value?.let {
                JSONObject().apply {
                    p("attempt", it.attempt)
                    p("delayMs", it.delayMs)
                    p("nextAtMs", it.nextAtMs)
                    p("networkValidated", it.networkValidated)
                }
            })
            p("events", registry.connEvents.value.map {
                JSONObject().apply {
                    p("atMillis", it.atMillis)
                    p("kind", it.kind.name)
                    p("attempt", it.attempt)
                    p("delayMs", it.delayMs)
                    p("detail", scrub(it.detail))
                }
            }.toJson())
        })

        root.p("logs", JSONObject().apply {
            p("decisions", DebugLog.entries.value.map { e ->
                JSONObject().apply {
                    p("atMillis", e.atMillis)
                    p("kind", e.kind.name)
                    p("reason", e.reason.name)
                    p("notified", e.notified)
                    p("night", e.night)
                    p("sirenOverride", e.sirenOverride)
                    p("vibrationLevel", e.vibrationLevel)
                    p("threatId", e.threatId)
                    p("threatType", e.threatType?.name)
                    p("tier", e.tier?.name)
                    p("distanceKm", e.distanceKm)
                    p("locality", scrub(e.locality))
                    p("scopeOblastId", e.scopeOblastId)
                    p("aboutMe", e.aboutMe)
                }
            }.toJson())
            p("connections", ConnectionLog.entries.value.map { e ->
                JSONObject().apply {
                    p("atMillis", e.atMillis)
                    p("status", e.status.name)
                    p("durationSec", e.durationSec)
                    p("activeSource", scrub(e.activeSource))
                    p("transport", e.transport?.name)
                }
            }.toJson())
            p("reconnects", ReconnectLog.entries.value.map { e ->
                JSONObject().apply {
                    p("atMillis", e.atMillis)
                    p("attemptNo", e.attemptNo)
                    p("delayMs", e.delayMs)
                    p("error", scrub(e.error))
                    p("networkValidated", e.networkValidated)
                    p("scheduledAt", e.scheduledAt)
                }
            }.toJson())
            p("gps", GpsLog.entries.value.map { e ->
                JSONObject().apply {
                    p("atMillis", e.atMillis)
                    p("kind", e.kind.name)
                    p("durationSec", e.durationSec)
                    p("detailKm", e.detailKm)
                    p("accuracyM", e.accuracyM)
                }
            }.toJson())
            p("power", BatteryLog.entries.value.map { e ->
                JSONObject().apply {
                    p("atMillis", e.atMillis)
                    p("batteryPct", e.batteryPct)
                    p("charging", e.charging)
                    p("temperatureTenthsC", e.temperatureTenthsC)
                    p("powerSave", e.powerSave)
                    p("deviceIdle", e.deviceIdle)
                    p("interactive", e.interactive)
                    p("thermal", e.thermal)
                    p("gpsEnabled", e.gpsEnabled)
                    p("batteryUnoptimized", e.batteryUnoptimized)
                }
            }.toJson())
        })

        return root
    }

    private fun readProcStatus(): Map<String, String> = runCatching {
        File("/proc/self/status").readLines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }.getOrDefault(emptyMap())
}
