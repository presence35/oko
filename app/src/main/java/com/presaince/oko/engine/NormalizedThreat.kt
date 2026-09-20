package com.presaince.oko.engine

import androidx.compose.runtime.Immutable

@Immutable
data class LatLng(val lat: Double, val lon: Double)

@Immutable
data class BoundingBox(
    val latNorth: Double,
    val lonEast: Double,
    val latSouth: Double,
    val lonWest: Double
) {
    val north: Double get() = latNorth
    val east: Double get() = lonEast
    val south: Double get() = latSouth
    val west: Double get() = lonWest

    val maxLat: Double get() = latNorth
    val maxLon: Double get() = lonEast
    val minLat: Double get() = latSouth
    val minLon: Double get() = lonWest
}

@Immutable
data class TrailPoint(val lat: Double, val lon: Double, val tMillis: Long?)

data class NormalizedThreat(
    val id: String,
    val type: String,
    val title: String,
    val region: String?,
    val district: String?,
    val locality: String?,
    val lat: Double,
    val lon: Double,
    val heading: Double?,
    val bearingDeg: Double?,
    val status: String,
    val advisory: Boolean,
    val areaOnly: Boolean,
    val confirmations: Int,
    val reliability: String,
    val count: Int,
    val explanationShort: String?,
    val speedKmh: Double?,
    val uncertaintyKm: Double?,
    val positionQuality: String?,
    val confirmedAtMillis: Long?,
    val updatedAtMillis: Long?,
    val trail: List<TrailPoint>,
    val sourceMeta: Map<String, Any> = emptyMap(),
    /** True when this track is emitted by a simulator (Test source), not a live feed — the UI
     *  watermarks it so a fake threat is never mistaken for a real one. Pure metadata: the engine
     *  treats simulated and live threats identically. */
    val simulated: Boolean = false
) {
    /** A track is dead-reckonable when the source gave it a course (authoritative velocity
     *  `bearingDeg` or reported `heading`) and an anchor (`confirmedAt`, or `updatedAt` when the
     *  confirmation time is missing). Movement additionally needs a speed and, in [predictPosition],
     *  a heading the engine can resolve — it never fabricates a course for a source that reports none. */
    val flying: Boolean
        get() = (bearingDeg != null || heading != null) &&
            (confirmedAtMillis != null || updatedAtMillis != null) &&
            status == "active"
}

fun fallbackCourse(id: String): Double {
    var t = 0
    for (ch in id) t = (t + ch.code) % 360
    return if (t == 0) 45.0 else t.toDouble()
}
