package ua.ukrainedrones

import androidx.compose.runtime.Immutable
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.toThreatType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Full edge-to-edge crossing time — slow enough to savor a screen-wide jet. */
const val AVIATION_FLYBY_DURATION_MS = 4000L

/**
 * One screen-space pass of the full-size AVIATION icon across the viewport, played the moment
 * a MiG-31K takeoff alert rings (INNER tier). [courseDeg] is the airbase→focus bearing, so the
 * plane visibly flies "at you"; when the flyby lands the threat card opens on top.
 */
@Immutable
data class AviationFlybyShow(
    val tick: Long,
    val threatId: String,
    val courseDeg: Double,
    val durationMs: Long = AVIATION_FLYBY_DURATION_MS
)

/** Pure policy + geometry for the aviation flyby flourish. */
object AviationFlyby {

    /**
     * The threat whose takeoff should play the flyby next, or null. Only INNER-tier AVIATION
     * qualifies (evaluate() already dropped advisory/type-off entries), each id plays at most
     * once per process, and nothing plays while the map isn't the visible foreground screen.
     */
    fun nextShow(
        innerThreats: List<NormalizedThreat>,
        playedIds: Set<String>,
        visible: Boolean,
        tick: Long
    ): AviationFlybyShow? {
        if (!visible) return null
        val t = innerThreats.firstOrNull { it.type.toThreatType() == ThreatType.AVIATION && it.id !in playedIds }
            ?: return null
        // Pure spectacle: a fresh random bearing every pass — "to somewhere, who knows".
        val course = Random.nextDouble(0.0, 360.0)
        return AviationFlybyShow(tick, t.id, course)
    }

    /** Unit direction of travel in screen coords (x right, y down) for a compass [courseDeg]. */
    internal fun direction(courseDeg: Double): Pair<Double, Double> {
        val rad = Math.toRadians(courseDeg)
        return sin(rad) to -cos(rad)
    }

    /**
     * Sprite transform for a pass at [courseDeg] flown by art whose true facing is
     * [facingDeg]: `(rotationZ degrees, vertical flip)`. The flip keeps the jet upright when
     * the pass would invert it — and because mirroring changes the art's effective facing
     * (β → 180−β), the rotation compensates for that too, so the nose always tracks the
     * flight path exactly.
     */
    fun spriteTransform(courseDeg: Float, facingDeg: Float): Pair<Float, Boolean> {
        val diff = ((courseDeg - facingDeg) % 360f + 360f) % 360f
        val flipped = diff >= 90f && diff <= 270f
        val rotZ = if (flipped) courseDeg - (180f - facingDeg) else courseDeg - facingDeg
        return rotZ to flipped
    }

    /**
     * Entry/exit points where a line through the viewport center along [courseDeg] crosses the
     * screen edges, inflated by [iconPx]/2 so the icon starts and ends fully off-screen.
     */
    fun endpoints(
        courseDeg: Double,
        widthPx: Float,
        heightPx: Float,
        iconPx: Float
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val (dx, dy) = direction(courseDeg)
        val tx = if (abs(dx) < 1e-9) Double.MAX_VALUE else (widthPx / 2f + iconPx / 2f) / abs(dx)
        val ty = if (abs(dy) < 1e-9) Double.MAX_VALUE else (heightPx / 2f + iconPx / 2f) / abs(dy)
        val t = minOf(tx, ty)
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val exit = (cx + t * dx).toFloat() to (cy + t * dy).toFloat()
        val entry = (cx - t * dx).toFloat() to (cy - t * dy).toFloat()
        return entry to exit
    }
}
