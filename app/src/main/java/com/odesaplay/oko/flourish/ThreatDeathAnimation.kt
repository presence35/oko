package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.odesaplay.oko.engine.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Total length of the death animation: projectile flight + explosion. */
private const val DEATH_DURATION_MS = 5000L

/** The projectile hits and the explosion begins this many ms into the animation. */
const val DEATH_EXPLOSION_START_MS = 2000L

/** Explosion window ends this many ms after it starts; the neutralized card fades out across it. */
const val DEATH_EXPLOSION_LEN_MS = DEATH_DURATION_MS - DEATH_EXPLOSION_START_MS

/** Compressed explosion for quick-boom deaths (intermediate replay groups). */
private const val QUICK_EXPLOSION_LEN_MS = 800L

/** Concurrent-death ceiling for overnight replay shows. */
private const val MAX_DEATHS = 420

private const val TAU = (2.0 * PI).toFloat()

/**
 * One debris piece torn from the threat icon.
 * Velocity is in px/ms at density=1 (scaled by density at creation).
 * UV rect is a grid cell (possibly merged) so chunks read as broken icon parts, not random noise.
 */
private data class Shard(
    val vx: Float,
    val vy: Float,
    val spin: Float,
    val sx: Float,
    val sy: Float,
    val sw: Float,
    val sh: Float,
    val sizeMul: Float,
    val aspect: Float,
    val drag: Float,
    val fadeStart: Float
)

/** Random spark with its own direction and speed (not a fixed ring). */
private data class Spark(
    val ang: Float,
    val speed: Float,
    val size: Float,
    val phase: Float
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
    val geo: LatLng,
    val startGeo: LatLng = geo,
    var origin: LatLng?,
    val start: Long,
    val icon: Drawable?,
    val rotationDeg: Float,
    val alpha: Float,
    val dud: Boolean,
    val durationMs: Long = DEATH_DURATION_MS,
    val kind: ExplosionKind = ExplosionKind.SHAHED,
    /** Stable seed so a given spawn is reproducible; varies across live kills via start time. */
    val seed: Int = 0
) {
    var shards: Array<Shard>? = null
    var sparks: Array<Spark>? = null
    /** Rasterized icon + per-shard source rects, built once at first explosion frame. */
    var iconBmp: Bitmap? = null
    var srcRects: Array<Rect>? = null
}

class ThreatDeathOverlay : DeathAnimation {

    private val deaths = mutableListOf<ActiveDeath>()

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    override val isActive: Boolean get() = deaths.isNotEmpty()

    override var highQuality: Boolean = true

    /** Shards and smoke are the expensive half: a show drops them rather than the flash. */
    override var economyMode: Boolean = false

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 160, 40),
        shape = ExplosionShape.SHARDS,
        scale = 1.05f
    )

    private fun syncActive() {
        val nowActive = deaths.isNotEmpty()
        if (_active.value != nowActive) _active.value = nowActive
    }

    override fun isActiveFor(id: String?): Boolean = id != null && deaths.any { it.id == id }

    override fun rebasePendingOrigins(newOrigin: () -> LatLng?) {
        val now = SystemClock.elapsedRealtime()
        for (d in deaths) {
            if (now < d.start) d.origin = newOrigin()
        }
    }

    override fun spawn(
        id: String?,
        geo: LatLng,
        startGeo: LatLng,
        origin: LatLng?,
        icon: Drawable?,
        rotationDeg: Float,
        alpha: Float,
        quickBoom: Boolean,
        fireAtDelayMs: Long,
        type: ThreatType
    ) {
        if (deaths.size >= MAX_DEATHS) return
        val start = SystemClock.elapsedRealtime() + fireAtDelayMs.coerceAtLeast(0L)
        val seed = (id?.hashCode() ?: 0) xor start.toInt() xor type.ordinal * 0x9E3779B9.toInt()
        deaths.add(
            ActiveDeath(
                id, geo, startGeo, origin, start,
                icon, rotationDeg, alpha, dud = false,
                durationMs = if (quickBoom) DEATH_EXPLOSION_START_MS + QUICK_EXPLOSION_LEN_MS
                else DEATH_DURATION_MS,
                kind = ExplosionKind.fromType(type),
                seed = seed
            )
        )
        syncActive()
    }

    override fun spawnDud(id: String?, geo: LatLng, origin: LatLng?) {
        if (origin == null || deaths.size >= MAX_DEATHS) return
        deaths.add(
            ActiveDeath(id, geo, geo, origin, SystemClock.elapsedRealtime(), null, 0f, 1f, dud = true)
        )
        syncActive()
    }

    override fun clear() {
        deaths.forEach { it.iconBmp?.recycle(); it.iconBmp = null }
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
    private val shardPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val smokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val debrisPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private var glowBitmap: Bitmap? = null
    private val reuseRect = RectF()
    private val shardDst = RectF()
    private var bulletSprite: BulletSprite? = null

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

    /**
     * Build irregular icon chunks from a coarse UV grid (merge neighbors), with wide random
     * velocity, drag, and fade timing. Directions are fully random with optional outward bias.
     */
    private fun createShards(
        kind: ExplosionKind,
        density: Float,
        hq: Boolean,
        seed: Int,
        inboundAngleRad: Float? = null
    ): Array<Shard> {
        val rnd = Random(seed)
        val cols = if (hq) 4 else 3
        val rows = if (hq) 4 else 3
        // Collect grid cells, then randomly merge some horizontal/vertical pairs.
        data class Cell(var c0: Int, var r0: Int, var c1: Int, var r1: Int, var keep: Boolean)
        val cells = ArrayList<Cell>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cells += Cell(c, r, c + 1, r + 1, keep = true)
            }
        }
        // Merge ~30% of cells with a neighbor for irregular chunk sizes.
        for (i in cells.indices) {
            val cell = cells[i]
            if (!cell.keep) continue
            if (rnd.nextFloat() > 0.32f) continue
            val horiz = rnd.nextBoolean()
            if (horiz && cell.c1 < cols) {
                val right = cells.indexOfFirst {
                    it.keep && it.r0 == cell.r0 && it.r1 == cell.r1 && it.c0 == cell.c1
                }
                if (right >= 0) {
                    cells[right].keep = false
                    cell.c1 = cells[right].c1
                }
            } else if (!horiz && cell.r1 < rows) {
                val below = cells.indexOfFirst {
                    it.keep && it.c0 == cell.c0 && it.c1 == cell.c1 && it.r0 == cell.r1
                }
                if (below >= 0) {
                    cells[below].keep = false
                    cell.r1 = cells[below].r1
                }
            }
        }
        // Drop a few cells so the icon doesn't shatter into a complete grid.
        val kept = cells.filter { it.keep }.toMutableList()
        val dropTarget = (kept.size * 0.15f).toInt().coerceAtLeast(0)
        repeat(dropTarget) {
            if (kept.size <= 4) return@repeat
            kept.removeAt(rnd.nextInt(kept.size))
        }

        val baseSpeed = when (kind) {
            ExplosionKind.BALLISTIC -> 0.13f
            ExplosionKind.FPV -> 0.18f
            ExplosionKind.AVIATION -> 0.09f
            ExplosionKind.CRUISE -> 0.12f
            ExplosionKind.SHAHED -> 0.11f
            else -> 0.10f
        }
        val spinScale = when (kind) {
            ExplosionKind.FPV -> 1.35f
            ExplosionKind.AVIATION -> 0.4f
            ExplosionKind.BALLISTIC -> 0.55f
            else -> 0.7f
        }
        val upwardBias = when (kind) {
            ExplosionKind.BALLISTIC -> -0.06f
            ExplosionKind.SHAHED -> -0.02f
            ExplosionKind.AVIATION -> -0.01f
            else -> -0.025f
        }

        return Array(kept.size) { i ->
            val cell = kept[i]
            val sx = cell.c0 / cols.toFloat()
            val sy = cell.r0 / rows.toFloat()
            val sw = (cell.c1 - cell.c0) / cols.toFloat()
            val sh = (cell.r1 - cell.r0) / rows.toFloat()
            // Slight UV jitter so edges aren't perfectly grid-aligned.
            val jx = (rnd.nextFloat() - 0.5f) * 0.04f
            val jy = (rnd.nextFloat() - 0.5f) * 0.04f

            // Random direction with optional bias opposite inbound projectile.
            var ang = rnd.nextFloat() * TAU
            if (inboundAngleRad != null && rnd.nextFloat() < 0.45f) {
                // Cone opposite flight direction (±~80°).
                ang = inboundAngleRad + PI.toFloat() + (rnd.nextFloat() - 0.5f) * 1.4f
            }
            // Power-law-ish speed: few fast, many slower.
            val speedMul = 0.3f + rnd.nextFloat().pow(0.65f) * 1.5f
            val speed = baseSpeed * speedMul * density
            val vx = cos(ang) * speed
            val vy = sin(ang) * speed + upwardBias * density * (0.6f + rnd.nextFloat() * 0.8f)

            // Size: larger for bigger UV cells + type personality.
            val area = sw * sh
            val sizeMul = when (kind) {
                ExplosionKind.AVIATION -> 1.1f + area * 2.2f + rnd.nextFloat() * 0.5f
                ExplosionKind.FPV -> 0.55f + area * 1.4f + rnd.nextFloat() * 0.35f
                ExplosionKind.BALLISTIC -> 0.85f + area * 1.6f
                else -> 0.75f + area * 1.8f + rnd.nextFloat() * 0.3f
            }
            val aspect = (sw / sh.coerceAtLeast(0.05f)).coerceIn(0.45f, 2.2f) *
                (0.85f + rnd.nextFloat() * 0.3f)

            Shard(
                vx = vx,
                vy = vy,
                spin = (rnd.nextFloat() - 0.5f) * spinScale * (0.7f + rnd.nextFloat() * 0.8f),
                sx = (sx + jx).coerceIn(0f, 0.92f),
                sy = (sy + jy).coerceIn(0f, 0.92f),
                sw = sw.coerceIn(0.12f, 0.55f),
                sh = sh.coerceIn(0.12f, 0.55f),
                sizeMul = sizeMul,
                aspect = aspect,
                drag = 0.72f + rnd.nextFloat() * 0.25f,
                fadeStart = 0.45f + rnd.nextFloat() * 0.45f
            )
        }
    }

    private fun createSparks(kind: ExplosionKind, hq: Boolean, seed: Int): Array<Spark> {
        val rnd = Random(seed xor 0xA5A5A5)
        val count = when (kind) {
            ExplosionKind.FPV -> if (hq) 16 else 11
            ExplosionKind.SHAHED -> if (hq) 12 else 8
            ExplosionKind.BALLISTIC -> if (hq) 14 else 9
            ExplosionKind.AVIATION -> if (hq) 11 else 7
            else -> if (hq) 10 else 7
        }
        return Array(count) {
            Spark(
                ang = rnd.nextFloat() * TAU,
                speed = 0.45f + rnd.nextFloat() * 1.1f,
                size = 0.6f + rnd.nextFloat() * 1.1f,
                phase = rnd.nextFloat()
            )
        }
    }

    private fun drawSmokePuffs(
        canvas: Canvas,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        seed: Int,
        rising: Boolean
    ) {
        val rnd = Random(seed xor 0x5F3759DF)
        val n = if (rising) 6 else 4
        repeat(n) { i ->
            val a = rnd.nextFloat() * TAU
            val rise = if (rising) -maxR * 0.55f * e * (0.5f + i * 0.12f) else 0f
            val spread = maxR * (0.15f + 0.55f * e) * (0.5f + rnd.nextFloat() * 0.6f)
            val cx = x + cos(a) * spread * (0.4f + rnd.nextFloat() * 0.6f)
            val cy = y + sin(a) * spread * 0.35f + rise
            val r = maxR * (0.12f + 0.22f * e) * (0.7f + rnd.nextFloat() * 0.5f)
            val alpha = (75 * fade * (1f - e * 0.5f)).toInt().coerceIn(0, 120)
            smokePaint.color = Color.argb(alpha, 35 + rnd.nextInt(25), 35, 35)
            canvas.drawCircle(cx, cy, r, smokePaint)
        }
    }

    private fun drawLargeDebris(
        canvas: Canvas,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        seed: Int
    ) {
        val rnd = Random(seed xor 0xC0FFEE)
        repeat(5) { i ->
            val ang = rnd.nextFloat() * TAU
            val dist = maxR * (0.15f + 0.65f * e) * (0.5f + rnd.nextFloat() * 0.7f)
            val sx = x + cos(ang) * dist
            val sy = y + sin(ang) * dist + 0.15f * density * e * e * maxR
            val w = (3.5f + rnd.nextFloat() * 5f) * density * (1f - 0.35f * e)
            val h = w * (0.4f + rnd.nextFloat() * 0.7f)
            val alpha = (150 * fade).toInt().coerceIn(0, 180)
            debrisPaint.color = Color.argb(alpha, 60 + rnd.nextInt(40), 60, 55)
            canvas.save()
            canvas.translate(sx, sy)
            canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() + e * 40f * (i - 2))
            canvas.drawRect(-w, -h, w, h, debrisPaint)
            canvas.restore()
        }
    }

    override fun draw(
        canvas: Canvas,
        density: Float,
        viewWidth: Float,
        viewHeight: Float,
        resources: android.content.res.Resources,
        zoom: Double,
        project: (lat: Double, lon: Double) -> PointF?
    ) {
        if (deaths.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        deaths.removeAll {
            val elapsed = now - it.start
            val done = elapsed > it.durationMs || (it.dud && elapsed > DEATH_EXPLOSION_START_MS)
            if (done) {
                it.iconBmp?.recycle()
                it.iconBmp = null
            }
            done
        }
        if (deaths.isEmpty()) {
            syncActive()
            return
        }
        val bullet = bulletSprite ?: BulletSprite(resources).also { bulletSprite = it }

        for (d in deaths) {
            val rawElapsed = now - d.start
            if (rawElapsed < 0L) {
                // Pre-spawned: show standing icon at startGeo until fire time.
                d.icon?.let { icon ->
                    val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
                    val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
                    icon.alpha = (d.alpha * 255).toInt()
                    val pt = project(d.startGeo.lat, d.startGeo.lon) ?: continue
                    canvas.save()
                    canvas.translate(pt.x, pt.y)
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
            val pt = project(d.geo.lat, d.geo.lon) ?: continue
            val x = pt.x
            val y = pt.y
            val t = (rawElapsed.toFloat() / dur).coerceIn(0f, 1f)

            // Threat icon glides toward impact until boom.
            d.icon?.let { icon ->
                if (t < boomT) {
                    val p = if (boomT > 0f) (t / boomT).coerceIn(0f, 1f) else 1f
                    val droneLat = d.startGeo.lat + (d.geo.lat - d.startGeo.lat) * p
                    val droneLon = d.startGeo.lon + (d.geo.lon - d.startGeo.lon) * p
                    val iconPt = project(droneLat, droneLon)
                    if (iconPt != null) {
                        val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
                        val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
                        icon.alpha = (d.alpha * 255).toInt()
                        canvas.save()
                        canvas.translate(iconPt.x, iconPt.y)
                        canvas.rotate(d.rotationDeg)
                        icon.setBounds(-w.toInt(), -h.toInt(), w.toInt(), h.toInt())
                        icon.draw(canvas)
                        canvas.restore()
                    }
                }
            }

            // Inbound projectile + soft trail.
            if (t in 0f..boomT && d.origin != null) {
                val oPt = project(d.origin!!.lat, d.origin!!.lon)
                if (oPt != null) {
                    val ox = oPt.x
                    val oy = oPt.y
                    val dx = x - ox
                    val dy = y - oy
                    val dist = sqrt(dx * dx + dy * dy)
                    val p = (t / boomT).coerceIn(0f, 1f)
                    if (dist > 1f) {
                        val W = viewWidth
                        val H = viewHeight
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

                            bullet.draw(
                                canvas, bx, by,
                                (Math.toDegrees(atan2(headY.toDouble(), headX.toDouble())) + 90).toFloat(),
                                density
                            )
                        }
                    }
                }
            }

            if (t >= boomT && !d.dud) {
                val e = ((t - boomT) / boomLenT).coerceIn(0f, 1f)
                val zoomScale = ((zoom - 9.0) / 4.0 * 2.0 + 1.0).coerceIn(1.0, 3.0).toFloat()
                val maxR = 46f * density * zoomScale
                val fade = 1f - e
                val hq = highQuality
                val kind = d.kind
                val elapsedMs = (t - boomT) * dur

                // Impact punch (first ~8% of explosion).
                if (e < 0.08f) {
                    val punch = 1f - e / 0.08f
                    flashPaint.color = Color.argb((200 * punch).toInt(), 255, 255, 255)
                    canvas.drawCircle(x, y, maxR * 0.35f * punch, flashPaint)
                }

                // 1. Glow
                val glow = explosionGlow(density)
                val br = maxR * e
                flashPaint.alpha = (220 * fade).toInt()
                flashPaint.color = Color.WHITE
                reuseRect.set(x - br, y - br, x + br, y + br)
                canvas.drawBitmap(glow, null, reuseRect, flashPaint)

                // 2. Core flash — colour per type
                flashPaint.alpha = (200 * fade).toInt()
                flashPaint.color = when (kind) {
                    ExplosionKind.BALLISTIC -> Color.WHITE
                    ExplosionKind.AVIATION -> Color.rgb(180, 220, 255)
                    ExplosionKind.CRUISE -> Color.rgb(255, 180, 80)
                    ExplosionKind.FPV -> Color.rgb(255, 230, 140)
                    else -> Color.WHITE
                }
                val coreMul = if (kind == ExplosionKind.BALLISTIC) 0.28f else 0.22f
                canvas.drawCircle(x, y, br * coreMul, flashPaint)

                // 3. Shockwave rings (faster outer wave + slower fire ring)
                ringPaint.strokeWidth = 2.4f * density
                val ringAlpha = if (kind == ExplosionKind.BALLISTIC) 255 else 220
                ringPaint.color = Color.argb((ringAlpha * fade).toInt(), 255, 213, 0)
                canvas.drawCircle(x, y, maxR * (0.35f + 1.05f * e), ringPaint)

                ringPaint.color = Color.argb((150 * fade).toInt(), 255, 120, 0)
                canvas.drawCircle(x, y, maxR * (0.12f + 1.35f * e), ringPaint)

                if (hq) {
                    ringPaint.strokeWidth = 1.2f * density
                    ringPaint.color = Color.argb((100 * fade).toInt(), 255, 255, 210)
                    canvas.drawCircle(x, y, maxR * (0.55f + 1.15f * e), ringPaint)
                }

                // 4. Icon shards — random direction, irregular UV chunks, drag + gravity
                // A show keeps the flash and drops the debris, which is where this style costs.
                if (!economyMode) d.icon?.let { icon ->
                    if (d.shards == null) {
                        val inbound = d.origin?.let { o ->
                            val op = project(o.lat, o.lon)
                            if (op != null) atan2((y - op.y).toDouble(), (x - op.x).toDouble()).toFloat()
                            else null
                        }
                        d.shards = createShards(kind, density, hq, d.seed, inbound)
                    }
                    if (d.sparks == null) {
                        d.sparks = createSparks(kind, hq, d.seed)
                    }
                    val shards = d.shards!!
                    val iw = icon.intrinsicWidth.coerceAtLeast(1)
                    val ih = icon.intrinsicHeight.coerceAtLeast(1)
                    val iconBmp = d.iconBmp ?: Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).also { bmp ->
                        val iconCanvas = Canvas(bmp)
                        icon.alpha = 255
                        icon.setBounds(0, 0, iw, ih)
                        icon.draw(iconCanvas)
                        d.iconBmp = bmp
                    }
                    val rects = d.srcRects ?: Array(shards.size) { i ->
                        val s = shards[i]
                        Rect(
                            (s.sx * iw).toInt().coerceIn(0, iw - 1),
                            (s.sy * ih).toInt().coerceIn(0, ih - 1),
                            ((s.sx + s.sw) * iw).toInt().coerceIn(((s.sx * iw).toInt() + 1).coerceAtMost(iw), iw),
                            ((s.sy + s.sh) * ih).toInt().coerceIn(((s.sy * ih).toInt() + 1).coerceAtMost(ih), ih)
                        )
                    }.also { d.srcRects = it }

                    val g = 0.000045f * density // gravity px/ms²
                    for ((i, s) in shards.withIndex()) {
                        // Integrate with drag: v_eff decays, position uses average under drag.
                        val dragFactor = s.drag.pow((elapsedMs / 400f).coerceIn(0f, 3f))
                        val sx = x + s.vx * elapsedMs * dragFactor
                        val sy = y + s.vy * elapsedMs * dragFactor + 0.5f * g * elapsedMs * elapsedMs
                        val rot = s.spin * elapsedMs
                        val life = when {
                            e < s.fadeStart -> 1f
                            else -> (1f - (e - s.fadeStart) / (1f - s.fadeStart).coerceAtLeast(0.05f))
                                .coerceIn(0f, 1f)
                        }
                        if (life <= 0.02f) continue
                        val base = 9f * density * s.sizeMul * (1f - 0.25f * e)
                        val hw = base * s.aspect
                        val hh = base / s.aspect.coerceAtLeast(0.5f)
                        shardPaint.alpha = (255 * fade * life).toInt().coerceIn(0, 255)
                        canvas.save()
                        canvas.translate(sx, sy)
                        canvas.rotate(rot)
                        shardDst.set(-hw, -hh, hw, hh)
                        canvas.drawBitmap(iconBmp, rects[i], shardDst, shardPaint)
                        canvas.restore()
                    }
                }

                // 5. Sparks — random angles & speeds (not a regular ring)
                val sparkColor = when (kind) {
                    ExplosionKind.FPV -> Color.rgb(255, 220, 100)
                    ExplosionKind.SHAHED -> Color.rgb(255, 160, 40)
                    ExplosionKind.BALLISTIC -> Color.rgb(255, 240, 180)
                    ExplosionKind.AVIATION -> Color.rgb(180, 220, 255)
                    ExplosionKind.CRUISE -> Color.rgb(255, 190, 90)
                    else -> Color.rgb(255, 193, 7)
                }
                val sparks = d.sparks ?: createSparks(kind, hq, d.seed).also { d.sparks = it }
                for (sp in sparks) {
                    val dist = maxR * sp.speed * (0.35f + 0.9f * e)
                    val sx = x + cos(sp.ang) * dist
                    val sy = y + sin(sp.ang) * dist - maxR * 0.08f * e * e
                    val sparkLife = (1f - e * (0.7f + sp.phase * 0.3f)).coerceIn(0f, 1f)
                    if (sparkLife <= 0.02f) continue
                    sparkPaint.color = Color.argb(
                        (255 * fade * sparkLife).toInt().coerceIn(0, 255),
                        Color.red(sparkColor),
                        Color.green(sparkColor),
                        Color.blue(sparkColor)
                    )
                    val sparkR = (if (kind == ExplosionKind.FPV) 1.6f else 2.3f) *
                        density * sp.size * sparkLife
                    canvas.drawCircle(sx, sy, sparkR, sparkPaint)
                }

                // 6. Smoke / large debris by type (HQ)
                if (hq) {
                    when (kind) {
                        ExplosionKind.SHAHED, ExplosionKind.CRUISE, ExplosionKind.KAB ->
                            drawSmokePuffs(canvas, x, y, e, fade, density, maxR, d.seed, rising = true)
                        ExplosionKind.BALLISTIC ->
                            drawSmokePuffs(canvas, x, y, e, fade, density, maxR, d.seed, rising = true)
                        ExplosionKind.AVIATION -> {
                            drawSmokePuffs(canvas, x, y, e, fade, density, maxR, d.seed, rising = false)
                            drawLargeDebris(canvas, x, y, e, fade, density, maxR, d.seed)
                        }
                        ExplosionKind.FPV -> {
                            // Light haze only
                            if (e > 0.2f) {
                                drawSmokePuffs(canvas, x, y, e * 0.7f, fade * 0.6f, density, maxR * 0.7f, d.seed, rising = false)
                            }
                        }
                        else -> {
                            if (e > 0.15f) {
                                drawSmokePuffs(canvas, x, y, e, fade * 0.8f, density, maxR, d.seed, rising = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
