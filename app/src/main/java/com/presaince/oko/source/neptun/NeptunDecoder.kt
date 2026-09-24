package com.presaince.oko.source.neptun

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import com.presaince.oko.ThreatType
import com.presaince.oko.Reliability
import com.presaince.oko.connection.Monotonic
import com.presaince.oko.engine.MonitorCore
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.OblastAlert
import com.presaince.oko.engine.TrailPoint
import com.presaince.oko.engine.fallbackCourse
import com.presaince.oko.engine.toEngineString
import com.presaince.oko.engine.toThreatType
import com.presaince.oko.source.ThreatRemoved
import java.time.Instant

/**
 * Isolated decoder for the NEPTUN stream.
 * Converts raw JSON text payloads into normalized threat/alert objects and invokes [MonitorCore].
 * All vendor-specific wire quirks, field corrections, and upstream bug sanitizations are owned here.
 */
class NeptunDecoder(
    private val core: MonitorCore
) {
    companion object {
        const val ALERT_CLEAR_CONFIRM_MS = 30_000L
        private const val TAG = "NeptunDecoder"

        // Upstream formats use parentheses with optional plus and question mark for approximate counts
        private val EXPLICIT_GROUP_COUNT_REGEX = Regex("""\((?<cnt>\d+)\+\?\)|\((?<cnt>\d+)\+\)|\((?<cnt>\d+)\)""")

        // Group formation semantics in titles
        private val GROUP_PAIR_REGEX = Regex("""(?iu)\bпара\b""")
        private val GROUP_SWARM_REGEX = Regex("""(?iu)\bрій\b""")
        private val GROUP_CLUSTER_REGEX = Regex("""(?iu)\b(?:група|хвиля)\b""")

        // Words denoting inherently single threats where upstream multi-counts represent leaked artifacts
        private val SINGULAR_TITLE_REGEX = Regex(
            """(?iu)\b(?:ракета|крилата ракета|балістика|бпла|шахед|дрон|каб|керована авіабомба|міг-?31|літак|розвідник)\b"""
        )

        /**
         * Upstream NEPTUN NLP occasionally leaks channel post counters, timestamps, or confirmation
         * tallies into `count` for singular threats (e.g. `count = 14` for a single missile).
         *
         * Upstream NEPTUN may fix their parser/wire schema in a future API release. Once upstream
         * stops leaking metadata into the `count` attribute, this vendor-specific sanitization becomes
         * pointless and can be safely retired.
         */
        fun sanitizeCount(rawCount: Int, rawTitle: String): Int {
            val title = rawTitle.trim()

            // Parenthetical count explicitly authored in title overrides raw field
            EXPLICIT_GROUP_COUNT_REGEX.find(title)?.groups?.get("cnt")?.value?.toIntOrNull()?.let {
                return it.coerceIn(1, 10)
            }

            // Group markers in title determine exact or bounded counts
            if (GROUP_PAIR_REGEX.containsMatchIn(title)) return 2
            if (GROUP_SWARM_REGEX.containsMatchIn(title)) return if (rawCount in 3..10) rawCount else 3
            if (GROUP_CLUSTER_REGEX.containsMatchIn(title)) return if (rawCount in 2..10) rawCount else 2

            // Upstream counts > 1 on singular titles are leaked telemetry artifacts
            if (SINGULAR_TITLE_REGEX.containsMatchIn(title)) return 1

            // Clamp into displayable single-cluster domain bounds
            return if (rawCount in 1..10) rawCount else 1
        }

        /**
         * NEPTUN sometimes fills `explanationShort` with bare confirmation counts (e.g. "Підтверджень: 3").
         * Strip redundant phrases and drop the field entirely when no course data remains.
         */
        fun sanitizeCourse(text: String?): String? {
            if (text == null) return null
            val cyr = "[А-Яа-яіїєґІЇЄҐ']"
            var t = text.replace(Regex("(?iu)підтвердж$cyr*"), " ")
            t = t.replace(Regex("^[\\s:.,—-]+"), "").trim()
            t = t.replaceFirst(Regex("(?iu)^\\d+\\s*(?:джерел$cyr*|sources?)?[\\s:.,—-]*"), "").trim()
            t = t.replace(Regex("(?iu)[\\s:.,—-]+\\d+(?:\\s*(?:джерел$cyr*|sources?|підтвердж$cyr*))?\\s*$"), "")
            if (t.isEmpty()) return null
            if (t.matches(Regex("(?iu)^\\d+(?:\\s*(?:джерел$cyr*|sources?))?\\.?$"))) return null
            return t
        }

        /**
         * Parses a single threat object from NEPTUN wire JSON into domain [NormalizedThreat].
         */
        fun parseNormalizedThreat(o: JSONObject, nowWall: Long = System.currentTimeMillis()): NormalizedThreat? {
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (o.optString("id").isBlank()) return null

            fun optNullable(key: String): String? =
                o.optString(key, "").takeIf { it.isNotBlank() }

            val velocity = o.optJSONObject("velocity")
            val speedKmh = velocity?.takeIf { it.has("speedKmh") }?.optDouble("speedKmh", Double.NaN)
                ?.takeIf { !it.isNaN() }
            val bearingDeg = velocity?.takeIf { it.has("bearingDeg") }?.optDouble("bearingDeg", Double.NaN)
                ?.takeIf { !it.isNaN() }
            val uncertainty = o.optDouble("uncertaintyKm", Double.NaN)
                .takeIf { !it.isNaN() }

            val updatedAt = optNullable("updatedAt")
            val updatedAtMillis = runCatching { updatedAt?.let { Instant.parse(it).toEpochMilli() } }
                .getOrNull()?.coerceAtMost(nowWall)
            val confirmedAt = optNullable("confirmedAt")
            val confirmedAtMillis = runCatching { confirmedAt?.let { Instant.parse(it).toEpochMilli() } }
                .getOrNull()?.coerceAtMost(nowWall)

            val rawTitle = o.optString("title", "")
            val rawCount = o.optInt("count", 0)

            return NormalizedThreat(
                id = o.optString("id"),
                type = ThreatType.fromApi(if (o.has("type") && !o.isNull("type")) o.optString("type") else null).toEngineString(),
                title = sanitizeCourse(rawTitle) ?: "",
                region = optNullable("region"),
                district = optNullable("district"),
                locality = optNullable("locality"),
                lat = lat,
                lon = lon,
                heading = if (o.has("heading") && !o.isNull("heading")) o.optDouble("heading") else null,
                bearingDeg = bearingDeg,
                status = o.optString("status", "active"),
                advisory = o.optBoolean("advisory", false),
                areaOnly = o.optBoolean("areaOnly", false),
                confirmations = o.optInt("sourceCount", o.optInt("sources", o.optInt("confirmations", 0))),
                reliability = Reliability.fromApi(
                    optNullable("confidenceLevel") ?: optNullable("reliability")
                ).name,
                count = sanitizeCount(rawCount, rawTitle),
                explanationShort = sanitizeCourse(optNullable("explanationShort")),
                speedKmh = speedKmh,
                uncertaintyKm = uncertainty,
                positionQuality = optNullable("positionQuality"),
                confirmedAtMillis = confirmedAtMillis,
                updatedAtMillis = updatedAtMillis,
                trail = parseTrail(o, nowWall)
            )
        }

        private fun parseTrail(o: JSONObject, now: Long): List<TrailPoint> {
            val arr = o.optJSONArray("trail") ?: return emptyList()
            val out = ArrayList<TrailPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.isNull("lat") || item.isNull("lon")) continue
                val pLat = item.optDouble("lat", Double.NaN)
                val pLon = item.optDouble("lon", Double.NaN)
                if (pLat.isNaN() || pLon.isNaN()) continue
                val t = if (item.has("t") && !item.isNull("t")) {
                    runCatching { Instant.parse(item.optString("t")).toEpochMilli() }.getOrNull()
                        ?.coerceAtMost(now)
                } else null
                out.add(TrailPoint(pLat, pLon, t))
            }
            return out
        }
    }

    private val _removedThreats = MutableSharedFlow<ThreatRemoved>(extraBufferCapacity = 16)
    val removedThreats: SharedFlow<ThreatRemoved> = _removedThreats.asSharedFlow()

    @Volatile private var alertsPendingClear: List<OblastAlert>? = null
    @Volatile private var alertsPendingClearSinceMono: Long? = null

    /**
     * Handles an incoming text frame from WebSocket.
     */
    fun handleFrame(text: String, nowWall: Long = System.currentTimeMillis(), nowMono: Long = Monotonic.now()) {
        try {
            val env = JSONObject(text)
            core.onStreamFrameReceived()

            val frameType = env.optString("type")
            when (frameType) {
                "snapshot" -> {
                    val data = env.optJSONObject("data") ?: return
                    val arr = data.optJSONArray("threats") ?: return
                    val list = mutableListOf<NormalizedThreat>()
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val t = parseNormalizedThreat(obj, nowWall) ?: continue
                            list.add(t)
                        } catch (e: Exception) {
                            Log.w(TAG, "Malformed threat in snapshot at index $i", e)
                        }
                    }
                    core.updateThreats(list)
                }
                "upsert" -> {
                    val data = env.optJSONObject("data") ?: return
                    val t = parseNormalizedThreat(data, nowWall) ?: return
                    if (t.status == "resolved") {
                        core.removeThreat(t.id)
                        _removedThreats.tryEmit(
                            ThreatRemoved(
                                t.id, t.lat, t.lon, t.type.toThreatType(),
                                t.bearingDeg ?: t.heading ?: fallbackCourse(t.id),
                                t.region, t.district, t.locality
                            )
                        )
                    } else {
                        core.upsertThreat(t)
                    }
                }
                "remove" -> {
                    val data = env.optJSONObject("data") ?: return
                    val id = data.optString("id")
                    if (id.isNotBlank()) {
                        val existing = core.threats.value.firstOrNull { it.id == id }
                        core.removeThreat(id)
                        if (existing != null) {
                            _removedThreats.tryEmit(
                                ThreatRemoved(
                                    existing.id, existing.lat, existing.lon, existing.type.toThreatType(),
                                    existing.bearingDeg ?: existing.heading ?: fallbackCourse(existing.id),
                                    existing.region, existing.district, existing.locality
                                )
                            )
                        }
                    }
                }
                "alerts" -> {
                    val dataObj = env.optJSONObject("data")
                    val dataArr = env.optJSONArray("data")
                        ?: env.optJSONArray("alerts")
                        ?: dataObj?.optJSONArray("alerts")
                    val hasAlertPayload = dataArr != null ||
                        dataObj?.let {
                            it.optJSONArray("raions") != null ||
                                it.optJSONArray("oblasts") != null ||
                                it.optJSONArray("alerts") != null
                        } == true
                    if (!hasAlertPayload) return

                    val list = mutableListOf<OblastAlert>()

                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            try {
                                val o = dataArr.getJSONObject(i)
                                val key = o.optString("key", o.optString("name", "")).trim()
                                if (key.isEmpty()) continue
                                val name = o.optString("name", key).trim()
                                val oblast = o.optString("oblast", name).trim()
                                val since = if (o.has("since") && !o.isNull("since")) o.optString("since") else null
                                val level = o.optString("level", "red").trim().lowercase().ifEmpty { "red" }
                                val wide = when {
                                    o.has("wide") -> o.optBoolean("wide", false)
                                    o.has("isOblastWide") -> o.optBoolean("isOblastWide", false)
                                    else -> null
                                }
                                list.add(OblastAlert(key = key, name = name, oblast = oblast, since = since, wide = wide, level = level))
                            } catch (e: Exception) {
                                Log.w(TAG, "Malformed alert at index $i", e)
                            }
                        }
                    } else if (dataObj != null) {
                        val wideByArray = mapOf("raions" to false, "oblasts" to true)
                        for ((arrName, wide) in wideByArray) {
                            val arr = dataObj.optJSONArray(arrName) ?: continue
                            for (i in 0 until arr.length()) {
                                try {
                                    val o = arr.getJSONObject(i)
                                    val key = o.optString("key", o.optString("name", "")).trim()
                                    if (key.isEmpty()) continue
                                    val name = o.optString("name", key).trim()
                                    val oblast = o.optString("oblast", name).trim()
                                    val since = if (o.has("since") && !o.isNull("since")) o.optString("since") else null
                                    val level = o.optString("level", "red").trim().lowercase().ifEmpty { "red" }
                                    list.add(OblastAlert(key = key, name = name, oblast = oblast, since = since, wide = wide, level = level))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Malformed alert at index $i", e)
                                }
                            }
                        }
                    } else {
                        return
                    }

                    if (list.isEmpty()) {
                        if (alertsPendingClear == null && core.alerts.value.isNotEmpty()) {
                            alertsPendingClear = core.alerts.value
                            alertsPendingClearSinceMono = nowMono
                        }
                        flushPendingAlertClear(nowMono)
                    } else {
                        alertsPendingClear = null
                        alertsPendingClearSinceMono = null
                        core.updateAlerts(list)
                    }
                }
                "heartbeat" -> {
                    // Handled implicitly by onStreamFrameReceived
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stream frame", e)
        }
    }

    fun flushPendingAlertClear(nowMono: Long = Monotonic.now()) {
        val pending = alertsPendingClear ?: return
        val since = alertsPendingClearSinceMono ?: return
        if (nowMono - since >= ALERT_CLEAR_CONFIRM_MS) {
            alertsPendingClear = null
            alertsPendingClearSinceMono = null
            core.updateAlerts(emptyList())
        }
    }

    fun handleTransportDrop() {
        alertsPendingClear = null
        alertsPendingClearSinceMono = null
    }
}
