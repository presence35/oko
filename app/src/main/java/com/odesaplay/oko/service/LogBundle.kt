package com.odesaplay.oko

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    /**
     * Bumped to 2 when `app.lastUpdateTime` and the `exits`/`exitSummary` blocks were added. The
     * payload only ever grows, so an old reader still parses a new bundle — the number exists so a
     * reader can tell "field absent because the build predates it" from "field absent because the
     * OS would not say".
     */
    private const val SCHEMA = 2

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

    /** Server-side filename. */
    fun fileName(context: Context): String = bundleFileName(
        brand = Build.BRAND,
        model = Build.MODEL,
        versionCode = BuildConfig.VERSION_CODE,
        atMillis = System.currentTimeMillis(),
        zone = ZoneId.systemDefault()
    )

    /** Hyphens are kept because real model names carry them ("SM-A505FN") and the endpoint's own
     *  sanitiser allows them too. Dots are deliberately NOT allowed in the brand/model part: a
     *  dot is the only way a name could contain a path segment, and the model name never needs
     *  one. Only the fixed `.json` suffix we append may contain a dot. */
    private val NON_FILE_SAFE = Regex("[^A-Za-z0-9-]")

    /** `<brand>_<model>_<versionCode>_<yyyyMMdd-HHmmss>.json`.
     *
     *  The timestamp exists because the endpoint used to overwrite one slot per device per build,
     *  which quietly destroyed the previous snapshot the moment a tester tapped twice — and a
     *  staged run (reboot, wait, open, send) is exactly when you tap more than once. The stamp is
     *  LOCAL time on purpose: the person reading the directory is asking "when did they send
     *  this", not "what was UTC". Seconds resolution is enough because the endpoint rate-limits
     *  to one write per 30s per IP, so two bundles in the same second cannot collide.
     *
     *  Kept pure and free of `Build`/`Context` so the sanitising is unit-testable: a brand or
     *  model that reduces to nothing must still produce a usable, filesystem-safe name. */
    internal fun bundleFileName(
        brand: String?,
        model: String?,
        versionCode: Int,
        atMillis: Long,
        zone: ZoneId
    ): String {
        fun part(raw: String?, fallback: String): String {
            val clean = NON_FILE_SAFE.replace(raw.orEmpty(), "")
            return clean.ifBlank { fallback }
        }
        val stamp = Instant.ofEpochMilli(atMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "${part(brand, "unknown")}_${part(model, "model")}_${versionCode}_$stamp.json"
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
        // Sampled once so `exportedAt` and the wall-to-monotonic conversion in [readExits] share
        // one instant and the arithmetic below is reproducible from the bundle alone.
        val nowMs = System.currentTimeMillis()

        val root = JSONObject()
        root.p("schema", SCHEMA)
        root.p("exportedAt", Instant.ofEpochMilli(nowMs).toString())
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
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            p("firstInstall", pkg.firstInstallTime)
            // A self-update kills the process, so `powerSummary.restartsObserved` is unreadable
            // without knowing when the build landed: the first "restart" after an install is the
            // installer, not a mystery kill.
            p("lastUpdate", pkg.lastUpdateTime)
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
            val processCpuMs = Process.getElapsedCpuTime()
            // `uptimeMs` is DEVICE uptime (since boot), so dividing process CPU by it reports
            // ~0% no matter how hard the app worked. Age the denominator off the process start
            // instead, or the number is decoration.
            val deviceUptimeMs = uptime
            val processAgeMs = (deviceUptimeMs - Process.getStartElapsedRealtime()).coerceAtLeast(0)
            p("processCpuMs", processCpuMs)
            p("processAgeMs", processAgeMs)
            p("deviceUptimeMs", deviceUptimeMs)
            p("appCpuPercent", if (processAgeMs > 0) processCpuMs * 100 / processAgeMs else null)
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

        // The OS's own account of why the process died. `powerSummary.restartsObserved` only says
        // THAT it died; this says who killed it and why, which is the question a tester cannot
        // answer from the app's side at all.
        val exits = readExits(context, am, uptime, nowMs)
        exits?.let {
            root.p("exits", it.map { e ->
                JSONObject().apply {
                    p("timestampMs", e.timestampMs)
                    p("elapsedRealtimeMs", e.elapsedRealtimeMs)
                    p("reason", e.reason)
                    p("reasonName", e.reasonName)
                    p("description", scrub(e.description))
                    p("status", e.status)
                    p("importance", e.importance)
                    p("pssKb", e.pssKb)
                    p("rssKb", e.rssKb)
                }
            }.toJson())
            root.p("exitSummary", summariseExits(it))
        }

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
            val power = BatteryLog.entries.value
            p("power", power.map { e ->
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
                    p("processStartMonoMs", e.processStartMonoMs)
                    p("feedThreats", e.feedThreats)
                    p("activeZones", e.activeZones)
                    p("screenBrightness", e.screenBrightness)
                }
            }.toJson())
            p("powerSummary", summarisePower(power))
        })

        return root
    }

    /**
     * How many historical exits to keep. The OS caps this anyway; 32 covers the worst night we
     * have seen (seven kills in four and a half hours) several times over without turning the
     * bundle into a diary.
     */
    private const val EXIT_HISTORY = 32

    /**
     * [android.app.ApplicationExitInfo.IMPORTANCE_FOREGROUND]. Named locally so the pure
     * [summariseExits] needs no SDK reference and stays unit-testable on a JVM.
     */
    private const val IMPORTANCE_FOREGROUND = 100

    /**
     * The OS's kill log, oldest-last as the platform returns it. Null on API < 30, which per this
     * file's convention means "not readable on this device" — an absent key, not a null one.
     *
     * Two things only the platform can answer, both asked after a real field report:
     *  - **who killed the process.** `powerSummary.restartsObserved` detects a death by watching
     *    the process generation change between samples; it cannot tell a low-memory kill from a
     *    crash from the user swiping the app away.
     *  - **how long it lived.** [ApplicationExitInfo] exposes only a wall-clock death time, but
     *    every power row carries [android.os.Process.getStartElapsedRealtime], which is on the
     *    monotonic clock — so the death is converted onto that clock here and a lifetime becomes
     *    `elapsedRealtimeMs - (that generation's processStartMonoMs)`.
     *
     * The conversion assumes the wall clock did not move between the death and [nowMs]; the raw
     * `timestampMs` ships alongside it, and `uptimeMs`/`exportedAt` are at the bundle root, so a
     * reader who distrusts it can redo the arithmetic. [ApplicationExitInfo] has no monotonic
     * accessor of its own and no public name for a reason, hence the local table in
     * [exitReasonName].
     */
    private fun readExits(
        context: Context,
        am: ActivityManager?,
        uptimeMs: Long,
        nowMs: Long
    ): List<ProcessExit>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            am?.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_HISTORY)
                ?.mapNotNull { info ->
                    // The CALLING process heads the list as a live record: reason OTHER, no memory
                    // figures. It has not died, so reporting it as a death would invent one on
                    // every single bundle and drown the real ones.
                    if (info.reason == ApplicationExitInfo.REASON_OTHER && info.pss == 0L) {
                        null
                    } else {
                        ProcessExit(
                            timestampMs = info.timestamp,
                            elapsedRealtimeMs = uptimeMs - (nowMs - info.timestamp),
                            reason = info.reason,
                            reasonName = exitReasonName(info.reason),
                            description = info.description,
                            status = info.status,
                            importance = info.importance,
                            pssKb = info.pss,
                            rssKb = info.rss
                        )
                    }
                }
        }.getOrNull()
    }

    /**
     * The API-30 reason set, by name. Deliberately a closed list over constants rather than a
     * hand-typed number table, so a value can never be paired with the wrong label. Anything not
     * listed lands in the `else` branch and still carries its number — which covers both reasons
     * added by later API levels (API 34 alone added several) and the two API-30 reasons that are
     * `@SystemApi` and therefore not nameable from here. The platform's own `reasonToString` is
     * not public API either, which is why the table exists.
     */
    internal fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
        else -> "REASON_$reason"
    }

    /**
     * Derived read of [readExits], same contract as [summarisePower]: the array never has to be
     * read by hand. `byReason` is the headline (a `REASON_CRASH: 3` is a bug report, a
     * `REASON_USER_REQUESTED: 12` is a user), and `whileForeground` separates "the system reaped
     * us while backgrounded" from "we died holding a screen the user was looking at".
     *
     * Pure so it is unit-testable; the arithmetic is the whole point.
     */
    internal fun summariseExits(exits: List<ProcessExit>): JSONObject {
        val byReason = exits.groupingBy { it.reasonName }.eachCount()
        return JSONObject().apply {
            p("total", exits.size)
            p("oldestTimestampMs", exits.minOfOrNull { it.timestampMs })
            p("newestTimestampMs", exits.maxOfOrNull { it.timestampMs })
            p("byReason", JSONObject().apply { byReason.forEach { (k, v) -> p(k, v) } })
            p("whileForeground", exits.count { it.importance >= IMPORTANCE_FOREGROUND })
        }
    }

    /**
     * Derived read of the power timeline. Exists so the array never has to be read by hand, and
     * specifically so the failure modes that are invisible in raw rows are called out: `samples: 0`
     * (the sampler was dead), a large `longestGapSec` (the service was dead), and
     * `restartsObserved` (the PROCESS was killed and came back — the cause of the former).
     *
     * Pure so the restart arithmetic is unit-testable; that arithmetic is the whole point of the
     * function, and it would be untestable inline in `build`.
     */
    internal fun summarisePower(power: List<BatterySample>): JSONObject {
        if (power.isEmpty()) return JSONObject().apply { p("samples", 0) }
        val first = power.first().atMillis
        val last = power.last().atMillis
        var longestGapMs = 0L
        var zoneActive = 0
        var screenOn = 0
        var bright = 0f
        var brightCount = 0
        var restarts = 0
        var maxBlindWindowMs = 0L
        var previousStart: Long? = null
        val generations = LinkedHashSet<Long>()
        val pcts = ArrayList<Int>()

        for (i in power.indices) {
            val s = power[i]
            if (i > 0) {
                val gapMs = s.atMillis - power[i - 1].atMillis
                if (gapMs > longestGapMs) longestGapMs = gapMs
                // A new process generation inside a gap means the process died. The gap is an
                // UPPER BOUND on blindness: it also covers the wait before the new process's
                // first sample, so never quote it as the real figure.
                val prior = previousStart
                if (prior != null && s.processStartMonoMs != null && s.processStartMonoMs != prior) {
                    restarts++
                    if (gapMs > maxBlindWindowMs) maxBlindWindowMs = gapMs
                }
            }
            s.processStartMonoMs?.let { generations.add(it) }
            // Carry the last known value across a null (a sample predating this field) so the
            // first post-upgrade sample is not misread as a restart.
            if (s.processStartMonoMs != null) previousStart = s.processStartMonoMs
            if (s.interactive == true) screenOn++
            if ((s.activeZones ?: 0) > 0) zoneActive++
            s.screenBrightness?.let { bright += it; brightCount++ }
            s.batteryPct?.let { pcts.add(it) }
        }

        return JSONObject().apply {
            p("samples", power.size)
            p("firstAtMillis", first)
            p("lastAtMillis", last)
            p("spanSec", (last - first) / 1000)
            p("expectedSamplesAt25s", (last - first) / 25_000)
            p("longestGapSec", longestGapMs / 1000)
            // null, not 0: no samples carrying the field is "cannot tell", not "never restarted".
            p("processGenerations", generations.size.takeIf { generations.isNotEmpty() })
            p("restartsObserved", restarts.takeIf { generations.isNotEmpty() })
            p("maxBlindWindowSec", maxBlindWindowMs / 1000)
            p("screenOnPct", screenOn * 100 / power.size)
            p("zonesActivePct", zoneActive * 100 / power.size)
            p("meanBrightness", if (brightCount > 0) bright / brightCount else null)
            p("batteryPctMin", pcts.minOrNull())
            p("batteryPctMax", pcts.maxOrNull())
        }
    }

    private fun readProcStatus(): Map<String, String> = runCatching {
        File("/proc/self/status").readLines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }.getOrDefault(emptyMap())
}

/**
 * One historical process death, flattened out of [android.app.ApplicationExitInfo]. A plain data
 * class rather than the SDK type because the platform class cannot be constructed in a JVM unit
 * test, and the summarising is exactly the part worth testing.
 */
internal data class ProcessExit(
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val reason: Int,
    val reasonName: String,
    val description: String?,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long
)
