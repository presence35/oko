package ua.ukrainedrones

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
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

private data class Shard(
    val dx: Float, val dy: Float,
    val spin: Float,
    val sx: Float, val sy: Float,
    val sw: Float, val sh: Float,
    val sizeMul: Float = 1f
)

private enum class ExplosionKind {
    SHAHED, BALLISTIC, CRUISE, FPV, AVIATION, KAB, RECON, UNKNOWN;

    companion object {
        fun fromType(type: ThreatType): ExplosionKind = when (type) {
            ThreatType.SHAHED -> SHAHED
            ThreatType.BALLISTIC -> BALLISTIC
            ThreatType.CRUISE_MISSILE -> CRUISE
            ThreatType.FPV_LOITERING -> FPV
            ThreatType.AVIATION -> AVIATION
            ThreatType.KAB -> KAB
            ThreatType.RECON -> RECON
            ThreatType.UNKNOWN -> UNKNOWN
        }
    }
}

private class ActiveDeath(
    val id: String?,
    val geo: GeoPoint,
    var origin: GeoPoint?,
    val start: Long,
    val icon: Drawable?,
    val rotationDeg: Float,
    val alpha: Float,
    val dud: Boolean,
    val durationMs: Long = DEATH_DURATION_MS,
    val kind: ExplosionKind = ExplosionKind.SHAHED
) {
    var shards: Array<Shard>? = null
}

class ThreatDeathOverlay : Overlay() {

    private val deaths = mutableListOf<ActiveDeath>()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive: Boolean get() = deaths.isNotEmpty()

    var highQuality: Boolean = true

    private fun syncActive() {
        val nowActive = deaths.isNotEmpty()
        if (_active.value != nowActive) _active.value = nowActive
    }

    fun isActiveFor(id: String?): Boolean = id != null && deaths.any { it.id == id }

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
        fireAtDelayMs: Long = 0L,
        type: ThreatType = ThreatType.UNKNOWN
    ) {
        if (deaths.size >= MAX_DEATHS) return
        deaths.add(
            ActiveDeath(
                id, geo, origin, SystemClock.elapsedRealtime() + fireAtDelayMs.coerceAtLeast(0L),
                icon, rotationDeg, alpha, dud = false,
                durationMs = if (quickBoom) DEATH_EXPLOSION_START_MS + QUICK_EXPLOSION_LEN_MS
                             else DEATH_DURATION_MS,
                kind = ExplosionKind.fromType(type)
            )
        )
        syncActive()
    }

    fun spawnDud(id: String?, geo: GeoPoint, origin: GeoPoint?) {
        if (origin == null || deaths.size >= MAX_DEATHS) return
        deaths.add(
            ActiveDeath(id, geo, origin, SystemClock.elapsedRealtime(), null, 0f, 1f, dud = true)
        )
        syncActive()
    }

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
    private var glowBitmap: Bitmap? = null
    private val reuse = android.graphics.Point()
    private val reuseOrigin = android.graphics.Point()
    private val reuseRect = RectF()

    private var bulletBitmap: Bitmap? = null

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

    private fun createShards(kind: ExplosionKind, density: Float, hq: Boolean, seed: Int): Array<Shard> {
        val rnd = kotlin.random.Random(seed)
        val count = when {
            hq && kind == ExplosionKind.AVIATION -> 12
            hq -> 10
            kind == ExplosionKind.FPV -> 7
            else -> 8
        }
        return Array(count) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val baseSpeed = when (kind) {
                ExplosionKind.BALLISTIC -> 0.14f
                ExplosionKind.FPV -> 0.16f
                ExplosionKind.AVIATION -> 0.11f
                else -> 0.10f
            }
            val speed = (baseSpeed + rnd.nextFloat() * 0.12f) * density
            val upward = when (kind) {
                ExplosionKind.BALLISTIC -> -0.04f
                ExplosionKind.SHAHED -> -0.015f
                else -> -0.02f
            } * density
            Shard(
                dx = cos(ang) * speed,
                dy = sin(ang) * speed + upward,
                spin = (rnd.nextFloat() - 0.5f) * when (kind) {
                    ExplosionKind.FPV -> 1.1f
                    ExplosionKind.AVIATION -> 0.45f
                    else -> 0.6f
                },
                sx = rnd.nextFloat() * 0.6f,
                sy = rnd.nextFloat() * 0.6f,
                sw = 0.25f + rnd.nextFloat() * 0.3f,
                sh = 0.25f + rnd.nextFloat() * 0.3f,
                sizeMul = when (kind) {
                    ExplosionKind.AVIATION -> 1.25f + rnd.nextFloat() * 0.4f
                    ExplosionKind.FPV -> 0.75f + rnd.nextFloat() * 0.3f
                    else -> 1f
                }
            )
        }
    }

    private fun drawSmokePuffs(canvas: Canvas, x: Float, y: Float, e: Float, fade: Float, density: Float, maxR: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((90 * fade).toInt(), 40, 40, 40)
            style = Paint.Style.FILL
        }
        repeat(4) { i ->
            val a = i * 1.7f
            val r = maxR * (0.3f + 0.5f * e) * (0.7f + i * 0.15f)
            canvas.drawCircle(
                x + cos(a) * maxR * 0.25f * e,
                y + sin(a) * maxR * 0.2f * e,
                r * 0.35f, p
            )
        }
    }

    private fun drawLargeDebris(canvas: Canvas, x: Float, y: Float, e: Float, fade: Float, density: Float, maxR: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((140 * fade).toInt(), 80, 80, 80)
            style = Paint.Style.FILL
        }
        repeat(3) { i ->
            val ang = i * 2.1f + 0.5f
            val dist = maxR * (0.2f + 0.5f * e) * (0.8f + i * 0.1f)
            val sx = x + cos(ang) * dist
            val sy = y + sin(ang) * dist + 0.00004f * density * e * e * maxR * maxR
            val sz = (4f + i * 2f) * density * (1f - 0.4f * e)
            canvas.drawRect(sx - sz, sy - sz * 0.6f, sx + sz, sy + sz * 0.6f, p)
        }
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
            val dur = d.durationMs.toFloat()
            val boomT = DEATH_EXPLOSION_START_MS / dur
            val boomLenT = (d.durationMs - DEATH_EXPLOSION_START_MS) / dur
            mapView.projection.toPixels(d.geo, reuse)
            val x = reuse.x.toFloat()
            val y = reuse.y.toFloat()
            val t = (rawElapsed.toFloat() / dur).coerceIn(0f, 1f)

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
                        val inv = (10f * density) / dist
                        val sx = ox + dx * tnear - dx * inv
                        val sy = oy + dy * tnear - dy * inv
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

            if (t >= boomT && !d.dud) {
                val e = ((t - boomT) / boomLenT).coerceIn(0f, 1f)
                val zoomScale = ((mapView.zoomLevelDouble - 9.0) / 4.0 * 2.0 + 1.0).coerceIn(1.0, 3.0).toFloat()
                val maxR = 46f * density * zoomScale
                val fade = 1f - e
                val hq = highQuality
                val kind = d.kind

                // 1. Glow (shared)
                val glow = explosionGlow(density)
                val br = maxR * e
                flashPaint.alpha = (220 * fade).toInt()
                reuseRect.set(x - br, y - br, x + br, y + br)
                canvas.drawBitmap(glow, null, reuseRect, flashPaint)

                // 2. Core flash — colour per type
                flashPaint.alpha = (200 * fade).toInt()
                flashPaint.color = when (kind) {
                    ExplosionKind.BALLISTIC -> Color.WHITE
                    ExplosionKind.AVIATION -> Color.rgb(180, 220, 255)
                    ExplosionKind.CRUISE -> Color.rgb(255, 180, 80)
                    else -> Color.WHITE
                }
                val coreMul = if (kind == ExplosionKind.BALLISTIC) 0.28f else 0.22f
                canvas.drawCircle(x, y, br * coreMul, flashPaint)

                // 3. Shock rings — intensity per type
                ringPaint.strokeWidth = 2.2f * density
                val ringAlpha = if (kind == ExplosionKind.BALLISTIC) 255 else 220
                ringPaint.color = Color.argb((ringAlpha * fade).toInt(), 255, 213, 0)
                canvas.drawCircle(x, y, maxR * (0.4f + 0.95f * e), ringPaint)

                ringPaint.color = Color.argb((160 * fade).toInt(), 255, 120, 0)
                canvas.drawCircle(x, y, maxR * (0.15f + 1.25f * e), ringPaint)

                if (hq && kind == ExplosionKind.BALLISTIC) {
                    ringPaint.strokeWidth = 1.1f * density
                    ringPaint.color = Color.argb((120 * fade).toInt(), 255, 255, 200)
                    canvas.drawCircle(x, y, maxR * (0.7f + 0.9f * e), ringPaint)
                }

                // 4. Icon shards
                val icon = d.icon
                if (icon != null) {
                    if (d.shards == null) {
                        d.shards = createShards(kind, density, hq, d.id.hashCode())
                    }
                    val iw = icon.intrinsicWidth.coerceAtLeast(1)
                    val ih = icon.intrinsicHeight.coerceAtLeast(1)
                    val iconBmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).also { bmp ->
                        val c = Canvas(bmp)
                        icon.setBounds(0, 0, iw, ih)
                        icon.alpha = 255
                        icon.draw(c)
                    }
                    val elapsedMs = (t - boomT) * boomLenT * d.durationMs
                    val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        alpha = (255 * fade).toInt()
                    }
                    for (s in d.shards!!) {
                        val sx = x + s.dx * elapsedMs
                        val sy = y + s.dy * elapsedMs + 0.00004f * density * elapsedMs * elapsedMs
                        val rot = s.spin * elapsedMs
                        val sz = 10f * density * s.sizeMul * (1f - 0.3f * e)
                        val srcL = (s.sx * iw).toInt().coerceIn(0, iw - 1)
                        val srcT = (s.sy * ih).toInt().coerceIn(0, ih - 1)
                        val srcR = ((s.sx + s.sw) * iw).toInt().coerceIn(srcL + 1, iw)
                        val srcB = ((s.sy + s.sh) * ih).toInt().coerceIn(srcT + 1, ih)
                        canvas.save()
                        canvas.translate(sx, sy)
                        canvas.rotate(rot)
                        canvas.drawBitmap(iconBmp, Rect(srcL, srcT, srcR, srcB), RectF(-sz, -sz, sz, sz), shardPaint)
                        canvas.restore()
                    }
                }

                // 5. Sparks — count & colour per type
                val (sparkCount, sparkColor) = when (kind) {
                    ExplosionKind.FPV -> 10 to Color.rgb(255, 220, 100)
                    ExplosionKind.SHAHED -> 6 to Color.rgb(255, 160, 40)
                    ExplosionKind.BALLISTIC -> 8 to Color.rgb(255, 240, 180)
                    ExplosionKind.AVIATION -> 7 to Color.rgb(180, 220, 255)
                    else -> 6 to Color.rgb(255, 193, 7)
                }
                sparkPaint.color = Color.argb((255 * fade).toInt(),
                    Color.red(sparkColor), Color.green(sparkColor), Color.blue(sparkColor))
                val sparkDist = maxR * (0.55f + 0.7f * e)
                val sparkR = (if (kind == ExplosionKind.FPV) 1.8f else 2.5f) * density * fade
                val actualSparkCount = if (hq) sparkCount + 4 else sparkCount
                repeat(actualSparkCount) { i ->
                    val a = 2.0 * PI * i / actualSparkCount + 0.3 + (i * 0.17)
                    canvas.drawCircle(
                        x + (cos(a) * sparkDist).toFloat(),
                        y + (sin(a) * sparkDist).toFloat(),
                        sparkR, sparkPaint
                    )
                }

                // 6. HD-only extras
                if (hq) {
                    when (kind) {
                        ExplosionKind.SHAHED -> drawSmokePuffs(canvas, x, y, e, fade, density, maxR)
                        ExplosionKind.AVIATION -> drawLargeDebris(canvas, x, y, e, fade, density, maxR)
                        else -> {}
                    }
                }
            }
        }
    }
}
