package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Elite style C — hybrid. Canvas is the source of truth: the body is a layered gradient fireball
 * with real orbiting sparks and a debris skirt, drawn on every API level. On API 33+ the same
 * fireball quad is swapped for an AGSL shader pass, so the upgrade lands inside one style instead
 * of splitting the look across two preferences.
 *
 * See [EliteShaderDeathOverlay] for the shader alone and [EliteBakedDeathOverlay] for the fully
 * baked version of this same silhouette.
 */
class EliteHybridDeathOverlay : EliteDeathBase() {

    private val shader: android.graphics.RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.graphics.RuntimeShader(HYBRID_AGSL) }.getOrNull()
        } else null

    private val bodyPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val flatPaint = Paint().apply { isAntiAlias = true }
    private val sparkPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val shardPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private var fireball: Bitmap? = null
    private var bakedDensity = 0f
    private val dst = RectF()

    /** Embers are seeded per kind, so the whole overlay shares one field. */
    private var emberField: FloatArray? = null

    override fun releasePayload(d: EliteDeath) {
        super.releasePayload(d)
        // Per-death payload is not used by this style; nothing to recycle per death.
    }

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
        val fireR = maxR * (0.14f + 0.9f * (1f - e * e))
        val ringR = shockRadius(maxR, e, 1.4f)
        val extent = maxOf(fireR, ringR, 1f)

        drawBody(canvas, d, x, y, e, fade, density, fireR, ringR, extent)
        drawDebris(canvas, d, x, y, e, fade, density, maxR, hq)
        drawSparks(canvas, d, x, y, e, fade, density, maxR, hq)
    }

    private fun drawBody(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        fireR: Float,
        ringR: Float,
        extent: Float
    ) {
        val sh = shader
        if (sh != null) {
            val hot = hotCore(d.kind)
            val mid = ember(d.kind)
            sh.setFloatUniform("uCenter", x, y)
            sh.setFloatUniform("uExtent", extent)
            sh.setFloatUniform("uFire", fireR / extent)
            sh.setFloatUniform("uRing", ringR / extent)
            sh.setFloatUniform("uE", e)
            sh.setFloatUniform("uSeed", (d.seed and 0xFFFF) / 65535f * 8f)
            sh.setFloatUniform("uHot", Color.red(hot) / 255f, Color.green(hot) / 255f, Color.blue(hot) / 255f)
            sh.setFloatUniform("uMid", Color.red(mid) / 255f, Color.green(mid) / 255f, Color.blue(mid) / 255f)
            bodyPaint.shader = sh
            bodyPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val half = extent * 1.02f
            canvas.drawRect(x - half, y - half, x + half, y + half, bodyPaint)
            bodyPaint.shader = null
            return
        }

        // Canvas path: cached radial fireball + additive shock ring.
        val bmp = fireballBitmap(density)
        val r = extent
        bodyPaint.alpha = (230 * fade).toInt().coerceIn(0, 255)
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(bmp, null, dst, bodyPaint)

        flatPaint.style = Paint.Style.STROKE
        flatPaint.strokeWidth = (3f - 1.8f * e).coerceAtLeast(0.8f) * density
        flatPaint.color = Color.argb((170 * fade).toInt().coerceIn(0, 255), 255, 214, 150)
        canvas.drawCircle(x, y, ringR.coerceAtLeast(1f), flatPaint)

        bodyPaint.color = Color.argb((210 * fade).toInt().coerceIn(0, 255), 255, 255, 235)
        canvas.drawCircle(x, y, (fireR * (0.4f - 0.24f * e)).coerceAtLeast(1f), bodyPaint)
    }

    private fun fireballBitmap(density: Float): Bitmap {
        if (bakedDensity != density) {
            fireball?.recycle()
            fireball = null
            bakedDensity = density
        }
        fireball?.let { return it }
        val size = (140 * density).toInt().coerceIn(48, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = size / 2f
        c.drawCircle(
            half, half, half, Paint().apply {
                isAntiAlias = true
                shader = RadialGradient(
                    half, half, half,
                    intArrayOf(
                        Color.argb(255, 255, 255, 235),
                        Color.argb(230, 255, 178, 60),
                        Color.argb(120, 226, 96, 22),
                        Color.argb(0, 130, 44, 12)
                    ),
                    floatArrayOf(0f, 0.32f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )
        fireball = bmp
        return bmp
    }

    /** Angular debris chunks thrown clear of the blast, thinning as they cool. */
    private fun drawDebris(
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
        val count = if (hq) 7 else 4
        val rnd = Random(d.seed xor 0x7F4A7C15)
        val color = ember(d.kind)
        repeat(count) {
            val ang = rnd.nextFloat() * ELITE_TAU
            val speed = 0.5f + rnd.nextFloat() * 1.15f
            val life = (1f - e * (0.55f + rnd.nextFloat() * 0.4f)).coerceIn(0f, 1f)
            if (life <= 0.05f) return@repeat
            val dist = maxR * speed * (0.15f + 1.2f * e)
            val sx = x + cos(ang) * dist
            val sy = y + sin(ang) * dist + maxR * 0.05f * e * e
            val w = (2.2f + rnd.nextFloat() * 3.2f) * density * life
            val h = w * (0.35f + rnd.nextFloat() * 0.5f)
            shardPaint.color = Color.argb(
                (210 * fade * life).toInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            canvas.save()
            canvas.translate(sx, sy)
            canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat())
            canvas.drawRect(-w, -h, w, h, shardPaint)
            canvas.restore()
        }
    }

    private fun drawSparks(
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
        val arr = emberField ?: buildEmberField(d.kind, hq)
        val color = ember(d.kind)
        var i = 0
        while (i < arr.size) {
            val ang = arr[i]
            val speed = arr[i + 1]
            val size = arr[i + 2]
            val phase = arr[i + 3]
            val life = (1f - e * (0.6f + phase * 0.4f)).coerceIn(0f, 1f)
            if (life > 0.03f) {
                val dist = maxR * speed * (0.18f + 1.2f * e)
                sparkPaint.color = Color.argb(
                    (255 * fade * life).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                canvas.drawCircle(
                    x + cos(ang) * dist,
                    y + sin(ang) * dist - maxR * 0.08f * e * e,
                    2f * density * size * life,
                    sparkPaint
                )
            }
            i += 4
        }
    }

    private fun buildEmberField(kind: EliteKind, hq: Boolean): FloatArray {
        val arr = FloatArray((if (hq) 22 else 13) * 4)
        val rnd = Random(kind.ordinal * 6_364_136 + 0x2545F491)
        for (i in arr.indices step 4) {
            arr[i] = rnd.nextFloat() * ELITE_TAU
            arr[i + 1] = 0.3f + rnd.nextFloat() * 1.35f
            arr[i + 2] = 0.55f + rnd.nextFloat() * 1.15f
            arr[i + 3] = rnd.nextFloat()
        }
        emberField = arr
        return arr
    }
}

/** The hybrid's shader pass: a tighter, less turbulent fireball than the pure-shader style. */
private const val HYBRID_AGSL = """
uniform float2 uCenter;
uniform float uExtent;
uniform float uFire;
uniform float uRing;
uniform float uE;
uniform float uSeed;
uniform float3 uHot;
uniform float3 uMid;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float dd = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, dd, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p *= 2.11;
        a *= 0.5;
    }
    return v;
}

half4 main(in float2 fragCoord) {
    float2 d = (fragCoord - uCenter) / uExtent;
    float r = length(d);
    float ang = atan(d.y, d.x);
    float2 q = float2(cos(ang), sin(ang)) * (1.4 + uSeed);

    float n = fbm(q * 2.0 + float2(uSeed, -uE * 1.8));
    float fire = uFire * (0.72 + n * 0.6);
    float body = smoothstep(fire, fire * 0.1, r);
    float turb = smoothstep(0.0, 0.3, fire - r + (n - 0.5) * 0.28);
    float3 col = mix(uMid, uHot, clamp(turb, 0.0, 1.0));
    float a = body * turb * (1.0 - uE * 0.9);

    float ring = smoothstep(0.05, 0.0, abs(r - uRing)) * (1.0 - uE);
    col += float3(1.0, 0.94, 0.78) * ring * 0.7;
    a = max(a, ring * 0.7);

    float edge = smoothstep(1.0, 0.95, r);
    return half4(half3(col * a), half(a * edge));
}
"""