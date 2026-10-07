package com.odesaplay.oko

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Simple style's blast: a flash, two expanding rings and a handful of sparks, then gone. No
 * bitmaps and nothing allocated per frame — the fallback for a phone that cannot afford more.
 */
class SimpleBody : ExplosionBody {

    override val id: DeathAnimationStyle = DeathAnimationStyle.SIMPLE

    override val preview: ExplosionPreview = ExplosionPreview(
        hot = Color.WHITE,
        mid = Color.rgb(255, 213, 0),
        shape = ExplosionShape.RINGS
    )

    override val baseRadius: Float = 40f

    /** Simple has always been zoom-independent; the others grow as the map does. */
    override fun radius(density: Float, zoomScale: Float): Float = baseRadius * density

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val flashPaint = Paint().apply { isAntiAlias = true }
    private val sparkPaint = Paint().apply { isAntiAlias = true }

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
        if (budget == ExplosionBudget.CHEAP) {
            // Rings are the whole identity here, so even the floor keeps one.
            ringPaint.strokeWidth = 2.4f * density
            ringPaint.color = Color.argb((200 * fade).toInt(), 255, 213, 0)
            canvas.drawCircle(x, y, maxR * (0.35f + 1.05f * e), ringPaint)
            return
        }

        flashPaint.alpha = (200 * fade).toInt()
        flashPaint.color = Color.WHITE
        canvas.drawCircle(x, y, maxR * 0.22f * (1f - e * 0.5f), flashPaint)

        ringPaint.strokeWidth = 2.4f * density
        ringPaint.color = Color.argb((220 * fade).toInt(), 255, 213, 0)
        canvas.drawCircle(x, y, maxR * (0.35f + 1.05f * e), ringPaint)

        ringPaint.color = Color.argb((150 * fade).toInt(), 255, 120, 0)
        canvas.drawCircle(x, y, maxR * (0.12f + 1.35f * e), ringPaint)

        sparkPaint.color = Color.argb((255 * fade).toInt(), 255, 193, 7)
        val dist = maxR * (0.55f + 0.7f * e)
        val sparkR = 2.3f * density * fade
        repeat(12) {
            val a = it * (ELITE_TAU / 12f)
            canvas.drawCircle(x + cos(a) * dist, y + sin(a) * dist, sparkR, sparkPaint)
        }
    }
}