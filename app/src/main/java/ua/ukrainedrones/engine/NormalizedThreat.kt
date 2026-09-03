package ua.ukrainedrones.engine

import androidx.compose.runtime.Immutable

@Immutable
data class LatLng(val lat: Double, val lon: Double)

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
    val sourceMeta: Map<String, Any> = emptyMap()
) {
    val flying: Boolean
        get() = bearingDeg != null && confirmedAtMillis != null && status == "active"
}

fun fallbackCourse(id: String): Double {
    var t = 0
    for (ch in id) t = (t + ch.code) % 360
    return if (t == 0) 45.0 else t.toDouble()
}
