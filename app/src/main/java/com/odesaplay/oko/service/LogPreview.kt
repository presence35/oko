package com.odesaplay.oko

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which block of the Debug tab a section is. The UI maps it to localized copy **by ordinal**, so
 *  the order here is the display order and [Strings.LogPreviewStrings.sectionTitles] must match it. */
enum class PreviewSectionId { DEVICE, USAGE, LOCATION, INSTALL, THREATS, SETTINGS }

/**
 * One line of the Debug tab. [path] is the literal JSON key path that ships — dotted, never a
 * friendly sentence — because the screen's only claim to trust is that the label is the key the
 * reader would find by opening the file. [sensitive] marks the handful worth landing the eye on.
 */
data class PreviewRow(val path: String, val value: String, val sensitive: Boolean = false)

/** [id] picks the title and the notes; [rows] carry no prose of their own. */
data class PreviewSection(val id: PreviewSectionId, val rows: List<PreviewRow>)

/**
 * Projects an already-built bundle onto the handful of things a tester wants to check *before*
 * sending it: what identifies the phone, when and where the app is used, where they are, how it was
 * installed, how much threat data rides along, and every stored setting.
 *
 * Two properties are load-bearing:
 *  - **It reads the real payload**, not a parallel description of it, so the tab cannot drift out
 *    of step with `LogBundle`. Every value below is extracted by key path from the bundle it is
 *    handed.
 *  - **It never redacts.** A preview that hid a field would be worse than no preview; the whole
 *    point is to show the uncomfortable line. `prefs` is listed in full for the same reason —
 *    `UserPrefs.snapshot()` is the one place a future field can arrive unreviewed, and enumerating
 *    it is the only way the screen stays honest about what is in it.
 *
 * Localized prose (section titles and notes) lives in [Strings.LogPreviewStrings]; this object
 * emits only paths and values, so it stays a pure function of the payload with no `Context`.
 */
object LogPreview {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Rows beyond this are summarised as "+N more": the full set is in the payload either way, and
     *  a location row that runs off the screen stops being readable. */
    private const val MAX_NAMES = 12

    fun project(bundle: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<PreviewSection> =
        listOf(
            device(bundle),
            usage(bundle, zone),
            location(bundle),
            install(bundle),
            threats(bundle),
            settings(bundle)
        )

    private fun device(bundle: JSONObject): PreviewSection {
        val d = bundle.optJSONObject("device")
        return PreviewSection(
            PreviewSectionId.DEVICE,
            listOf(
                row("device", d, "fingerprint", sensitive = true),
                row("device", d, "manufacturer"),
                row("device", d, "brand"),
                row("device", d, "model", sensitive = true),
                row("device", d, "device"),
                row("device", d, "display"),
                row("device", d, "incremental"),
                row("device", d, "securityPatch"),
                row("device", d, "abis"),
                row("device", d, "sdkInt"),
                row("device", d, "androidRelease"),
                row("device", d, "size"),
                row("device", d, "density")
            ).tight()
        )
    }

    private fun usage(bundle: JSONObject, zone: ZoneId): PreviewSection {
        val d = bundle.optJSONObject("device")
        val a = bundle.optJSONObject("app")
        return PreviewSection(
            PreviewSectionId.USAGE,
            listOf(
                row("device", d, "locale", sensitive = true),
                row("device", d, "tz", sensitive = true),
                stamped("app.firstInstall", a, "firstInstall", zone),
                stamped("app.lastUpdate", a, "lastUpdate", zone),
                row("app", a, "versionName"),
                row("app", a, "versionCode"),
                row("device", d, "screenOn"),
                row("", bundle, "schema"),
                row("", bundle, "uptimeMs"),
                row("", bundle, "exportedAt")
            ).tight()
        )
    }

    private fun location(bundle: JSONObject): PreviewSection {
        val logs = bundle.optJSONObject("logs")
        val decisions = logs?.optJSONArray("decisions")
        val loc = bundle.optJSONObject("location")
        return PreviewSection(
            PreviewSectionId.LOCATION,
            listOf(
                row("location", loc, "hasPosition"),
                row("location", loc, "fresh"),
                row("location", loc, "lastFixAgeSec"),
                row("location", loc, "accuracyM"),
                row("location", loc, "accessBlocked"),
                row("prefs", bundle.optJSONObject("prefs"), "pinned_city", sensitive = true),
                // The city of every alert that reached this phone — the only real location signal
                // the payload carries, since GpsLog stores accuracy and drift rather than a fix.
                names("logs.decisions[].locality", decisions, "locality"),
                names("logs.decisions[].scopeOblastId", decisions, "scopeOblastId"),
                PreviewRow(
                    "logs.decisions[].aboutMe",
                    decisions?.countTrue("aboutMe")?.toString() ?: ""
                ).takeIf { decisions != null },
                counted("logs.gps", logs?.optJSONArray("gps"))
            ).tight()
        )
    }

    private fun install(bundle: JSONObject): PreviewSection {
        val a = bundle.optJSONObject("app")
        return PreviewSection(
            PreviewSectionId.INSTALL,
            listOf(
                // Which app store (or chat) the install came from — the one field that says
                // something about the person rather than the phone.
                row("app", a, "installer", sensitive = true),
                row("app", a, "flavor"),
                row("app", a, "selfUpdate"),
                row("app", a, "debuggable"),
                row("app", a, "package")
            ).tight()
        )
    }

    private fun threats(bundle: JSONObject): PreviewSection {
        val logs = bundle.optJSONObject("logs")
        val sources = bundle.optJSONObject("sources")
        return PreviewSection(
            PreviewSectionId.THREATS,
            listOf(
                row("sources", sources, "connectionState"),
                row("sources", sources, "activeAlertSource"),
                row("sources", sources, "wsHealthy"),
                row("sources", sources, "degraded"),
                row("sources", sources, "degradedSince"),
                row("sources", sources, "coveredByFallback"),
                counted("sources.perSource", sources?.optJSONObject("perSource"), "keys"),
                counted("sources.retryState", sources?.optJSONObject("retryState"), "keys"),
                counted("sources.events", sources?.optJSONArray("events")),
                counted("logs.decisions", logs?.optJSONArray("decisions")),
                counted("logs.connections", logs?.optJSONArray("connections")),
                counted("logs.reconnects", logs?.optJSONArray("reconnects")),
                counted("logs.wakeEvents", logs?.optJSONArray("wakeEvents")),
                counted("logs.monitorEvents", logs?.optJSONArray("monitorEvents")),
                counted("logs.power", logs?.optJSONArray("power")),
                counted("logs.powerSummary", logs?.optJSONObject("powerSummary"), "keys"),
                counted("exits", bundle.optJSONArray("exits"))
            ).tight()
        )
    }

    /** Every stored pref, sorted, with its own value. See the class doc for why this is not
     *  trimmed to the interesting ones. */
    private fun settings(bundle: JSONObject): PreviewSection {
        val prefs = bundle.optJSONObject("prefs")
            ?: return PreviewSection(PreviewSectionId.SETTINGS, emptyList())
        val keys = prefs.keys().asSequence().sorted().toList()
        return PreviewSection(
            PreviewSectionId.SETTINGS,
            buildList(keys.size + 1) {
                add(PreviewRow("prefs", keys.size.toString()))
                keys.forEach { add(PreviewRow("prefs.$it", render(prefs.get(it)))) }
            }
        )
    }

    /** One scalar key, or null when the key is absent — absent means "not readable on this device"
     *  and must not be rendered as a null someone might read as "the OS said no". */
    private fun row(path: String, obj: JSONObject?, key: String, sensitive: Boolean = false): PreviewRow? {
        if (obj == null || !obj.has(key)) return null
        return PreviewRow(label(path, key), render(obj.get(key)), sensitive)
    }

    /** An epoch-millis field, shown as the raw number **plus** a decoded stamp beside it. The raw
     *  value is what ships, so it leads; the gloss is additive and never replaces it. */
    private fun stamped(path: String, obj: JSONObject?, key: String, zone: ZoneId): PreviewRow? {
        if (obj == null || !obj.has(key)) return null
        val ms = obj.optLong(key, -1L)
        val gloss = if (ms > 0) "  (${STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))})" else ""
        return PreviewRow(path, render(obj.get(key)) + gloss)
    }

    private fun counted(path: String, arr: JSONArray?): PreviewRow? =
        arr?.let { PreviewRow(path, it.length().toString()) }

    private fun counted(path: String, obj: JSONObject?, unit: String): PreviewRow? =
        obj?.let { PreviewRow(path, "${it.length()} $unit") }

    /** Distinct non-blank values of [key] across the array, verbatim, capped at [MAX_NAMES]. */
    private fun names(path: String, arr: JSONArray?, key: String): PreviewRow? {
        arr ?: return null
        val distinct = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optString(key).orEmpty()
            if (v.isNotBlank()) distinct.add(v)
        }
        if (distinct.isEmpty()) return null
        val shown = distinct.take(MAX_NAMES).joinToString(", ")
        val rest = distinct.size - MAX_NAMES
        return PreviewRow(path, if (rest > 0) "$shown (+$rest more)" else shown, sensitive = true)
    }

    private fun JSONArray.countTrue(key: String): Int {
        var n = 0
        for (i in 0 until length()) if (optJSONObject(i)?.optBoolean(key) == true) n++
        return n
    }

    private fun label(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun render(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is JSONObject -> "${value.length()} keys"
        is JSONArray -> value.length().toString()
        else -> value.toString()
    }

    private fun List<PreviewRow?>.tight(): List<PreviewRow> = filterNotNull()
}
