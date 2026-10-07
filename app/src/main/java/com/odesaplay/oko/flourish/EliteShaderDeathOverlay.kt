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
 * Elite style A — GPU fireball. The whole explosion body is one AGSL quad per death:
 * a domain-warped fbm fireball collapsing inward plus an additive shock ring, drawn with
 * [android.graphics.RuntimeShader]. One draw call per blast, so it scales to the [ELITE_MAX_DEATHS]
 * ceiling without the draw-call storm a particle body would cost.
 *
 * AGSL exists from API 33. Below that the same impl falls back to a layered gradient fireball so
 * the style is still usable rather than blank — see [fallbackFireball].
 */
class EliteShaderDeathOverlay : EliteDeathBase() {

    private val addPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val emberPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private val shader: android.graphics.RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.graphics.RuntimeShader(FIREBALL_AGSL) }.getOrNull()
        } else null

    /** One shared ember field for the whole overlay — the shape is decor, not per-death identity. */
    private var embers: FloatArray? = null

    private var fallback: Bitmap? = null
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
        val fireR = maxR * (0.15f + 0.85f * (1f - e * e))
        val ringR = shockRadius(maxR, e, 1.5f)
        val extent = maxOf(fireR, ringR, 1f)
        val half = extent * 1.02f

        if (shader != null) {
            drawShaderBody(canvas, d, x, y, e, fade, fireR, ringR, extent, half)
        } else {
            drawFallbackBody(canvas, d, x, y, e, fade, density, fireR, extent)
        }

        drawEmbers(canvas, d, x, y, e, fade, density, maxR, hq)
    }

    private fun drawShaderBody(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        fireR: Float,
        ringR: Float,
        extent: Float,
        half: Float
    ) {
        val sh = shader ?: return
        val hot = hotCore(d.kind)
        val ember = ember(d.kind)
        sh.setFloatUniform("uCenter", x, y)
        sh.setFloatUniform("uExtent", extent)
        sh.setFloatUniform("uFire", fireR / extent)
        sh.setFloatUniform("uRing", ringR / extent)
        sh.setFloatUniform("uE", e)
        sh.setFloatUniform("uSeed", (d.seed and 0xFFFF) / 65535f * 8f)
        sh.setFloatUniform("uHot", Color.red(hot) / 255f, Color.green(hot) / 255f, Color.blue(hot) / 255f)
        sh.setFloatUniform(
            "uMid",
            Color.red(ember) / 255f,
            Color.green(ember) / 255f,
            Color.blue(ember) / 255f
        )
        addPaint.shader = sh
        addPaint.alpha = (255 * fade.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        canvas.drawRect(x - half, y - half, x + half, y + half, addPaint)
        addPaint.shader = null
    }

    /** API < 33 path: a cached gradient fireball — same silhouette, none of the turbulence. */
    private fun drawFallbackBody(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        fireR: Float,
        extent: Float
    ) {
        val bmp = fallback(density)
        val r = extent
        addPaint.alpha = (235 * fade).toInt().coerceIn(0, 255)
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(bmp, null, dst, addPaint)
        addPaint.alpha = (200 * fade * (1f - e)).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, fireR * 0.34f, addPaint.apply { color = hotCore(d.kind) })
        addPaint.color = Color.WHITE
    }

    private fun fallback(density: Float): Bitmap {
        fallback?.let { return it }
        val size = (128 * density).toInt().coerceIn(48, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = size / 2f
        c.drawCircle(
            half, half, half, Paint().apply {
                isAntiAlias = true
                shader = RadialGradient(
                    half, half, half,
                    intArrayOf(
                        Color.argb(255, 255, 255, 240),
                        Color.argb(220, 255, 190, 70),
                        Color.argb(90, 220, 90, 20),
                        Color.argb(0, 120, 40, 10)
                    ),
                    floatArrayOf(0f, 0.35f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )
        fallback = bmp
        return bmp
    }

    /** A small deterministic ember burst — the shader body carries the mass, this carries the tail. */
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
        val arr = embers ?: buildEmbers(d.kind, hq)
        var i = 0
        while (i < arr.size) {
            val ang = arr[i]
            val speed = arr[i + 1]
            val size = arr[i + 2]
            val phase = arr[i + 3]
            val life = (1f - e * (0.65f + phase * 0.35f)).coerceIn(0f, 1f)
            if (life <= 0.03f) {
                i += 4
                continue
            }
            val dist = maxR * speed * (0.2f + 1.15f * e)
            val sx = x + cos(ang) * dist
            val sy = y + sin(ang) * dist - maxR * 0.1f * e * e
            emberPaint.color = Color.argb(
                (255 * fade * life).toInt().coerceIn(0, 255),
                Color.red(ember(d.kind)),
                Color.green(ember(d.kind)),
                Color.blue(ember(d.kind))
            )
            canvas.drawCircle(sx, sy, 2.1f * density * size * life, emberPaint)
            i += 4
        }
    }

    private fun buildEmbers(kind: EliteKind, hq: Boolean): FloatArray {
        val count = (if (hq) 18 else 11) * 4
        val rnd = Random(kind.ordinal * 7919 + 0x3B9ACA07)
        val arr = FloatArray(count)
        for (i in 0 until count step 4) {
            arr[i] = rnd.nextFloat() * ELITE_TAU
            arr[i + 1] = 0.35f + rnd.nextFloat() * 1.25f
            arr[i + 2] = 0.55f + rnd.nextFloat() * 1.1f
            arr[i + 3] = rnd.nextFloat()
        }
        embers = arr
        return arr
    }
}

/**
 * Fireball + shock ring in normalized quad space. `fragCoord` is local canvas space, so the
 * caller only has to hand over a center, an extent and the current radii.
 */
private const val FIREBALL_AGSL = """
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
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}

half4 main(in float2 fragCoord) {
    float2 d = (fragCoord - uCenter) / uExtent;
    float r = length(d);
    float ang = atan(d.y, d.x);
    float2 q = float2(cos(ang), sin(ang)) * (1.6 + uSeed);

    float n = fbm(q * 2.4 + float2(uSeed, -uE * 2.2));
    n = fbm(q * 2.4 + float2(n * 1.8, -uE * 2.6));

    float fire = uFire * (0.6 + n * 0.9);
    float body = smoothstep(fire, fire * 0.08, r);
    float turb = smoothstep(0.0, 0.24, fire - r + (n - 0.5) * 0.38);
    float3 col = mix(uMid, uHot, clamp(turb, 0.0, 1.0));
    float a = body * turb * (1.0 - uE * 0.85);

    float ring = smoothstep(0.06, 0.0, abs(r - uRing)) * (1.0 - uE);
    col += float3(1.0, 0.92, 0.72) * ring * 0.85;
    a = max(a, ring * 0.8);

    float edge = smoothstep(1.0, 0.94, r);
    return half4(half3(col * a), half(a * edge));
}
"""