package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.distanceFlat

import androidx.compose.runtime.Immutable
import org.osmdroid.util.BoundingBox

/** A resolved threat remembered by the service for the tally-tap replay flourish. */
@Immutable
data class FlourishRecord(val lat: Double, val lon: Double, val type: ThreatType)

/** One-shot replay show: the remembered resolutions to shoot down, in arrival order. */
@Immutable
data class FlourishShow(
    val tick: Int,
    val records: List<FlourishRecord>
)

/** Gap between consecutive bullets in the tally-tap replay flourish. */
const val FLOURISH_STAGGER_MS = 420L

/**
 * Replay playback position, emitted per bullet by the controller: [groupSize] drives the
 * footer's "Resolving N threats" copy (per group, never a global total), while
 * [bulletOverall]/[totalRecords] drives the footer's overall progress bar.
 */
@Immutable
data class ReplayProgress(
    val bulletInGroup: Int,
    val groupSize: Int,
    val bulletOverall: Int,
    val totalRecords: Int
) {
    /** Overall show completion 0f..1f for the footer progress bar. */
    val fraction: Float
        get() = if (totalRecords <= 0) 0f else bulletOverall.coerceAtMost(totalRecords) / totalRecords.toFloat()
}

/** Floor for the reveal frame's lat/lon span — stops over-zoom on a very close threat. */
const val REVEAL_MIN_SPAN_LAT = 0.10
const val REVEAL_MIN_SPAN_LON = 0.16

/** Bounding box over every resolution in the replay flourish (plus the focus) so a single
 *  zoom-out shows the whole show at once — never pans per bullet. Adds a margin so threats on
 *  the edge of the zoom aren't clipped by the screen border. */
internal fun flourishesBoundingBox(records: List<FlourishRecord>, focus: LatLng?): BoundingBox {
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    for (r in records) {
        minLat = minOf(minLat, r.lat); maxLat = maxOf(maxLat, r.lat)
        minLon = minOf(minLon, r.lon); maxLon = maxOf(maxLon, r.lon)
    }
    focus?.let {
        minLat = minOf(minLat, it.lat); maxLat = maxOf(maxLat, it.lat)
        minLon = minOf(minLon, it.lon); maxLon = maxOf(maxLon, it.lon)
    }
    val spanLat = maxOf(maxLat - minLat, REVEAL_MIN_SPAN_LAT)
    val spanLon = maxOf(maxLon - minLon, REVEAL_MIN_SPAN_LON)
    val marginLat = spanLat * 0.15
    val marginLon = spanLon * 0.15
    val latMid = (maxLat + minLat) / 2
    val lonMid = (maxLon + minLon) / 2
    return BoundingBox(
        (latMid + spanLat / 2 + marginLat).coerceAtMost(85.0), lonMid + spanLon / 2 + marginLon,
        (latMid - spanLat / 2 - marginLat).coerceAtLeast(-85.0), lonMid - spanLon / 2 - marginLon
    )
}

/**
 * Greedy spatial clustering of a replay flourish into groups, so the camera can zoom onto each
 * group in turn instead of one over-wide fit. A record joins a group when it is within
 * [maxDistanceMeters] of that group's centroid (recomputed as it grows). Deterministic (input
 * order preserved), pure, and cheap for the ≤21-record memory cap.
 */
internal fun clusterFlourish(
    records: List<FlourishRecord>,
    maxDistanceMeters: Double
): List<List<FlourishRecord>> {
    val groups = mutableListOf<MutableList<FlourishRecord>>()
    for (r in records) {
        var placed = false
        for (g in groups) {
            val cLat = g.map { it.lat }.average()
            val cLon = g.map { it.lon }.average()
            if (distanceFlat(cLat, cLon, r.lat, r.lon) <= maxDistanceMeters) {
                g.add(r)
                placed = true
                break
            }
        }
        if (!placed) groups.add(mutableListOf(r))
    }
    return groups
}

/** When a selected threat vanishes it shows the compact "shot-down" card and drops the
 *  selection only while the death animation is on, the map is the visible screen and the
 *  shelter overlay is down — nothing animates anywhere when the flourish is off. */
object FlourishPolicy {
    /** The selection should be dropped (card self-destructs) once the threat is gone and the
     *  death animation is enabled. */
    fun dropSelection(selectedGone: Boolean, animOn: Boolean): Boolean = selectedGone && animOn

    /** The neutralized card itself is shown only when the map can actually play the flourish. */
    fun showNeutralizedCard(
        selectedGone: Boolean,
        animOn: Boolean,
        mapVisible: Boolean,
        shelterModeActive: Boolean
    ): Boolean = selectedGone && animOn && mapVisible && !shelterModeActive
}