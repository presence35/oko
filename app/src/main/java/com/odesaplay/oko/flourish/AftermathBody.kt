package com.odesaplay.oko

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Aftermath — the slow one, and the only one the older styles cannot imitate: a wide soft bloom
 * that keeps burning after the blast has gone, angular debris thrown clear and then falling, and
 * a smoke column that climbs out of the impact and hangs.
 *
 * The body is a shared [ElitePuff] plus lobes and a low shock ring; on API 33+ a wide AGSL bloom
 * replaces the lobes. Its shader is deliberately low-octave — the read is "soft", so extra
 * per-pixel detail buys nothing here and costs on every blast.
 */
class AftermathBody : ExplosionBody, ExplosionWarmer {

    override val id: DeathAnimationStyle = DeathAnimationStyle.AFTERMATH

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.rgb(255, 236, 190),
        mid = Color.rgb(255, 140, 50),
        shape = ExplosionShape.BLOOM,
        scale = 1.15f
    )

    override val baseRadius: Float = 52f

    /** Falling debris and a hanging column suit the heavy, long-burning targets. */
    override val kinds: Set<EliteKind> = setOf(
        EliteKind.BALLISTIC, EliteKind.CRUISE, EliteKind.KAB, EliteKind.SHAHED, EliteKind.RECON, EliteKind.UNKNOWN
    )

    private val bodyPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val flatPaint = Paint().apply { isAntiAlias = true }
    private val debrisPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val smoke = SmokePuff()
    private val debrisPath = Path()

    private val puff = ElitePuff(sizeDp = 128)
    private val corePuff = ElitePuff(sizeDp = 48)

    /**
     * Compiled on first use, not at construction — see [BreachBody.shader] for why. [warmUp] moves
     * the cost off the blast path entirely.
     */
    private val shader: android.graphics.RuntimeShader? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.graphics.RuntimeShader(AFTERMATH_AGSL) }.getOrNull()
        } else null
    }

    override fun warmUp() {
        shader
        smoke.warm()
    }

    private var debris: FloatArray? = null
    private var column: FloatArray? = null

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
        drawBody(canvas, death, x, y, e, fade, density, maxR, budget)
        if (budget == ExplosionBudget.CHEAP) return
        drawDebris(canvas, death, x, y, e, fade, density, maxR, if (budget == ExplosionBudget.FULL && hq) 8 else 4)
        if (budget == ExplosionBudget.FULL) drawColumn(canvas, death, x, y, e, maxR)
    }

    private fun drawBody(
        canvas: Canvas,
        death: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        budget: ExplosionBudget
    ) {
        val mid = ember(death.kind)
        // Sized to the bloom itself, not to maxR: the puff plus its shader quad is the single
        // most expensive thing a body draws, and it never needs to cover the whole radius.
        val bloomR = maxR * (0.5f + 0.4f * (1f - e)) * radiusScale(budget)

        val sh = if (budget == ExplosionBudget.FULL) shader else null
        if (sh != null) {
            val hot = hotCore(death.kind)
            sh.setFloatUniform("uCenter", x, y)
            sh.setFloatUniform("uExtent", bloomR)
            sh.setFloatUniform("uE", e)
            sh.setFloatUniform("uSeed", (death.seed and 0xFFFF) / 65535f * 8f)
            sh.setFloatUniform("uHot", Color.red(hot) / 255f, Color.green(hot) / 255f, Color.blue(hot) / 255f)
            sh.setFloatUniform("uMid", Color.red(mid) / 255f, Color.green(mid) / 255f, Color.blue(mid) / 255f)
            bodyPaint.shader = sh
            bodyPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val half = bloomR * 1.02f
            canvas.drawRect(x - half, y - half, x + half, y + half, bodyPaint)
            bodyPaint.shader = null
        } else {
            // Two offset puffs read as a wide soft bloom where one reads as a ball.
            puff.draw(canvas, x, y, bloomR, mid, (185 * fade).toInt().coerceIn(0, 255))
            puff.draw(
                canvas,
                x - bloomR * 0.22f,
                y - bloomR * 0.16f,
                bloomR * 0.66f,
                hotCore(death.kind),
                (150 * fade).toInt().coerceIn(0, 255)
            )
        }

        // The core keeps shrinking long after the bloom stops growing.
        val coreR = bloomR * (0.34f - 0.24f * e)
        corePuff.draw(canvas, x, y, coreR.coerceAtLeast(1f), Color.WHITE, (225 * fade).toInt().coerceIn(0, 255))

        flatPaint.style = Paint.Style.STROKE
        flatPaint.strokeWidth = (2.2f - 1.4f * e).coerceAtLeast(0.8f) * density
        flatPaint.color = Color.argb((120 * fade).toInt().coerceIn(0, 255), 255, 190, 120)
        canvas.drawCircle(x, y, shockRadius(maxR, e, 1.05f).coerceAtLeast(1f), flatPaint)
    }

    private fun radiusScale(budget: ExplosionBudget): Float = when (budget) {
        ExplosionBudget.FULL -> 1f
        ExplosionBudget.SOLID -> 0.85f
        ExplosionBudget.CHEAP -> 0.7f
    }

    /** Angular debris thrown clear of the blast, then falling — the "after" in Aftermath. */
    private fun drawDebris(
        canvas: Canvas,
        death: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        fade: Float,
        density: Float,
        maxR: Float,
        count: Int
    ) {
        val arr = debris ?: buildDebris(death).also { debris = it }
        val color = ember(death.kind)
        var drawn = 0
        var i = 0
        while (i < arr.size && drawn < count) {
            val ang = arr[i]
            val speed = arr[i + 1]
            val size = arr[i + 2]
            val life = (1f - e * (0.5f + arr[i + 3] * 0.45f)).coerceIn(0f, 1f)
            if (life > 0.05f) {
                val reach = maxR * speed * (0.25f + 1.05f * e)
                // Debris arcs: it keeps going out, then gravity takes it down.
                val sx = x + cos(ang) * reach
                val sy = y + sin(ang) * reach + maxR * 0.28f * e * e
                // Tapered slivers, never squares: the old rect reached 0.88 aspect on big
                // chunks, so the blast read as Lego. Length along travel, narrow across it.
                val len = (2f + size * 3f) * density * life
                val half = len * (0.10f + size * 0.08f)
                debrisPaint.color = Color.argb(
                    (215 * fade * life).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                debrisPath.reset()
                debrisPath.moveTo(len, 0f)
                debrisPath.lineTo(-len, half)
                debrisPath.lineTo(-len, -half)
                debrisPath.close()
                canvas.save()
                canvas.translate(sx, sy)
                canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 180f * e)
                canvas.drawPath(debrisPath, debrisPaint)
                canvas.restore()
                drawn++
            }
            i += 4
        }
    }

    /**
     * The smoke column: a puff bank that rises out of the impact, thickens as it climbs and fades
     * slowly at the very end. It must not exist from the first frame — a column that is there from
     * impact reads as fog lying over the map, not as aftermath.
     *
     * The motion follows the cheap version of Morton–Taylor–Turner (1956) entrainment that games
     * use instead of fluid sim: each puff is born tiny at a staggered start, rises on an eased
     * curve (buoyancy against drag — slow start, never a jump), widens with distance traveled
     * (r = r0 + alpha·z, thermal cone), dilutes with volume, and drifts outward as it climbs
     * (the vortex-ring mushroom roll). Positions stay pure functions of [e]: no sim state, no
     * allocation, deterministic per seed.
     */
    private fun drawColumn(
        canvas: Canvas,
        death: EliteDeath,
        x: Float,
        y: Float,
        e: Float,
        maxR: Float
    ) {
        if (e < COLUMN_START) return
        val span = 1f - COLUMN_START
        // Holds its opacity across the tail instead of fading with the blast, so the column is
        // still there when the fireball is gone — that is what makes it aftermath and not smoke.
        val tail = 1f - (e - COLUMN_START) * 0.42f
        if (tail <= 0.02f) return
        val arr = column ?: buildColumn(death).also { column = it }
        val rise = maxR * 1.35f
        var i = 0
        while (i < arr.size) {
            val ox = arr[i]
            val phase = arr[i + 1]
            val size = arr[i + 2]
            // Staggered birth across the column window: at any moment some puffs are unborn.
            // A puff that fades into place mid-column at full size is what read as BOOM.
            val tau = ((e - COLUMN_START) / span - phase * 0.55f) / (1f - phase * 0.55f)
            if (tau > 0f) {
                val t = tau.coerceAtMost(1f)
                // Eased rise: zero initial velocity, steepening — buoyancy winning over drag.
                val climb = t * t * (1.7f - 0.7f * t)
                val puffY = y - rise * climb
                // Mushroom roll: the ring pushes outer fluid sideways as it climbs.
                val spread = 0.4f + climb + 0.3f * climb * climb
                val puffX = x + maxR * ox * spread
                // Born tiny, widening with distance risen — a column that starts at full width
                // reads as a balloon, one that keeps widening reads as smoke spreading.
                val puffR = maxR * (0.06f + 0.5f * climb) * size
                // Dilutes with volume: opacity follows the radius it grew from.
                val growth = (puffR / (maxR * 0.3f)).coerceIn(0f, 1f)
                // The sprite peaks at full alpha in its core and falls away, so it needs more than
                // the flat fill it replaced to read as the same weight of smoke.
                val alpha = (210 * growth * tail).toInt().coerceIn(0, 235)
                smoke.draw(canvas, puffX, puffY, puffR, alpha, death.seed + i * 0x9E37)
            }
            i += 3
        }
    }

    private fun buildDebris(death: EliteDeath): FloatArray {
        val arr = FloatArray(MAX_DEBRIS * 4)
        val rnd = Random(death.seed xor 0x7F4A7C15)
        for (i in arr.indices step 4) {
            arr[i] = rnd.nextFloat() * ELITE_TAU
            arr[i + 1] = 0.5f + rnd.nextFloat() * 1.15f
            arr[i + 2] = 0.35f + rnd.nextFloat() * 1.1f
            arr[i + 3] = rnd.nextFloat()
        }
        return arr
    }

    private fun buildColumn(death: EliteDeath): FloatArray {
        val arr = FloatArray(MAX_COLUMN * 3)
        val rnd = Random(death.seed xor 0x27D4EB2F)
        for (i in arr.indices step 3) {
            arr[i] = rnd.nextFloat() * 1.2f - 0.6f
            arr[i + 1] = rnd.nextFloat()
            arr[i + 2] = 0.7f + rnd.nextFloat() * 0.9f
        }
        return arr
    }

    private companion object {
        const val MAX_DEBRIS = 8
        const val MAX_COLUMN = 7

        /**
         * The smoke column must not exist from the first frame — a column that is there from
         * impact reads as fog lying over the map, not as aftermath. It only starts once the
         * fireball has properly collapsed into the bloom, then fades up over [COLUMN_FADE_IN]
         * before reaching full.
         */
        const val COLUMN_START = 0.55f
    }
}

/** Wide, soft, low-contrast turbulence — the opposite end of the sheet to Breach's hard spokes. */
private const val AFTERMATH_AGSL = """
uniform float2 uCenter;
uniform float uExtent;
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

// Two octaves, not four: this body is a soft bloom, so the third and fourth octave only cost
// per-pixel ALU on the one style whose read is "smooth" anyway.
float fbm(float2 p) {
    return 0.65 * vnoise(p) + 0.35 * vnoise(p * 2.11);
}

half4 main(in float2 fragCoord) {
    float2 p = (fragCoord - uCenter) / uExtent;
    float r = length(p);
    if (r > 1.0) return half4(0.0);
    float ang = atan(p.y, p.x);

    // Low-frequency lobes only: soft edges, no spikes, wide shoulders.
    float n = fbm(float2(cos(ang), sin(ang)) * (1.1 + uSeed) + float2(uSeed, -uE * 0.7));
    float radius = 0.62 + n * 0.34;
    float body = smoothstep(radius, radius * 0.15, r);
    float soft = smoothstep(radius, radius * 0.55, r);
    float3 col = mix(uMid, uHot, soft);
    float a = body * (1.0 - uE * 0.9);
    return half4(half3(col * a), half(a * soft));
}
"""