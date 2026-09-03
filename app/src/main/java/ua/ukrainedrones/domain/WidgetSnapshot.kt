package ua.ukrainedrones

import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.connection.NeptunConnectionClient
import ua.ukrainedrones.connection.isDegraded
import ua.ukrainedrones.connection.isOffline
import ua.ukrainedrones.connection.offlineSinceOrNull
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.distanceFlat
import ua.ukrainedrones.engine.toThreatType
import kotlin.math.roundToInt

/**
 * A lightweight, serializable snapshot of the threat state for the home-screen widget.
 *
 * Pure and deterministic — computed from shared domain functions only, never by the widget
 * itself. The widget simply renders whatever it is handed, so the launcher process can never
 * re-derive or drift from the app's zone/alert logic (mirror rule: see ARCHITECTURE.md).
 */
data class WidgetSnapshot(
    val threatCount: Int = 0,
    val typeCounts: Map<ThreatType, Int> = emptyMap(),
    val activeZone: ThreatZone? = null,
    val nearestKm: Double? = null,
    val officialAlert: Boolean = false,
    val sourceOnline: Boolean = false,
    /** Connected but the stream is quiet (orange) — mirrors the app's degraded pill. */
    val sourceDegraded: Boolean = false,
    /** The nearest non-stale map-enabled threat (id + position), so the widget can highlight
     *  and reveal it — mirrors the footer strip's nearest-first semantics. */
    val primaryThreat: WidgetThreat? = null,
    val updatedAtMs: Long = 0L
) {
    companion object {
        /** Max distance (km) at which a threat is still shown as a rounded nearest value. */
        const val NEAREST_CAP_KM = 500.0
    }
}

/** Lightweight serializable threat reference for the widget's primary icon + tap-to-map. */
data class WidgetThreat(
    val id: String,
    val lat: Double,
    val lon: Double,
    val type: ThreatType
)

/**
 * Computes the widget snapshot from the shared domain state. Mirrors the footer-strip
 * semantics of the main UI: counts non-stale, non-resolved, map-enabled threats; the nearest
 * distance and zone derive from the focus point via the threat engine; the official-alert flag
 * matches the focus oblast via [focusAttribution] (majors-only, same as the app). No decision
 * logic lives in the widget layer.
 */
fun computeWidgetSnapshot(
    cs: ConnectionState,
    threats: Map<String, NormalizedThreat>,
    alerts: List<OblastAlert>,
    focus: LatLng?,
    token: String?,
    params: ZoneParams,
    mapEnabled: Set<ThreatType>,
    now: Long = System.currentTimeMillis()
): WidgetSnapshot {
    val threatList = threats.values
        .filter { it.type.toThreatType() in mapEnabled }
    val engine = ThreatEngine(NEPTUN_TYPES)
    val eval = engine.evaluate(
        threats = threatList,
        focus = focus,
        params = params,
        hiddenTypes = emptySet(),
        silencedTypes = emptySet(),
        now = now
    )

    var count = 0
    var nearestKm: Double? = null
    var primaryThreat: WidgetThreat? = null
    var nearestDist = Double.MAX_VALUE
    val typeCounts = LinkedHashMap<ThreatType, Int>()
    for (nt in eval.mapThreats) {
        if (engine.isStale(nt, engine.propsFor(nt.type), now)) continue
        count++
        typeCounts[nt.type.toThreatType()] = (typeCounts[nt.type.toThreatType()] ?: 0) + 1
        if (focus != null) {
            val d = distanceFlat(focus.lat, focus.lon, nt.lat, nt.lon) / 1000.0
            if (nearestKm == null || d < nearestKm) nearestKm = d
            if (d < nearestDist) {
                nearestDist = d
                primaryThreat = WidgetThreat(nt.id, nt.lat, nt.lon, nt.type.toThreatType())
            }
        } else if (primaryThreat == null) {
            primaryThreat = WidgetThreat(nt.id, nt.lat, nt.lon, nt.type.toThreatType())
        }
    }
    nearestKm = nearestKm?.let { it.coerceAtMost(WidgetSnapshot.NEAREST_CAP_KM).roundToInt().toDouble() }

    val officialAlert = token != null && alerts.any { it.inOblast(token) }

    // Online = the app-pill semantics: not down AND past the shared grace window, so short
    // socket blips (drops that recover inside OFFLINE_GRACE_MS) don't flicker the badge.
    val offline = cs.isOffline && (cs.offlineSinceOrNull == null ||
        now - cs.offlineSinceOrNull!! >= NeptunConnectionClient.OFFLINE_GRACE_MS)

    return WidgetSnapshot(
        threatCount = count,
        typeCounts = typeCounts,
        activeZone = eval.activeZone,
        nearestKm = nearestKm,
        officialAlert = officialAlert,
        sourceOnline = !offline,
        sourceDegraded = !offline && cs.isDegraded,
        primaryThreat = primaryThreat,
        updatedAtMs = now
    )
}
