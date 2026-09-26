package com.presaince.oko

import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.engine.BoundingBox
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.resolveOblastId
import com.presaince.oko.engine.distanceFlat
import com.presaince.oko.engine.matchOblast

import androidx.compose.runtime.Immutable
data class FlourishRecord(
    val lat: Double,
    val lon: Double,
    val type: ThreatType,
    val region: String? = null
)

/** One-shot replay show: the remembered resolutions to shoot down, in arrival order. */
@Immutable
data class FlourishShow(
    val tick: Int,
    val records: List<FlourishRecord>
)

/** One-shot fake shootdown greeting the user right after the first-run wizard: a synthetic
 *  SHAHED near the focus, never tied to a real threat. Pure flourish, once per install. */
@Immutable
data class WelcomeShootdown(
    val tick: Int,
    val lat: Double,
    val lon: Double
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
    val totalRecords: Int,
    val groupType: ThreatType? = null
) {
    /** Overall show completion 0f..1f for the footer progress bar. */
    val fraction: Float
        get() = if (totalRecords <= 0) 0f else bulletOverall.coerceAtMost(totalRecords) / totalRecords.toFloat()
}

/** Floor for the reveal frame's lat/lon span — stops over-zoom on a very close threat. */
const val REVEAL_MIN_SPAN_LAT = 0.15
const val REVEAL_MIN_SPAN_LON = 0.22

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
    val marginLat = spanLat * 0.25
    val marginLon = spanLon * 0.25
    val latMid = (maxLat + minLat) / 2
    val lonMid = (maxLon + minLon) / 2
    return BoundingBox(
        (latMid + spanLat / 2 + marginLat).coerceAtMost(85.0), lonMid + spanLon / 2 + marginLon,
        (latMid - spanLat / 2 - marginLat).coerceAtLeast(-85.0), lonMid - spanLon / 2 - marginLon
    )
}

/** Same span/margin math as [flourishesBoundingBox], but over an explicit extent. */
internal fun boundingBoxFromExtent(
    minLat: Double,
    maxLat: Double,
    minLon: Double,
    maxLon: Double
): BoundingBox {
    val spanLat = maxOf(maxLat - minLat, REVEAL_MIN_SPAN_LAT)
    val spanLon = maxOf(maxLon - minLon, REVEAL_MIN_SPAN_LON)
    val marginLat = spanLat * 0.25
    val marginLon = spanLon * 0.25
    val latMid = (maxLat + minLat) / 2
    val lonMid = (maxLon + minLon) / 2
    return BoundingBox(
        (latMid + spanLat / 2 + marginLat).coerceAtMost(85.0), lonMid + spanLon / 2 + marginLon,
        (latMid - spanLat / 2 - marginLat).coerceAtLeast(-85.0), lonMid - spanLon / 2 - marginLon
    )
}

/**
 * Canonical oblast key for a record — the same rule [clusterFlourishByOblast] uses to group
 * (region text → canonical stem, else nearest-city geo lookup, else "other").
 */
internal fun flourishOblastKey(r: FlourishRecord): String =
    r.region?.let { resolveOblastId(it) } ?: matchOblast(r.lat, r.lon)?.id ?: "other"

/**
 * Zoom target for an oblast group: the whole oblast extent when its boundary is known (so the
 * camera frames the entire region, not just the remembered threat points), else the group's own
 * spread. Used by the tally-tap replay in All-of-Ukraine mode.
 */
internal fun flourishGroupBoundingBox(group: List<FlourishRecord>): BoundingBox {
    if (group.isEmpty()) return flourishesBoundingBox(emptyList(), null)
    val first = group.first()
    val polygon = CompactOblastBoundaries.get(flourishOblastKey(first))
    if (polygon != null) {
        polygon.boundingBox()?.let { b ->
            return boundingBoxFromExtent(b.minLat, b.maxLat, b.minLon, b.maxLon)
        }
    }
    return flourishesBoundingBox(group, null)
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

/**
 * Oblast-based clustering for the replay flourish: group records by the canonical stem of
 * their server region text (falling back to a nearest-city geo lookup), used in All-of-Ukraine
 * mode so each oblast plays as one group instead of scattering single-threat groups. Deterministic
 * (arrival order preserved); a missing region goes to a trailing "other" bucket.
 */
internal fun clusterFlourishByOblast(records: List<FlourishRecord>): List<List<FlourishRecord>> {
    val groups = linkedMapOf<String, MutableList<FlourishRecord>>()
    for (r in records) {
        val key = flourishOblastKey(r)
        groups.getOrPut(key) { mutableListOf() }.add(r)
    }
    return groups.values.toList()
}

internal fun flourishGroupType(group: List<FlourishRecord>): ThreatType? {
    val types = group.map { it.type }.toSet()
    return if (types.size == 1) types.first() else null
}

/** When a selected threat vanishes it shows the compact "shot-down" card and drops the *  selection only while the death animation is on and the map is the visible screen —
 *  shelters no longer block morale (see plan: full removal). */
object FlourishPolicy {
    /** The selection should be dropped (card self-destructs) once the threat is gone and the
     *  death animation is enabled. */
    fun dropSelection(selectedGone: Boolean, animOn: Boolean): Boolean = selectedGone && animOn

    /** The neutralized card itself is shown only when the map can actually play the flourish. */
    fun showNeutralizedCard(
        selectedGone: Boolean,
        animOn: Boolean,
        mapVisible: Boolean
    ): Boolean = selectedGone && animOn && mapVisible

    @Deprecated("Use 3-arg overload", ReplaceWith("showNeutralizedCard(selectedGone, animOn, mapVisible)"))
    fun showNeutralizedCard(
        selectedGone: Boolean,
        animOn: Boolean,
        mapVisible: Boolean,
        @Suppress("UNUSED_PARAMETER") shelterModeActive: Boolean
    ): Boolean = showNeutralizedCard(selectedGone, animOn, mapVisible)
}

/**
 * Decode a flourish tap's baked extras back into replay records. Pure (no Intent) so it
 * unit-tests without Robolectric; MainActivity calls it after pulling the raw arrays out.
 */
internal fun parseFlourishRecords(
    lats: DoubleArray,
    lons: DoubleArray,
    types: Array<String>,
    regions: Array<out String?>?
): List<FlourishRecord> {
    val n = minOf(lats.size, lons.size, types.size)
    return buildList {
        for (i in 0 until n) {
            val lat = lats[i]
            val lon = lons[i]
            if (!lat.isFinite() || !lon.isFinite() ||
                lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0
            ) continue
            val type = runCatching { ThreatType.valueOf(types[i]) }.getOrNull() ?: continue
            add(FlourishRecord(lat, lon, type, regions?.getOrNull(i)))
        }
    }
}

/**
 * Selective reset routing for a consumed flourish tap: only the store whose show was watched
 * is cleared, so watching the running tally never wipes the episode memory and vice versa.
 * A missing source (notifications posted before this tag existed) resets both, as before.
 */
internal fun flourishResetTally(source: String?): Boolean =
    source != NeutralizedTally.SOURCE_EPISODE && source != NeutralizedTally.SOURCE_ALLCLEAR

internal fun flourishResetEpisode(source: String?): Boolean =
    source != NeutralizedTally.SOURCE_TALLY