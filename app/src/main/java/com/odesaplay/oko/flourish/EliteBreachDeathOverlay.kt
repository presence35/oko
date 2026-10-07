package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Elite style "Breach" — hard directional streaks. Deliberately not another fireball: there is
 * no soft ball here, just a white-hot core punched out of a ring of long shards thrown clear of
 * the impact, crossed by two thin shock rings that outrun everything else and clear fast.
 *
 * The shader pass (API 33+) turns the streaks into hard-edged tapered slivers; below it the same
 * shapes are drawn as rotated bars, so a pre-33 phone still gets streaks and not the soft bloom
 * the other elites use.
 */
class EliteBreachDeathOverlay : EliteDeathBase() {

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 214, 140),
        shape = ExplosionShape.STREAK,
        scale = 1.05f
    )

    private val barPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val corePaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private val shader: android.graphics.RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.graphics.RuntimeShader(BREACH_AGSL) }.getOrNull()
        } else null

    private var streaks: FloatArray? = null
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
        val coreR = maxR * (0.3f * (1f - e * 0.85f)).coerceAtLeast(0f)
        val shardCount = when (budget) {
            ExplosionBudget.FULL -> if (highQuality) 14 else 8
            ExplosionBudget.SOLID -> 6
            ExplosionBudget.CHEAP -> 0
        }
        val shardR = maxR * 1.35f

        if (budget == ExplosionBudget.FULL && shader != null) {
            drawShaderStreaks(canvas, d, x, y, e, fade, shardR, coreR)
        } else {
            drawBarStreaks(canvas, d, x, y, e, fade, density, shardR, shardCount)
        }

        // Two thin rings, deliberately fast and thin — the "clears fast" half of the read.
        val ringAlpha = (215 * fade).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = (1.8f - 1.2f * e).coerceAtLeast(0.7f) * density
        ringPaint.color = Color.argb(ringAlpha, 255, 246, 214)
        canvas.drawCircle(x, y, shockRadius(maxR, e, 1.9f), ringPaint)
        ringPaint.color = Color.argb((ringAlpha * 0.55f).toInt().coerceIn(0, 255), 255, 190, 90)
        canvas.drawCircle(x, y, shockRadius(maxR, e, 1.35f), ringPaint)

        if (budget == ExplosionBudget.CHEAP) return

        // Hot core, punched out rather than glowed.
        corePaint.color = Color.argb((255 * fade).toInt().coerceIn(0, 255), 255, 255, 250)
        canvas.drawCircle(x, y, coreR.coerceAtLeast(1f), corePaint)
        if (budget == ExplosionBudget.FULL) {
            corePaint.color = Color.argb((190 * fade).toInt().coerceIn(0, 255), 255, 226, 170)
            canvas.drawCircle(x, y, coreR.coerceAtLeast(1f) * 1.7f, corePaint)
        }
    }

    private fun drawShaderStreaks(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        shardR: Float,
        coreR: Float
    ) {
        val sh = shader ?: return
        val emberColor = ember(d.kind)
        sh.setFloatUniform("uCenter", x, y)
        sh.setFloatUniform("uExtent", shardR)
        sh.setFloatUniform("uE", e)
        sh.setFloatUniform("uSeed", (d.seed and 0xFFFF) / 65535f * 8f)
        sh.setFloatUniform(
            "uHot",
            Color.red(emberColor) / 255f,
            Color.green(emberColor) / 255f,
            Color.blue(emberColor) / 255f
        )
        barPaint.shader = sh
        barPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        val half = shardR * 1.02f
        canvas.drawRect(x - half, y - half, x + half, y + half, barPaint)
        barPaint.shader = null
    }

    private fun drawBarStreaks(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        shardR: Float,
        count: Int
    ) {
        if (count <= 0) return
        val arr = streaks ?: buildStreaks(d).also { streaks = it }
        val color = ember(d.kind)
        var drawn = 0
        var i = 0
        while (i < arr.size && drawn < count) {
            val ang = arr[i]
            val speed = arr[i + 1]
            val len = arr[i + 2]
            val width = arr[i + 3]
            val life = (1f - e * (0.75f + len * 0.2f)).coerceIn(0f, 1f)
            if (life > 0.04f) {
                val reach = shardR * (0.28f + 0.95f * e) * speed
                // Long, thin, radial: this is the whole identity of the style.
                val halfLen = reach * (0.35f + 0.75f * len)
                val halfWidth = (1.1f + width * 1.9f) * density * life
                barPaint.color = Color.argb(
                    (240 * fade * life).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat())
                dst.set(0f, -halfWidth, halfLen * 2f, halfWidth)
                canvas.drawBitmap(streakSprite(), null, dst, barPaint)
                canvas.restore()
                drawn++
            }
            i += 4
        }
    }

    /** One white sliver, stretched per streak — no per-frame gradient, no per-frame allocation. */
    private fun streakSprite(): Bitmap {
        streakSpriteBmp?.let { return it }
        val bmp = Bitmap.createBitmap(48, 16, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawPath(
            Path().apply {
                moveTo(48f, 8f)
                lineTo(0f, 1f)
                lineTo(6f, 8f)
                lineTo(0f, 15f)
                close()
            },
            Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
            }
        )
        streakSpriteBmp = bmp
        return bmp
    }

    private var streakSpriteBmp: Bitmap? = null

    private fun buildStreaks(d: EliteDeath): FloatArray {
        val arr = FloatArray(MAX_STREAKS * 4)
        val rnd = Random(d.seed xor 0x51ED270B)
        for (i in arr.indices step 4) {
            arr[i] = rnd.nextFloat() * ELITE_TAU
            arr[i + 1] = 0.55f + rnd.nextFloat() * 0.75f
            arr[i + 2] = 0.35f + rnd.nextFloat() * 0.85f
            arr[i + 3] = 0.4f + rnd.nextFloat() * 1.4f
        }
        streaks = arr
        return arr
    }

    private companion object {
        /** Fixed width so the shared array never resizes mid-blast. */
        const val MAX_STREAKS = 16
    }
}

private const val BREACH_AGSL = """
uniform float2 uCenter;
uniform float uExtent;
uniform float uE;
uniform float uSeed;
uniform float3 uHot;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

half4 main(in float2 fragCoord) {
    float2 p = (fragCoord - uCenter) / uExtent;
    float r = length(p);
    if (r > 1.0) return half4(0.0);
    float ang = atan(p.y, p.x);

    // A handful of hard spokes: narrow in angle, long in radius, thinning as they fly.
    float spokes = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float a = fi * 0.8976 + uSeed;
        float da = abs(atan(sin(ang - a), cos(ang - a)));
        float w = 0.02 + 0.05 * hash(float2(fi, uSeed));
        spokes += smoothstep(w, 0.0, da) * (0.55 + 0.6 * hash(float2(fi + 3.0, uSeed)));
    }
    spokes *= smoothstep(1.0, 0.15, r) * smoothstep(0.06, 0.3, r);

    float core = smoothstep(0.3, 0.0, r);
    float a = clamp(spokes * (1.0 - uE) + core, 0.0, 1.0);
    float3 col = mix(uHot, float3(1.0), core * 0.8);
    return half4(half3(col * a), half(a));
}
"""