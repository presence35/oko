package com.odesaplay.oko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A smoke billow that is baked instead of drawn.
 *
 * `drawCircle` reads as a ball because both halves of the shape are wrong: the silhouette is a
 * circle and the fill is flat. Real smoke is the other way round — the fill is smooth and the
 * *outline* is what is broken up, because the boundary is where entrainment tears a billow apart.
 *
 * A single blob with a noisy radius does not fix that either: it stays rotationally symmetric, so
 * it still reads as a fuzzy disc. This sprite is [LOBES] overlapping kernels strung along one
 * random axis — an elongated, lumpy plume — with the outline displaced by fbm and the interior
 * modulated by a second noise field. The asymmetry is the part that stops it reading as a ball,
 * and it is why a bank of these reads as smoke where identical circles read as bubbles.
 *
 * That noise is baked once into [SHEET], so drawing a puff costs one `drawBitmap` with a reused
 * [Matrix] — no per-frame gradient, no per-frame noise, no allocation, and no API level needed.
 * [ElitePuff] makes the same trade for the fireball.
 */
internal class SmokePuff {

    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val matrix = Matrix()

    /** Pays the bake off the blast path. The sheet is process-wide, so any one body warms it. */
    fun warm() {
        SHEET
    }

    /**
     * @param seed stable per puff, not per frame — it picks the variant and the rotation, so a puff
     *   keeps its shape across the whole animation instead of reshuffling every draw.
     */
    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Int, seed: Int) {
        if (alpha < 3 || radius < 1f) return
        val h = mix(seed)
        val sprite = SHEET[(h ushr 7) % SHEET.size]
        val half = sprite.width * 0.5f
        val scale = radius * 2f / sprite.width
        matrix.setTranslate(-half, -half)
        matrix.postScale(scale, scale)
        matrix.postRotate(((h ushr 17) and 0xFF) * (360f / 256f))
        matrix.postTranslate(x, y)
        paint.alpha = alpha.coerceAtMost(255)
        canvas.drawBitmap(sprite, matrix, paint)
    }

    private companion object {
        /** Baked resolution, not displayed size — a billow is drawn 20-60 px across. */
        const val SIZE = 96

        /** Distinct silhouettes. Three is enough once rotation and scale vary them. */
        const val VARIANTS = 3

        /** Kernels per billow, and how far apart they sit, as a fraction of the sprite. */
        const val LOBES = 3
        const val SPACING = 0.32f

        /** Kernel size. Summed kernels are what make the outline bumpy; the tail is the softness. */
        const val RADIUS = 0.52f
        const val FALLOFF = 1.9f

        /** How far fbm drags the outline in or out, as a fraction of a kernel radius. */
        const val WARP = 0.40f
        const val EDGE_FREQ = 2.1f

        /** Saturating merge: solid where kernels overlap, thin at the tails. */
        const val SAT = 2.2f

        /** Interior density, so the body is not an even wash of one value. */
        const val GRAIN_FREQ = 3.1f

        const val TAU = 6.2831855f

        /** Neutral smoke. The old per-frame random tint was flicker, not variety. */
        const val RED = 52
        const val GREEN = 50
        const val BLUE = 48

        val SHEET: Array<Bitmap> by lazy { Array(VARIANTS) { bake(it) } }

        fun mix(seed: Int): Int {
            var h = seed * -0x61C88647
            h = h xor (h ushr 16)
            h *= -0x7A143595
            return h xor (h ushr 13)
        }

        fun bake(variant: Int): Bitmap {
            val seed = 0x2F17 + variant * 0x9E37
            val pixels = IntArray(SIZE * SIZE)
            val half = SIZE * 0.5f

            // Lobe centres on one random axis — this is what makes a billow a plume and not a disc.
            val theta = hash(variant, 99, seed) * TAU
            val axisX = cos(theta)
            val axisY = sin(theta)
            val lobeX = FloatArray(LOBES)
            val lobeY = FloatArray(LOBES)
            val lobeR = FloatArray(LOBES)
            for (k in 0 until LOBES) {
                val along = (k - (LOBES - 1) * 0.5f) * SPACING
                val across = (hash(variant, k, seed + 3) - 0.5f) * 0.28f
                lobeX[k] = axisX * along - axisY * across
                lobeY[k] = axisY * along + axisX * across
                lobeR[k] = RADIUS * (0.72f + 0.56f * hash(variant, k, seed + 13))
            }

            for (y in 0 until SIZE) {
                val v = (y + 0.5f - half) / half
                for (x in 0 until SIZE) {
                    val u = (x + 0.5f - half) / half
                    // One displacement per pixel rather than per lobe: it tears the outline and the
                    // interior with the same field, for a third of the noise reads.
                    val warp = (fbm(u * EDGE_FREQ, v * EDGE_FREQ, seed, 2) - 0.5f) * WARP
                    var density = 0f
                    for (k in 0 until LOBES) {
                        val dx = u - lobeX[k]
                        val dy = v - lobeY[k]
                        val d = sqrt(dx * dx + dy * dy) / lobeR[k] - warp
                        density += exp(-FALLOFF * d * d)
                    }
                    val grain = fbm(u * GRAIN_FREQ, v * GRAIN_FREQ, seed + 0x99, 2)
                    val a = ((1f - exp(-SAT * density)) * (0.78f + 0.44f * grain)).coerceIn(0f, 1f)
                    val alpha = (a * 255f + 0.5f).toInt()
                    pixels[y * SIZE + x] = (alpha shl 24) or (RED shl 16) or (GREEN shl 8) or BLUE
                }
            }
            return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        }
    }
}

/** Value noise — no permutation table, so the only state is the seed. */
private fun vnoise(x: Float, y: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val fx = x - xi
    val fy = y - yi
    val ux = fx * fx * (3f - 2f * fx)
    val uy = fy * fy * (3f - 2f * fy)
    val top = lerp(hash(xi, yi, seed), hash(xi + 1, yi, seed), ux)
    val bottom = lerp(hash(xi, yi + 1, seed), hash(xi + 1, yi + 1, seed), ux)
    return lerp(top, bottom, uy)
}

private fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
    var sum = 0f
    var norm = 0f
    var amp = 1f
    var freq = 1f
    for (o in 0 until octaves) {
        sum += amp * vnoise(x * freq, y * freq, seed + o * 0x3D4)
        norm += amp
        amp *= 0.5f
        freq *= 2.03f
    }
    return sum / norm
}

private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 0x27D4EB2D xor y * 0x165667B1 xor seed
    h = h xor (h ushr 15)
    h *= 0x2C1B3C6D
    h = h xor (h ushr 12)
    return (h ushr 8) * (1f / 16777216f)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
