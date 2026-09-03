package ua.ukrainedrones

import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.TrailPoint
import ua.ukrainedrones.engine.toEngineString

/** Shared builder for a default active NormalizedThreat so tests stay short. */
fun threat(
    id: String = "t1",
    type: ThreatType = ThreatType.SHAHED,
    title: String = "",
    region: String? = "Одеська",
    district: String? = null,
    locality: String? = null,
    lat: Double = 46.48,
    lon: Double = 30.73,
    heading: Double? = null,
    bearingDeg: Double? = null,
    status: String = "active",
    advisory: Boolean = false,
    areaOnly: Boolean = false,
    confirmations: Int = 1,
    reliability: String = "MEDIUM",
    count: Int = 0,
    explanationShort: String? = null,
    speedKmh: Double? = null,
    uncertaintyKm: Double? = null,
    positionQuality: String? = "confirmed",
    confirmedAtMillis: Long? = null,
    updatedAtMillis: Long? = null,
    trail: List<TrailPoint> = emptyList()
): NormalizedThreat = NormalizedThreat(
    id = id,
    type = type.toEngineString(),
    title = title,
    region = region,
    district = district,
    locality = locality,
    lat = lat,
    lon = lon,
    heading = heading,
    bearingDeg = bearingDeg,
    status = status,
    advisory = advisory,
    areaOnly = areaOnly,
    confirmations = confirmations,
    reliability = reliability,
    count = count,
    explanationShort = explanationShort,
    speedKmh = speedKmh,
    uncertaintyKm = uncertaintyKm,
    positionQuality = positionQuality,
    confirmedAtMillis = confirmedAtMillis,
    updatedAtMillis = updatedAtMillis,
    trail = trail
)