package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** One debris piece torn from the threat icon. Velocity is px/ms at density=1. */
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

/**
 * The Advanced style's blast, moved verbatim from the original overlay: the icon shatters into
 * irregular chunks with drag, spin and gravity, sparks fly on random angles, and each threat type
 * brings its own fireball colour, smoke and debris. This is the default style, so its body is the
 * most demanding of the six — it is also the one the budget has to trim first.
 */
class AdvancedBody : ExplosionBody {

    override val id: DeathAnimationStyle = DeathAnimationStyle.ADVANCED

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 160, 40),
        shape = ExplosionShape.SHARDS,
        scale = 1.05f
    )

    override val baseRadius: Float = 46f

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

    /** Per-death plan: shard table, spark table, the rasterized icon and its source rects. */
    private class Scratch(
        val shards: Array<Shard>,
        val sparks: Array<Spark>,
        val iconBmp: Bitmap?,
        val srcRects: Array<Rect>?
    )

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
        val kind = death.kind
        val scale = radiusScale(budget)
        val r = maxR * scale
        val br = r * e

        // Impact punch (first ~8% of explosion).
        if (e < 0.08f) {
            val punch = 1f - e / 0.08f
            flashPaint.color = Color.argb((200 * punch).toInt(), 255, 255, 255)
            canvas.drawCircle(x, y, r * 0.35f * punch, flashPaint)
        }

        // 1. Glow
        val glow = explosionGlow(density)
        flashPaint.alpha = (220 * fade).toInt()
        flashPaint.color = Color.WHITE
        reuseRect.set(x - br, y - br, x + br, y + br)
        canvas.drawBitmap(glow, null, reuseRect, flashPaint)

        // 2. Core flash — colour per type
        flashPaint.alpha = (200 * fade).toInt()
        flashPaint.color = when (kind) {
            EliteKind.BALLISTIC -> Color.WHITE
            EliteKind.AVIATION -> Color.rgb(180, 220, 255)
            EliteKind.CRUISE -> Color.rgb(255, 180, 80)
            EliteKind.FPV -> Color.rgb(255, 230, 140)
            else -> Color.WHITE
        }
        val coreMul = if (kind == EliteKind.BALLISTIC) 0.28f else 0.22f
        canvas.drawCircle(x, y, br * coreMul, flashPaint)

        // 3. Shockwave rings (faster outer wave + slower fire ring)
        ringPaint.strokeWidth = 2.4f * density
        val ringAlpha = if (kind == EliteKind.BALLISTIC) 255 else 220
        ringPaint.color = Color.argb((ringAlpha * fade).toInt(), 255, 213, 0)
        canvas.drawCircle(x, y, r * (0.35f + 1.05f * e), ringPaint)

        ringPaint.color = Color.argb((150 * fade).toInt(), 255, 120, 0)
        canvas.drawCircle(x, y, r * (0.12f + 1.35f * e), ringPaint)

        if (hq) {
            ringPaint.strokeWidth = 1.2f * density
            ringPaint.color = Color.argb((100 * fade).toInt(), 255, 255, 210)
            canvas.drawCircle(x, y, r * (0.55f + 1.15f * e), ringPaint)
        }

        if (budget == ExplosionBudget.CHEAP) return

        val scratch = death.payload as? Scratch ?: buildScratch(death, density, hq).also { death.payload = it }

        // 4. Icon shards — random direction, irregular UV chunks, drag + gravity
        val iconBmp = scratch.iconBmp
        if (iconBmp != null && scratch.srcRects != null) {
            val g = 0.000045f * density
            for ((i, s) in scratch.shards.withIndex()) {
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
                canvas.drawBitmap(iconBmp, scratch.srcRects[i], shardDst, shardPaint)
                canvas.restore()
            }
        }

        // 5. Sparks — random angles & speeds (not a regular ring)
        val sparkColor = when (kind) {
            EliteKind.FPV -> Color.rgb(255, 220, 100)
            EliteKind.SHAHED -> Color.rgb(255, 160, 40)
            EliteKind.BALLISTIC -> Color.rgb(255, 240, 180)
            EliteKind.AVIATION -> Color.rgb(180, 220, 255)
            EliteKind.CRUISE -> Color.rgb(255, 190, 90)
            else -> Color.rgb(255, 193, 7)
        }
        for (sp in scratch.sparks) {
            val dist = r * sp.speed * (0.35f + 0.9f * e)
            val sx = x + cos(sp.ang) * dist
            val sy = y + sin(sp.ang) * dist - r * 0.08f * e * e
            val sparkLife = (1f - e * (0.7f + sp.phase * 0.3f)).coerceIn(0f, 1f)
            if (sparkLife <= 0.02f) continue
            sparkPaint.color = Color.argb(
                (255 * fade * sparkLife).toInt().coerceIn(0, 255),
                Color.red(sparkColor),
                Color.green(sparkColor),
                Color.blue(sparkColor)
            )
            val sparkR = (if (kind == EliteKind.FPV) 1.6f else 2.3f) * density * sp.size * sparkLife
            canvas.drawCircle(sx, sy, sparkR, sparkPaint)
        }

        // 6. Smoke / large debris by type (HD)
        if (hq) {
            when (kind) {
                EliteKind.SHAHED, EliteKind.CRUISE, EliteKind.KAB, EliteKind.BALLISTIC ->
                    drawSmokePuffs(canvas, x, y, e, fade, r, death.seed, rising = true)
                EliteKind.AVIATION -> {
                    drawSmokePuffs(canvas, x, y, e, fade, r, death.seed, rising = false)
                    drawLargeDebris(canvas, x, y, e, fade, density, r, death.seed)
                }
                EliteKind.FPV -> {
                    if (e > 0.2f) {
                        drawSmokePuffs(canvas, x, y, e * 0.7f, fade * 0.6f, r * 0.7f, death.seed, rising = false)
                    }
                }
                else -> {
                    if (e > 0.15f) {
                        drawSmokePuffs(canvas, x, y, e, fade * 0.8f, r, death.seed, rising = false)
                    }
                }
            }
        }
    }

    override fun release(death: EliteDeath) {
        (death.payload as? Scratch)?.iconBmp?.recycle()
        death.payload = null
    }

    private fun radiusScale(budget: ExplosionBudget): Float = when (budget) {
        ExplosionBudget.FULL -> 1f
        ExplosionBudget.SOLID -> 0.85f
        ExplosionBudget.CHEAP -> 0.65f
    }

    private fun buildScratch(death: EliteDeath, density: Float, hq: Boolean): Scratch {
        val shards = createShards(death.kind, density, hq, death.seed, death.inboundAngleRad)
        val sparks = createSparks(death.kind, hq, death.seed)
        val icon = death.icon
        var bmp: Bitmap? = null
        var rects: Array<Rect>? = null
        if (icon != null) {
            val iw = icon.intrinsicWidth.coerceAtLeast(1)
            val ih = icon.intrinsicHeight.coerceAtLeast(1)
            bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).also { b ->
                val iconCanvas = Canvas(b)
                icon.alpha = 255
                icon.setBounds(0, 0, iw, ih)
                icon.draw(iconCanvas)
            }
            rects = Array(shards.size) { i ->
                val s = shards[i]
                Rect(
                    (s.sx * iw).toInt().coerceIn(0, iw - 1),
                    (s.sy * ih).toInt().coerceIn(0, ih - 1),
                    ((s.sx + s.sw) * iw).toInt().coerceIn(((s.sx * iw).toInt() + 1).coerceAtMost(iw), iw),
                    ((s.sy + s.sh) * ih).toInt().coerceIn(((s.sy * ih).toInt() + 1).coerceAtMost(ih), ih)
                )
            }
        }
        return Scratch(shards, sparks, bmp, rects)
    }

    private fun explosionGlow(density: Float): Bitmap {
        glowBitmap?.let { return it }
        val size = (96 * density).toInt().coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = size / 2f
        c.drawCircle(
            half, half, half, Paint().apply {
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
            }
        )
        glowBitmap = bmp
        return bmp
    }

    /** Irregular icon chunks from a coarse UV grid, with wide random velocity, drag and fade. */
    private fun createShards(
        kind: EliteKind,
        density: Float,
        hq: Boolean,
        seed: Int,
        inboundAngleRad: Float?
    ): Array<Shard> {
        val rnd = Random(seed)
        val cols = if (hq) 4 else 3
        val rows = if (hq) 4 else 3
        data class Cell(var c0: Int, var r0: Int, var c1: Int, var r1: Int, var keep: Boolean)
        val cells = ArrayList<Cell>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cells += Cell(c, r, c + 1, r + 1, keep = true)
            }
        }
        // Merge ~30% of cells with a neighbour for irregular chunk sizes.
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
        val kept = cells.filter { it.keep }.toMutableList()
        val dropTarget = (kept.size * 0.15f).toInt().coerceAtLeast(0)
        repeat(dropTarget) {
            if (kept.size <= 4) return@repeat
            kept.removeAt(rnd.nextInt(kept.size))
        }

        val baseSpeed = when (kind) {
            EliteKind.BALLISTIC -> 0.13f
            EliteKind.FPV -> 0.18f
            EliteKind.AVIATION -> 0.09f
            EliteKind.CRUISE -> 0.12f
            EliteKind.SHAHED -> 0.11f
            else -> 0.10f
        }
        val spinScale = when (kind) {
            EliteKind.FPV -> 1.35f
            EliteKind.AVIATION -> 0.4f
            EliteKind.BALLISTIC -> 0.55f
            else -> 0.7f
        }
        val upwardBias = when (kind) {
            EliteKind.BALLISTIC -> -0.06f
            EliteKind.SHAHED -> -0.02f
            EliteKind.AVIATION -> -0.01f
            else -> -0.025f
        }

        return Array(kept.size) { i ->
            val cell = kept[i]
            val sx = cell.c0 / cols.toFloat()
            val sy = cell.r0 / rows.toFloat()
            val sw = (cell.c1 - cell.c0) / cols.toFloat()
            val sh = (cell.r1 - cell.r0) / rows.toFloat()
            val jx = (rnd.nextFloat() - 0.5f) * 0.04f
            val jy = (rnd.nextFloat() - 0.5f) * 0.04f

            var ang = rnd.nextFloat() * ELITE_TAU
            if (inboundAngleRad != null && rnd.nextFloat() < 0.45f) {
                // Cone opposite the flight direction (±~80°).
                ang = inboundAngleRad + PI.toFloat() + (rnd.nextFloat() - 0.5f) * 1.4f
            }
            val speedMul = 0.3f + rnd.nextFloat().pow(0.65f) * 1.5f
            val speed = baseSpeed * speedMul * density
            val vx = cos(ang) * speed
            val vy = sin(ang) * speed + upwardBias * density * (0.6f + rnd.nextFloat() * 0.8f)

            val area = sw * sh
            val sizeMul = when (kind) {
                EliteKind.AVIATION -> 1.1f + area * 2.2f + rnd.nextFloat() * 0.5f
                EliteKind.FPV -> 0.55f + area * 1.4f + rnd.nextFloat() * 0.35f
                EliteKind.BALLISTIC -> 0.85f + area * 1.6f
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

    private fun createSparks(kind: EliteKind, hq: Boolean, seed: Int): Array<Spark> {
        val rnd = Random(seed xor 0xA5A5A5)
        val count = when (kind) {
            EliteKind.FPV -> if (hq) 16 else 11
            EliteKind.SHAHED -> if (hq) 12 else 8
            EliteKind.BALLISTIC -> if (hq) 14 else 9
            EliteKind.AVIATION -> if (hq) 11 else 7
            else -> if (hq) 10 else 7
        }
        return Array(count) {
            Spark(
                ang = rnd.nextFloat() * ELITE_TAU,
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
        maxR: Float,
        seed: Int,
        rising: Boolean
    ) {
        val rnd = Random(seed xor 0x5F3759DF)
        val n = if (rising) 6 else 4
        repeat(n) { i ->
            val a = rnd.nextFloat() * ELITE_TAU
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
            val ang = rnd.nextFloat() * ELITE_TAU
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
}