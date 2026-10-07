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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Frames per baked sheet. 16 over the explosion tail is enough shape change with embers on top. */
private const val ELITE_FRAMES = 16

/** 4x4 grid — a 16x1 sheet would be a 16x-tall bitmap, which some devices refuse outright. */
private const val ELITE_SHEET_COLS = 4

/** Sheet frame edge in density-independent px. */
private const val ELITE_FRAME_DP = 96

/**
 * Elite style "Film" — the pre-baked one. The explosion body is a 16-frame sheet baked once per
 * density on a background thread and played back with a single `drawBitmap` per death.
 *
 * One sheet, not one per threat type: the bake is neutral white-hot and each kind is tinted at
 * draw time with a cached colour filter. That is the difference between a first kill costing one
 * bake and costing eight — which is what made the earlier version stutter on first contact.
 *
 * It is also the cheapest tier by design, so it is what a replay show or a throttled frame falls
 * back to without the caller swapping styles.
 */
class EliteFilmDeathOverlay : EliteDeathBase() {

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 180, 70),
        shape = ExplosionShape.BALL,
        scale = 1f
    )

    @Volatile
    private var sheet: Bitmap? = null

    @Volatile
    private var bakedDensity = 0f

    /** One bake in flight at a time; the first kill of the session waits on it, not on a queue. */
    private val bakeKick = AtomicBoolean(false)

    private val tints = HashMap<EliteKind, android.graphics.ColorFilter>(EliteKind.entries.size)
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
        budget: ExplosionBudget
    ) {
        val frames = when (budget) {
            ExplosionBudget.FULL -> ELITE_FRAMES
            ExplosionBudget.SOLID -> ELITE_FRAMES / 2
            ExplosionBudget.CHEAP -> 6
        }
        val bmp = sheetFor(density)
        if (bmp == null) {
            // The bake is still running: an additive ring carries the blast so the first
            // kill of the session is never a blank frame.
            framePaint.color = Color.argb((170 * fade).toInt().coerceIn(0, 255), 255, 196, 96)
            canvas.drawCircle(x, y, shockRadius(maxR, e, 1.1f).coerceAtLeast(1f), framePaint)
            return
        }

        val idx = (e * frames).toInt().coerceIn(0, frames - 1)
        val fs = bmp.width / ELITE_SHEET_COLS
        val col = idx % ELITE_SHEET_COLS
        val row = idx / ELITE_SHEET_COLS
        scratch.set(col * fs, row * fs, col * fs + fs, row * fs + fs)
        framePaint.colorFilter = tint(d.kind)
        framePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        val r = maxR * 1.2f
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(bmp, scratch, dst, framePaint)
        framePaint.colorFilter = null

        if (budget == ExplosionBudget.FULL) {
            drawEmbers(canvas, d, x, y, e, fade, density, maxR)
        }
    }

    private fun tint(kind: EliteKind): android.graphics.ColorFilter =
        tints.getOrPut(kind) {
            val c = ember(kind)
            // MULTIPLY keeps the sheet's white-hot core white and tints only its colour.
            android.graphics.PorterDuffColorFilter(c, PorterDuff.Mode.MULTIPLY)
        }

    private fun drawEmbers(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float
    ) {
        val count = if (highQuality) 16 else 9
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

    /** The baked sheet, kicking off the bake on first use. One bake for the whole session. */
    private fun sheetFor(density: Float): Bitmap? {
        if (bakedDensity != density) {
            sheet?.recycle()
            sheet = null
            bakedDensity = density
        }
        sheet?.let { return it }
        if (bakeKick.compareAndSet(false, true)) {
            val size = (ELITE_FRAME_DP * density).toInt().coerceIn(48, 320)
            EliteBakeExecutor.execute {
                val baked = bakeSheet(size)
                if (bakedDensity == density) sheet = baked
                bakeKick.set(false)
            }
        }
        return null
    }

    override fun clear() {
        super.clear()
        // The sheet is a session-lifetime cache, not per-death state: a replay show pays for it once.
    }

    private companion object {
        val EliteBakeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "elite-film-bake").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}

/** One neutral sheet for every threat type: white-hot core, warm mid, grey smoke skirt. */
private fun bakeSheet(frame: Int): Bitmap? = runCatching {
    val rows = ELITE_FRAMES / ELITE_SHEET_COLS
    val sheet = Bitmap.createBitmap(frame * ELITE_SHEET_COLS, frame * rows, Bitmap.Config.ARGB_8888)
    val c = Canvas(sheet)
    val paint = Paint().apply { isAntiAlias = true }
    val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    val half = frame / 2f
    val core = intArrayOf(255, 255, 246)
    val mid = intArrayOf(255, 190, 96)

    for (f in 0 until ELITE_FRAMES) {
        val t = f / (ELITE_FRAMES - 1f)
        val cx = (f % ELITE_SHEET_COLS) * frame + half
        val cy = (f / ELITE_SHEET_COLS) * frame + half
        // Fireball: swells fast, collapses slow.
        val swell = if (t < 0.22f) t / 0.22f else 1f - (t - 0.22f) / 0.78f
        val fireR = half * (0.18f + 0.7f * swell.coerceIn(0f, 1f))

        // Turbulent lobes ride the fireball edge so it never reads as a clean disc.
        paint.xfermode = null
        repeat(5) { l ->
            val a = l * (ELITE_TAU / 5f) + t * 1.1f
            val lobeR = fireR * (0.42f + 0.18f * ((l * 37 % 11) / 11f))
            val ox = cos(a) * fireR * 0.42f
            val oy = sin(a) * fireR * 0.42f
            val alpha = (210 * (1f - t * 0.85f)).toInt().coerceIn(0, 255)
            paint.shader = RadialGradient(
                cx + ox, cy + oy, lobeR,
                intArrayOf(
                    Color.argb(alpha, core[0], core[1], core[2]),
                    Color.argb((alpha * 0.55f).toInt(), mid[0], mid[1], mid[2]),
                    Color.argb(0, mid[0], mid[1], mid[2])
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(cx + ox, cy + oy, lobeR, paint)
            paint.shader = null
        }

        // Hot core and shock ring, additive.
        paint.xfermode = add
        paint.color = Color.argb((230 * (1f - t * 0.9f)).toInt().coerceIn(0, 255), 255, 255, 240)
        c.drawCircle(cx, cy, (fireR * (0.44f - 0.24f * t)).coerceAtLeast(1f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (2.2f - 1.4f * t).coerceAtLeast(0.8f) * (frame / 96f)
        paint.color = Color.argb((150 * (1f - t)).toInt().coerceIn(0, 255), 255, 214, 150)
        c.drawCircle(cx, cy, half * (0.2f + t), paint)
        paint.style = Paint.Style.FILL
        paint.xfermode = null

        // Smoke skirt on the back half only, rising as the fireball dies.
        if (t > 0.3f) {
            val st = (t - 0.3f) / 0.7f
            paint.color = Color.argb((70 * (1f - st)).toInt().coerceIn(0, 90), 48, 46, 44)
            repeat(5) { p ->
                val a = p * (ELITE_TAU / 5f) + 0.7f
                c.drawCircle(
                    cx + cos(a) * fireR * (0.5f + 0.6f * st),
                    cy + sin(a) * fireR * (0.5f + 0.6f * st) - fireR * 0.7f * st,
                    fireR * (0.34f + 0.3f * st),
                    paint
                )
            }
        }
    }
    sheet
}.getOrNull()

