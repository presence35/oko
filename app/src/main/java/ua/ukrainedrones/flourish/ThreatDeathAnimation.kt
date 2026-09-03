package ua.ukrainedrones

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Total length of the death animation: projectile flight + explosion. */
private const val DEATH_DURATION_MS = 5000L

/** The projectile hits and the explosion begins this many ms into the animation (1.5s flight). */
const val DEATH_EXPLOSION_START_MS = 2000L

/** Explosion window ends this many ms after it starts (5.0s - 2.0s); the neutralized card
 *  fades out across it. */
const val DEATH_EXPLOSION_LEN_MS = DEATH_DURATION_MS - DEATH_EXPLOSION_START_MS

/** Compressed explosion for quick-boom deaths (intermediate replay groups): a brief flash
 *  after impact, then gone — the show pans on instead of lingering through every burst. */
private const val QUICK_EXPLOSION_LEN_MS = 800L

/** Concurrent-death ceiling. Pre-spawning a whole replay group (≤21 targets) while the
 *  previous group's flashes are still fading needs real headroom — the old hard-coded 6
 *  silently ate bullets mid-show. */
private const val MAX_DEATHS = 32

/**
 * A dying threat. [icon] is the marker's own drawable, so the icon keeps rendering here through
 * the bullet flight and vanishes the instant the explosion starts (the threat re-draws on the
 * next overlay rebuild). [origin] is where the projectile takes off from — the nearest major
 * city to the target (else your GPS position / pinned city) at spawn time; null skips the
 * flight visuals.
 *
 * A [dud] carries no icon and never explodes: it's a follow-up projectile fired when the
 * threat turned out to be already destroyed (e.g. the server re-sent the resolution), so it
 * streaks past the old position and off-screen, then is dropped from memory.
 */
private class ActiveDeath(
    val id: String?,
    val geo: GeoPoint,
    /** Take-off point for the pending flight. Mutable: pre-spawned replay targets re-base it
     *  to the new viewport's edge once the camera lands ([rebasePendingOrigins]). */
    var origin: GeoPoint?,
    /** When the bullet FIRES (epochRealtime). Pre-spawned replay targets use a future start:
     *  until then only the icon renders — the threat already stands there when the camera
     *  lands, so nothing appears out of nowhere. */
    val start: Long,
    val icon: Drawable?,
    val rotationDeg: Float,
    val alpha: Float,
    val dud: Boolean,
    /** Total lifetime; quick-boom deaths (replay intermediates) compress the explosion. */
    val durationMs: Long = DEATH_DURATION_MS
)

/**
 * Playful "neutralized" flourish drawn on the map at a threat's last position: a small
 * projectile always enters from just off the screen edge, along the line from the nearest
 * major city (else your GPS position or pinned city) through the target, and explodes on
 * impact. Rendered as an osmdroid overlay so the map's own
 * projection places it exactly at the geo points (tracking pan/zoom) and `draw()` is re-invoked
 * on every invalidate — a per-frame ticker in the map view keeps it animating for
 * [DEATH_DURATION_MS].
 */
class ThreatDeathOverlay : Overlay() {

    private val deaths = mutableListOf<ActiveDeath>()

    private val _active = MutableStateFlow(false)
    /** True while at least one bullet/explosion is on screen — the footer can swap its copy. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive: Boolean get() = deaths.isNotEmpty()

    private fun syncActive() {
        val nowActive = deaths.isNotEmpty()
        if (_active.value != nowActive) _active.value = nowActive
    }

    /** Whether a death animation is already in flight for [id]. */
    fun isActiveFor(id: String?): Boolean = id != null && deaths.any { it.id == id }

    /** Re-point every not-yet-fired bullet's take-off edge via [newOrigin]. Called right after
     *  a camera jump so pre-spawned flights enter from the CURRENT screen edge (their edge
     *  was picked on whatever viewport was up when they were pre-spawned). */
    fun rebasePendingOrigins(newOrigin: () -> GeoPoint?) {
        val now = SystemClock.elapsedRealtime()
        for (d in deaths) {
            if (now < d.start) d.origin = newOrigin()
        }
    }

    fun spawn(
        id: String? = null,
        geo: GeoPoint,
        origin: GeoPoint? = null,
        icon: Drawable? = null,
        rotationDeg: Float = 0f,
        alpha: Float = 1f,
        quickBoom: Boolean = false,
        fireAtDelayMs: Long = 0L
    ) {
        if (deaths.size >= MAX_DEATHS) return
        deaths.add(
            ActiveDeath(
                id, geo, origin, SystemClock.elapsedRealtime() + fireAtDelayMs.coerceAtLeast(0L),
                icon, rotationDeg, alpha, dud = false,
                // Quick boom: impact + a brief flash — used for intermediate replay groups so the
                // show pans on right after the hits instead of lingering through every explosion.
                durationMs = if (quickBoom) DEATH_EXPLOSION_START_MS + QUICK_EXPLOSION_LEN_MS
                             else DEATH_DURATION_MS
            )
        )
        syncActive()
    }

    /** Follow-up projectile for an already-destroyed threat: no icon, never explodes, just
     *  flies through and off-screen. Without an [origin] there's nothing to fly, so skip. */
    fun spawnDud(id: String?, geo: GeoPoint, origin: GeoPoint?) {
        if (origin == null || deaths.size >= MAX_DEATHS) return
        deaths.add(
            ActiveDeath(id, geo, origin, SystemClock.elapsedRealtime(), null, 0f, 1f, dud = true)
        )
        syncActive()
    }

    /** Drop every active death instantly — used when a red alert ejects the flourish. */
    fun clear() {
        deaths.clear()
        syncActive()
    }

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val flashPaint = Paint().apply { isAntiAlias = true }
    private val sparkPaint = Paint().apply { isAntiAlias = true }
    private val bulletPaint = Paint().apply { isAntiAlias = true }
    // Explosion glow, pre-rendered once per density instead of allocating a RadialGradient
    // every frame per exploding death (the old per-frame shader churn was the jank source).
    private var glowBitmap: Bitmap? = null
    private val reuse = android.graphics.Point()
    private val reuseOrigin = android.graphics.Point()
    private val reuseRect = RectF()

    private var bulletBitmap: Bitmap? = null

    /** White-hot core -> amber -> transparent radial glow, rendered once and scaled per frame. */
    private fun explosionGlow(density: Float): Bitmap {
        glowBitmap?.let { return it }
        val size = (96 * density).toInt().coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = size / 2f
        c.drawCircle(half, half, half, Paint().apply {
            isAntiAlias = true
            shader = RadialGradient(
                half, half, half,
                intArrayOf(
                    Color.argb(255, 255, 229, 127),
                    Color.argb(200, 255, 152, 0),
                    Color.argb(0, 255, 152, 0)
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        })
        glowBitmap = bmp
        return bmp
    }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (deaths.isEmpty()) return
        val density = mapView.context.resources.displayMetrics.density
        val now = SystemClock.elapsedRealtime()

        deaths.removeAll {
            val elapsed = now - it.start
            elapsed > it.durationMs || (it.dud && elapsed > DEATH_EXPLOSION_START_MS)
        }
        if (deaths.isEmpty()) {
            syncActive()
            return
        }

                for (d in deaths) {
            // Pre-fired target (bullet not launched yet): only the icon renders, standing at
            // the geo position — so pre-spawned replay targets are already in place when the
            // camera lands instead of popping up as their bullet fires.
            val rawElapsed = now - d.start
            if (rawElapsed < 0) {
                d.icon?.let { icon ->
                    val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
                    val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
                    icon.alpha = (d.alpha * 255).toInt()
                    mapView.projection.toPixels(d.geo, reuse)
                    canvas.save()
                    canvas.translate(reuse.x.toFloat(), reuse.y.toFloat())
                    canvas.rotate(d.rotationDeg)
                    icon.setBounds(-w.toInt(), -h.toInt(), w.toInt(), h.toInt())
                    icon.draw(canvas)
                    canvas.restore()
                }
                continue
            }
            // Per-death timeline: quick-boom deaths compress the explosion window, so boomT
            // and the fade curve are derived from each death's own duration.
            val dur = d.durationMs.toFloat()
            val boomT = DEATH_EXPLOSION_START_MS / dur
            val boomLenT = (d.durationMs - DEATH_EXPLOSION_START_MS) / dur
            mapView.projection.toPixels(d.geo, reuse)
            val x = reuse.x.toFloat()
            val y = reuse.y.toFloat()
            val t = (rawElapsed.toFloat() / dur).coerceIn(0f, 1f)

            // The threat's own icon lingers through the bullet flight, then vanishes the instant
            // the explosion starts — no fading away, the detonation replaces it.
            d.icon?.let { icon ->
                val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
                val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
                val fade = if (t >= boomT) 0f else 1f
                icon.alpha = (d.alpha * fade * 255).toInt()
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(d.rotationDeg)
                icon.setBounds(-w.toInt(), -h.toInt(), w.toInt(), h.toInt())
                icon.draw(canvas)
                canvas.restore()
            }

            // Flight (impact): a small projectile always enters from just off the screen edge,
            // along the line from the origin (a random point on the viewport edge, clamped to
            // Ukraine — so it can never take off from "another country") through the target —
            // it glides in from the edge and detonates on impact, which lands at p=1 exactly
            // when the explosion below starts.
            if (t in 0f..boomT && d.origin != null) {
                mapView.projection.toPixels(d.origin, reuseOrigin)
                val ox = reuseOrigin.x.toFloat()
                val oy = reuseOrigin.y.toFloat()
                val dx = x - ox
                val dy = y - oy
                val dist = sqrt(dx * dx + dy * dy)
                val p = (t / boomT).coerceIn(0f, 1f)
                if (dist > 1f) {
                    val W = mapView.width.toFloat()
                    val H = mapView.height.toFloat()
                    // Slab test: where the line origin→threat crosses the viewport rectangle.
                    // When the origin is on-screen tnear is negative (the edge behind it), so
                    // the bullet still crosses the whole viewport toward the target.
                    var tnear = -Float.MAX_VALUE
                    var tfar = Float.MAX_VALUE
                    val t1x = -ox / dx
                    val t2x = (W - ox) / dx
                    tnear = maxOf(tnear, minOf(t1x, t2x))
                    tfar = minOf(tfar, maxOf(t1x, t2x))
                    val t1y = -oy / dy
                    val t2y = (H - oy) / dy
                    tnear = maxOf(tnear, minOf(t1y, t2y))
                    tfar = minOf(tfar, maxOf(t1y, t2y))
                    if (tfar > 0f && tnear.isFinite()) {
                        // Start a little outside the edge so it glides in, never popping up
                        // mid-air (which happens when the GPS dot is far off-screen).
                        val inv = (10f * density) / dist
                        val sx = ox + dx * tnear - dx * inv
                        val sy = oy + dy * tnear - dy * inv
                        // A dud keeps going past the (already destroyed) target and exits the
                        // screen — extend the endpoint by the viewport diagonal so it always
                        // clears the edge regardless of pan/zoom.
                        val tx = if (d.dud) {
                            val diag = sqrt(W * W + H * H)
                            x + dx / dist * diag
                        } else x
                        val ty = if (d.dud) {
                            val diag = sqrt(W * W + H * H)
                            y + dy / dist * diag
                        } else y
                        val bx = sx + (tx - sx) * p
                        val by = sy + (ty - sy) * p
                        val headX = tx - sx
                        val headY = ty - sy
                        // Bullet: the projectile PNG, rotated to the heading.
                        canvas.save()
                        canvas.translate(bx, by)
                        canvas.rotate((Math.toDegrees(atan2(headY.toDouble(), headX.toDouble())) + 90).toFloat())
                        val bitmap = bulletBitmap ?: run {
                            BitmapFactory.decodeResource(mapView.context.resources, R.drawable.bullet)
                                ?.also { bulletBitmap = it }
                        }
                        if (bitmap != null) {
                            val longHalf = 11f * density
                            val bw = bitmap.width.toFloat()
                            val bh = bitmap.height.toFloat()
                            val scale = longHalf * 2f / max(bw, bh)
                            val hw = bw * scale / 2f
                            val hh = bh * scale / 2f
                            canvas.drawBitmap(
                                bitmap, null,
                                RectF(-hw, -hh, hw, hh),
                                bulletPaint
                            )
                        }
                        canvas.restore()
                    }
                }
            }

            // Explosion (impact-5.0s): radial burst, center flash, shockwave ring, sparks.
            // Duds never detonate — the projectile just exits and is pruned above.
            if (t >= boomT && !d.dud) {
                val e = ((t - boomT) / boomLenT).coerceIn(0f, 1f)
                val maxR = 46f * density
                val br = maxR * e
                val fade = 1f - e
                // Pre-rendered glow sprite scaled to the blast radius — no per-frame shader.
                val glow = explosionGlow(density)
                flashPaint.alpha = (255 * fade).toInt()
                reuseRect.set(x - br, y - br, x + br, y + br)
                canvas.drawBitmap(glow, null, reuseRect, flashPaint)
                flashPaint.alpha = 255
                flashPaint.color = Color.argb((230 * fade).toInt(), 255, 255, 255)
                canvas.drawCircle(x, y, br * 0.3f, flashPaint)
                ringPaint.color = Color.argb((255 * fade).toInt(), 255, 213, 0)
                ringPaint.strokeWidth = 2f * density
                canvas.drawCircle(x, y, maxR * (0.5f + 0.9f * e), ringPaint)
                sparkPaint.color = Color.argb((255 * fade).toInt(), 255, 193, 7)
                val sparkDist = maxR * (0.5f + 0.8f * e)
                val sparkR = 3f * density * fade
                repeat(8) { i ->
                    val a = 2.0 * PI * i / 8.0 + 0.4
                    canvas.drawCircle(
                        x + (cos(a) * sparkDist).toFloat(),
                        y + (sin(a) * sparkDist).toFloat(),
                        sparkR,
                        sparkPaint
                    )
                }
            }
        }
    }
}