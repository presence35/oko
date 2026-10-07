package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Frames per baked strip. 24 over a ~3s tail is ~8fps of shape change plus additive embers. */
private const val ELITE_FRAMES = 24

/** One strip is 4 columns of frames, so a 24-frame sheet is 4x6 rather than 24x1 tall. */
private const val ELITE_STRIP_COLS = 4

/** Frame edge in density-independent px. The strip is baked once per density and reused. */
private const val ELITE_FRAME_DP = 132

/** Baked on a background thread — a strip is a few hundred KB of pixel work. */
private val EliteBakeExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "elite-death-bake").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}

/**
 * Elite style B — baked film. The explosion body is a pre-rendered frame strip per
 * [EliteKind]: 24 hand-shaped fireball frames baked once per density on a background thread and
 * then played back with one `drawBitmap` per death per frame. Playback cost is a single textured
 * quad, so a full [ELITE_MAX_DEATHS] replay show costs the same per-frame budget as one blast.
 *
 * Trades away live turbulence for identity: every device, every API level, looks the same.
 */
class EliteBakedDeathOverlay : EliteDeathBase() {

    /** Baked strips, one per kind. Written by the bake thread, read on the draw thread. */
    private val strips = ConcurrentHashMap<EliteKind, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<EliteKind>()

    @Volatile
    private var bakedDensity = 0f

    private val framePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val emberPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val scratch = Rect()
    private val dst = RectF()

    override fun drawExplosion(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        elapsedMs: Float,
        hq: Boolean
    ) {
        val strip = stripFor(d.kind, density)
        val r = maxR * 1.25f

        if (strip != null) {
            val idx = (e * ELITE_FRAMES).toInt().coerceIn(0, ELITE_FRAMES - 1)
            val fs = strip.width / ELITE_STRIP_COLS
            scratch.set(
                (idx % ELITE_STRIP_COLS) * fs,
                (idx / ELITE_STRIP_COLS) * fs,
                (idx % ELITE_STRIP_COLS) * fs + fs,
                (idx / ELITE_STRIP_COLS) * fs + fs
            )
            framePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            dst.set(x - r, y - r, x + r, y + r)
            canvas.drawBitmap(strip, scratch, dst, framePaint)
        } else {
            // Strip still baking: the additive shock ring carries the blast until frames land.
            framePaint.color = Color.argb((180 * fade).toInt(), 255, 190, 90)
            framePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val rr = shockRadius(maxR, e, 1.2f)
            canvas.drawCircle(x, y, rr.coerceAtLeast(1f), framePaint)
        }

        drawEmbers(canvas, d, x, y, e, fade, density, maxR, hq)
    }

    private fun drawEmbers(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        hq: Boolean
    ) {
        val count = if (hq) 16 else 9
        val rnd = Random(d.seed xor 0x2545F491)
        val color = ember(d.kind)
        repeat(count) {
            val ang = rnd.nextFloat() * ELITE_TAU
            val speed = 0.3f + rnd.nextFloat() * 1.3f
            val life = (1f - e * (0.6f + rnd.nextFloat() * 0.4f)).coerceIn(0f, 1f)
            if (life <= 0.03f) return@repeat
            val dist = maxR * speed * (0.2f + 1.1f * e)
            emberPaint.color = Color.argb(
                (255 * fade * life).toInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            canvas.drawCircle(
                x + cos(ang) * dist,
                y + sin(ang) * dist - maxR * 0.09f * e * e,
                2f * density * (0.6f + rnd.nextFloat()) * life,
                emberPaint
            )
        }
    }

    /** Returns the strip if it is already baked, else schedules the bake and returns null. */
    private fun stripFor(kind: EliteKind, density: Float): Bitmap? {
        if (bakedDensity != density) {
            // A density change invalidates every strip; drop them and re-bake on demand.
            strips.values.forEach { it.recycle() }
            strips.clear()
            pending.clear()
            bakedDensity = density
        }
        strips[kind]?.let { return it }
        if (pending.add(kind)) {
            val size = (ELITE_FRAME_DP * density).toInt().coerceIn(48, 384)
            EliteBakeExecutor.execute {
                val baked = bakeStrip(kind, size)
                // A concurrent density change already recycled the map's slots; this bake is stale.
                if (bakedDensity == density && baked != null) strips[kind] = baked
                pending.remove(kind)
            }
        }
        return null
    }

    private fun bakeStrip(kind: EliteKind, size: Int): Bitmap? = runCatching {
        val rows = ELITE_FRAMES / ELITE_STRIP_COLS
        val sheet = Bitmap.createBitmap(size * ELITE_STRIP_COLS, size * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val hot = hotCore(kind)
        val mid = ember(kind)
        val smoke = Color.rgb(48, 46, 44)
        val half = size / 2f
        val paint = Paint().apply { isAntiAlias = true }
        val add = PorterDuffXfermode(PorterDuff.Mode.ADD)

        for (f in 0 until ELITE_FRAMES) {
            val t = f / (ELITE_FRAMES - 1f)
            val cx = (f % ELITE_STRIP_COLS) * size + half
            val cy = (f / ELITE_STRIP_COLS) * size + half
            // Fireball: swells fast, collapses slow.
            val swell = if (t < 0.22f) t / 0.22f else 1f - (t - 0.22f) / 0.78f
            val fireR = half * (0.18f + 0.74f * swell.coerceIn(0f, 1f))
            val coreR = fireR * (0.42f - 0.22f * t)

            // Shock ring, one frame behind the fireball.
            val ringR = half * (0.2f + 1.0f * t)
            val ringFade = (1f - t).coerceIn(0f, 1f)
            paint.xfermode = add
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (2.5f - 1.6f * t).coerceAtLeast(0.8f) * (size / 132f)
            paint.color = Color.argb((150 * ringFade).toInt().coerceIn(0, 255), 255, 214, 150)
            c.drawCircle(cx, cy, ringR, paint)

            // Turbulent lobes: a few offset blobs ride the fireball edge so it isn't a clean disc.
            paint.style = Paint.Style.FILL
            paint.xfermode = null
            val lobes = 5
            for (l in 0 until lobes) {
                val a = l * (ELITE_TAU / lobes) + t * 1.1f + kind.ordinal
                val lobeR = fireR * (0.42f + 0.18f * ((l * 37 % 11) / 11f))
                val ox = cos(a) * fireR * 0.42f
                val oy = sin(a) * fireR * 0.42f
                val alpha = (200 * (1f - t * 0.85f)).toInt().coerceIn(0, 255)
                paint.shader = RadialGradient(
                    cx + ox, cy + oy, lobeR,
                    intArrayOf(
                        Color.argb(alpha, Color.red(hot), Color.green(hot), Color.blue(hot)),
                        Color.argb((alpha * 0.6f).toInt(), Color.red(mid), Color.green(mid), Color.blue(mid)),
                        Color.argb(0, Color.red(mid), Color.green(mid), Color.blue(mid))
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawCircle(cx + ox, cy + oy, lobeR, paint)
                paint.shader = null
            }

            // Hot core.
            paint.xfermode = add
            paint.color = Color.argb((220 * (1f - t * 0.9f)).toInt().coerceIn(0, 255), 255, 255, 240)
            c.drawCircle(cx, cy, coreR.coerceAtLeast(1f), paint)
            paint.xfermode = null

            // Smoke skirt on the back half only, rising as the fireball dies.
            if (t > 0.3f) {
                val st = (t - 0.3f) / 0.7f
                paint.color = Color.argb((70 * (1f - st)).toInt().coerceIn(0, 90), Color.red(smoke), Color.green(smoke), Color.blue(smoke))
                val puffs = 5
                for (p in 0 until puffs) {
                    val a = p * (ELITE_TAU / puffs) + 0.7f
                    val spread = fireR * (0.5f + 0.6f * st)
                    val pr = fireR * (0.34f + 0.3f * st)
                    c.drawCircle(
                        cx + cos(a) * spread,
                        cy + sin(a) * spread - fireR * 0.7f * st,
                        pr,
                        paint
                    )
                }
            }
        }
        sheet
    }.getOrNull()
}