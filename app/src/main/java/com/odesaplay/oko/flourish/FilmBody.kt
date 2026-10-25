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
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Frames per baked sheet. 16 over the explosion tail is enough shape change with embers on top. */
private const val ELITE_FRAMES = 16

/** 4x4 grid — a 16x1 sheet would be a 16x-tall bitmap, which some devices refuse outright. */
private const val ELITE_SHEET_COLS = 4

/** Sheet frame edge in density-independent px. */
private const val ELITE_FRAME_DP = 96

/**
 * The threat kinds that get their own sheet, baked on demand. The rest share one warm sheet rather
 * than each costing their own — Film is the cheapest style on purpose, and a full type list would
 * make a first kill pay for eight bakes, which is exactly the stutter this design exists to remove.
 */
private val ELITE_DISTINCT_KINDS = listOf(EliteKind.BALLISTIC, EliteKind.AVIATION)

/** Everything not in [ELITE_DISTINCT_KINDS] shares one sheet, since they share one colour anyway. */
private val ELITE_SHARED_KIND = EliteKind.SHAHED

private val EliteBakeExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "elite-film-bake").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}

/**
 * Film — the pre-baked one and the cheapest tier by design.
 *
 * The explosion body is a 16-frame 4x4 sheet played back with a single `drawBitmap` per death. The
 * important property is that **nothing about a frame is computed per draw**: the sheet is baked per
 * kind once, off the main thread, and tinted at bake time. An earlier version applied a colour
 * filter per frame to fake per-type colour, which forced the filtered bitmap to be re-sampled every
 * frame for every concurrent death — it looked free and was the single biggest cost in the style.
 *
 * Three sheets can exist at once (ballistic / aviation / shared); anything past that shares the
 * shared sheet.
 */
class FilmBody : ExplosionBody, ExplosionWarmer {

    override val id: DeathAnimationStyle = DeathAnimationStyle.FILM

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 180, 70),
        shape = ExplosionShape.BALL,
        scale = 1f
    )

    override val baseRadius: Float = 52f

    /** A clean baked ball suits every threat, so Film is dealt from for anything. */
    override val kinds: Set<EliteKind> get() = emptySet()

    /** Kind -> baked sheet. Written by the bake thread, read on the draw thread. */
    private val sheets = HashMap<EliteKind, Bitmap>(4)

    /** Kinds with a bake in flight, so a stampede queues one bake and not one per death. */
    private val baking = HashSet<EliteKind>()

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

    /** Bake every sheet this session will likely need, so the first kill never waits on one. */
    override fun warmUp() {
        val density = bakedDensity
        for (kind in ELITE_DISTINCT_KINDS + listOf(ELITE_SHARED_KIND)) sheetFor(kind, density)
    }

    override fun draw(
        canvas: Canvas,
        death: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        elapsedMs: Float,
        budget: ExplosionBudget,
        hq: Boolean
    ) {
        val bmp = sheetFor(sheetKind(death.kind), density)
        if (bmp == null) {
            // Still baking: an additive ring carries the blast, so the first kill of a type is
            // never a blank frame.
            framePaint.color = Color.argb((170 * fade).toInt().coerceIn(0, 255), 255, 196, 96)
            canvas.drawCircle(x, y, shockRadius(maxR, e, 1.1f).coerceAtLeast(1f), framePaint)
            return
        }

        val frames = when (budget) {
            ExplosionBudget.FULL -> ELITE_FRAMES
            ExplosionBudget.SOLID -> ELITE_FRAMES / 2
            ExplosionBudget.CHEAP -> 6
        }
        val idx = (e * frames).toInt().coerceIn(0, frames - 1)
        val fs = bmp.width / ELITE_SHEET_COLS
        val col = idx % ELITE_SHEET_COLS
        val row = idx / ELITE_SHEET_COLS
        scratch.set(col * fs, row * fs, col * fs + fs, row * fs + fs)
        framePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        val r = maxR * 1.2f * radiusScale(budget)
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(bmp, scratch, dst, framePaint)

        if (budget == ExplosionBudget.FULL) {
            drawEmbers(canvas, death, x, y, e, fade, density, maxR, hq)
        }
    }

    private fun radiusScale(budget: ExplosionBudget): Float = when (budget) {
        ExplosionBudget.FULL -> 1f
        ExplosionBudget.SOLID -> 0.9f
        ExplosionBudget.CHEAP -> 0.7f
    }

    /** Kinds that own a sheet; everything else renders as the shared warm one. */
    private fun sheetKind(kind: EliteKind): EliteKind =
        if (kind in ELITE_DISTINCT_KINDS) kind else ELITE_SHARED_KIND

    private fun drawEmbers(
        canvas: Canvas,
        death: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        hq: Boolean
    ) {
        val count = if (hq) 16 else 9
        val rnd = Random(death.seed xor 0x2545F491)
        val color = ember(death.kind)
        repeat(count) {
            val ang = rnd.nextFloat() * ELITE_TAU
            val speed = 0.3f + rnd.nextFloat() * 1.3f
            val life = (1f - e * (0.6f + rnd.nextFloat() * 0.4f)).coerceIn(0f, 1f)
            if (life <= 0.03f) return@repeat
            // Drag ease-out like the shards: fast launch, then the air wins.
            val dist = maxR * speed * 1.3f * (1f - exp(-2.0f * e))
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

    /** The baked sheet for [kind], scheduling its bake on first use. */
    private fun sheetFor(kind: EliteKind, density: Float): Bitmap? {
        if (bakedDensity != density) {
            synchronized(sheets) {
                sheets.values.forEach { it.recycle() }
                sheets.clear()
                baking.clear()
            }
            bakedDensity = density
        }
        synchronized(sheets) {
            sheets[kind]?.let { return it }
            if (baking.add(kind)) {
                val size = (ELITE_FRAME_DP * density).toInt().coerceIn(48, 320)
                val mid = ember(kind)
                val hot = hotCore(kind)
                EliteBakeExecutor.execute {
                    val baked = bakeSheet(size, mid, hot)
                    synchronized(sheets) {
                        baking.remove(kind)
                        // A density change recycled the slots while this ran; the bake is stale.
                        if (bakedDensity == density && baked != null) sheets[kind] = baked
                    }
                }
            }
        }
        return null
    }
}

/** One sheet, tinted at bake time with this kind's palette — see [FilmBody] for why. */
private fun bakeSheet(frame: Int, mid: Int, hot: Int): Bitmap? = runCatching {
    val rows = ELITE_FRAMES / ELITE_SHEET_COLS
    val sheet = Bitmap.createBitmap(frame * ELITE_SHEET_COLS, frame * rows, Bitmap.Config.ARGB_8888)
    val c = Canvas(sheet)
    val paint = Paint().apply { isAntiAlias = true }
    val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    val half = frame / 2f
    val smoke = SmokePuff()

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
                    Color.argb(alpha, Color.red(hot), Color.green(hot), Color.blue(hot)),
                    Color.argb((alpha * 0.55f).toInt(), Color.red(mid), Color.green(mid), Color.blue(mid)),
                    Color.argb(0, Color.red(mid), Color.green(mid), Color.blue(mid))
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

        // Smoke skirt on the back half only, rising as the fireball dies. Same baked billows the
        // live styles draw, baked into the sheet here so Film keeps costing one drawBitmap a death.
        if (t > 0.3f) {
            val st = (t - 0.3f) / 0.7f
            val alpha = (150 * (1f - st)).toInt().coerceIn(0, 190)
            repeat(5) { p ->
                val a = p * (ELITE_TAU / 5f) + 0.7f
                // Keyed on the puff, not the frame: a billow must keep its shape as it climbs,
                // otherwise the sheet shimmers.
                smoke.draw(
                    c,
                    cx + cos(a) * fireR * (0.5f + 0.6f * st),
                    cy + sin(a) * fireR * (0.5f + 0.6f * st) - fireR * 0.7f * st,
                    fireR * (0.34f + 0.3f * st),
                    alpha,
                    0x51DE + p * 0x9E37
                )
            }
        }
    }
    sheet
}.getOrNull()