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
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** One chunk of the icon thrown clear by a Classic blast. */
private data class ClassicShard(
    val dx: Float,
    val dy: Float,
    val spin: Float,
    val sx: Float,
    val sy: Float,
    val sw: Float,
    val sh: Float,
    val sizeMul: Float = 1f
)

/**
 * The Classic style's blast, moved verbatim from the original overlay: a shared glow, a core flash
 * and shock rings coloured per threat type, icon shards, fixed-count sparks, and smoke or debris in
 * HD. Frozen — this body exists so Classic keeps looking exactly as it always has, while sharing
 * the engine's flight and pruning with everything else.
 */
class ClassicBody : ExplosionBody {

    override val id: DeathAnimationStyle = DeathAnimationStyle.CLASSIC

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 193, 7),
        shape = ExplosionShape.BALL
    )

    override val baseRadius: Float = 46f

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val flashPaint = Paint().apply { isAntiAlias = true }
    private val sparkPaint = Paint().apply { isAntiAlias = true }
    private var glowBitmap: Bitmap? = null
    private val reuseRect = RectF()

    /** Per-death shard plan and the rasterized icon, held in the death's scratch slot. */
    private class Scratch(val shards: Array<ClassicShard>, val iconBmp: Bitmap)

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
        val budgetScale = radiusScale(budget)
        val r = maxR * budgetScale
        // Fireball swells fast and collapses slow — same punch-out curve as Film's bake.
        val br = r * if (e < 0.22f) e / 0.22f else (1f - (e - 0.22f) / 0.78f).coerceIn(0f, 1f)

        // 1. Glow
        val glow = explosionGlow(density)
        flashPaint.alpha = (220 * fade).toInt()
        reuseRect.set(x - br, y - br, x + br, y + br)
        canvas.drawBitmap(glow, null, reuseRect, flashPaint)

        // 2. Core flash — colour per type
        flashPaint.alpha = (200 * fade).toInt()
        flashPaint.color = when (kind) {
            EliteKind.BALLISTIC -> Color.WHITE
            EliteKind.AVIATION -> Color.rgb(180, 220, 255)
            EliteKind.CRUISE -> Color.rgb(255, 180, 80)
            else -> Color.WHITE
        }
        val coreMul = if (kind == EliteKind.BALLISTIC) 0.28f else 0.22f
        canvas.drawCircle(x, y, br * coreMul, flashPaint)

        // 3. Shock rings — intensity per type. Sedov–Taylor: violent early expansion,
// then yield. Same endpoints as the old linear tracks.
        ringPaint.strokeWidth = 2.2f * density
        val ringAlpha = if (kind == EliteKind.BALLISTIC) 255 else 220
        ringPaint.color = Color.argb((ringAlpha * fade).toInt(), 255, 213, 0)
        canvas.drawCircle(x, y, r * 1.35f * e.pow(0.4f), ringPaint)

        ringPaint.color = Color.argb((160 * fade).toInt(), 255, 120, 0)
        canvas.drawCircle(x, y, r * 1.4f * e.pow(0.4f), ringPaint)

        if (hq && kind == EliteKind.BALLISTIC) {
            ringPaint.strokeWidth = 1.1f * density
            ringPaint.color = Color.argb((120 * fade).toInt(), 255, 255, 200)
            canvas.drawCircle(x, y, r * 1.6f * e.pow(0.4f), ringPaint)
        }

        if (budget == ExplosionBudget.CHEAP) return

        // 4. Icon shards
        val icon = death.icon
        if (icon != null) {
            val scratch = death.payload as? Scratch
                ?: Scratch(createShards(kind, density, hq, death.id?.hashCode() ?: 0), rasterize(icon))
                    .also { death.payload = it }
            val iw = scratch.iconBmp.width
            val ih = scratch.iconBmp.height
            val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (255 * fade).toInt() }
            for (s in scratch.shards) {
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
                canvas.drawBitmap(scratch.iconBmp, Rect(srcL, srcT, srcR, srcB), RectF(-sz, -sz, sz, sz), shardPaint)
                canvas.restore()
            }
        }

        // 5. Sparks — count & colour per type
        val (sparkCount, sparkColor) = when (kind) {
            EliteKind.FPV -> 10 to Color.rgb(255, 220, 100)
            EliteKind.SHAHED -> 6 to Color.rgb(255, 160, 40)
            EliteKind.BALLISTIC -> 8 to Color.rgb(255, 240, 180)
            EliteKind.AVIATION -> 7 to Color.rgb(180, 220, 255)
            else -> 6 to Color.rgb(255, 193, 7)
        }
        sparkPaint.color = Color.argb(
            (255 * fade).toInt(),
            Color.red(sparkColor), Color.green(sparkColor), Color.blue(sparkColor)
        )
        val sparkDist = r * 1.25f * (1f - exp(-2.2f * e))
        val sparkR = (if (kind == EliteKind.FPV) 1.8f else 2.5f) * density * fade
        val actual = if (hq) sparkCount + 4 else sparkCount
        repeat(actual) { i ->
            val a = 2.0 * PI * i / actual + 0.3 + (i * 0.17)
            canvas.drawCircle(
                x + cos(a).toFloat() * sparkDist,
                y + sin(a).toFloat() * sparkDist - r * 0.08f * e * e,
                sparkR,
                sparkPaint
            )
        }

        // 6. HD-only extras
        if (hq) {
            when (kind) {
                EliteKind.SHAHED -> drawSmokePuffs(canvas, x, y, e, fade, r)
                EliteKind.AVIATION -> drawLargeDebris(canvas, x, y, e, fade, density, r)
                else -> {}
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

    private fun rasterize(icon: android.graphics.drawable.Drawable): Bitmap {
        val iw = icon.intrinsicWidth.coerceAtLeast(1)
        val ih = icon.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            icon.setBounds(0, 0, iw, ih)
            icon.alpha = 255
            icon.draw(c)
        }
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

    private fun createShards(
        kind: EliteKind,
        density: Float,
        hq: Boolean,
        seed: Int
    ): Array<ClassicShard> {
        val rnd = Random(seed)
        val count = when {
            hq && kind == EliteKind.AVIATION -> 12
            hq -> 10
            kind == EliteKind.FPV -> 7
            else -> 8
        }
        return Array(count) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val baseSpeed = when (kind) {
                EliteKind.BALLISTIC -> 0.14f
                EliteKind.FPV -> 0.16f
                EliteKind.AVIATION -> 0.11f
                else -> 0.10f
            }
            val speed = (baseSpeed + rnd.nextFloat() * 0.12f) * density
            val upward = when (kind) {
                EliteKind.BALLISTIC -> -0.04f
                EliteKind.SHAHED -> -0.015f
                else -> -0.02f
            } * density
            ClassicShard(
                dx = cos(ang) * speed,
                dy = sin(ang) * speed + upward,
                spin = (rnd.nextFloat() - 0.5f) * when (kind) {
                    EliteKind.FPV -> 1.1f
                    EliteKind.AVIATION -> 0.45f
                    else -> 0.6f
                },
                sx = rnd.nextFloat() * 0.6f,
                sy = rnd.nextFloat() * 0.6f,
                sw = 0.25f + rnd.nextFloat() * 0.3f,
                sh = 0.25f + rnd.nextFloat() * 0.3f,
                sizeMul = when (kind) {
                    EliteKind.AVIATION -> 1.25f + rnd.nextFloat() * 0.4f
                    EliteKind.FPV -> 0.75f + rnd.nextFloat() * 0.3f
                    else -> 1f
                }
            )
        }
    }

    private fun drawSmokePuffs(
        canvas: Canvas,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        maxR: Float
    ) {
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

    private fun drawLargeDebris(
        canvas: Canvas,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float
    ) {
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
}