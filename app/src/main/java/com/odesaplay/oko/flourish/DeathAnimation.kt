package com.odesaplay.oko

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.odesaplay.oko.engine.LatLng
import kotlinx.coroutines.flow.StateFlow

/**
 * The silhouette a style draws, reduced to what a static thumbnail can show. Settings renders
 * this once per card, so a style declares its own look here instead of the UI hardcoding a
 * phrasebook — a new style gets a recognisable preview for free.
 */
data class ExplosionPreview(
    val hot: Int,
    val mid: Int,
    val shape: ExplosionShape,
    /** Fraction of the thumbnail the blast fills. */
    val scale: Float = 1f
)

/** Body plan of a style, which is what actually distinguishes one explosion from another. */
enum class ExplosionShape {
    /** Flash and expanding rings, no body. */
    RINGS,

    /** A single round ball. */
    BALL,

    /** A wide soft bloom that lingers. */
    BLOOM,

    /** Hard directional streaks thrown clear of the blast. */
    STREAK,

    /** A ball torn into chunks. */
    SHARDS
}

/**
 * Optional companion to [DeathAnimation]: a style that has one-time setup (a shader compile, a
 * baked sheet) exposes it so the controller can pay that cost before the first blast instead of
 * during it. Styles with nothing to prepare simply don't implement it.
 */
interface ExplosionWarmer {
    fun warmUp()
}

/**
 * Swappable death-animation engine. The map view only ever talks to this interface
 * ([DeathFxController.overlay]), so styles come and go without touching UI code.
 * Implementations: [SimpleDeathOverlay] (cheap rings + sparks, no bitmaps),
 * [ClassicDeathOverlay] (the original look, frozen), [ThreatDeathOverlay] (fuller), and the
 * three elites [EliteBreachDeathOverlay] / [EliteFilmDeathOverlay] / [EliteAftermathDeathOverlay],
 * which share [EliteDeathBase] for everything but the explosion body itself. A style with
 * one-time setup implements [ExplosionWarmer] so that cost is paid before the first blast.
 */
interface DeathAnimation {
    val active: StateFlow<Boolean>
    val isActive: Boolean
    var highQuality: Boolean

    /**
     * Set by the flourish controller while a replay show is running, so a style holding hundreds
     * of blasts can play a cheaper pass without the caller knowing how to degrade it.
     */
    var economyMode: Boolean

    /** What this style looks like, for the settings card thumbnail. Defaults to a plain ball. */
    val preview: ExplosionPreview get() = ExplosionPreview(Color.WHITE, Color.rgb(255, 193, 7), ExplosionShape.BALL)

    fun isActiveFor(id: String?): Boolean
    fun rebasePendingOrigins(newOrigin: () -> LatLng?)
    fun spawn(
        id: String? = null,
        geo: LatLng,
        startGeo: LatLng = geo,
        origin: LatLng? = null,
        icon: Drawable? = null,
        rotationDeg: Float = 0f,
        alpha: Float = 1f,
        quickBoom: Boolean = false,
        fireAtDelayMs: Long = 0L,
        type: ThreatType = ThreatType.UNKNOWN
    )
    fun spawnDud(id: String?, geo: LatLng, origin: LatLng?)
    fun clear()
    fun draw(
        canvas: Canvas,
        density: Float,
        viewWidth: Float,
        viewHeight: Float,
        resources: Resources,
        zoom: Double = 10.0,
        project: (lat: Double, lon: Double) -> PointF?
    )
}

fun DeathAnimationStyle.create(): DeathAnimation = when (this) {
    DeathAnimationStyle.SIMPLE -> SimpleDeathOverlay()
    DeathAnimationStyle.CLASSIC -> ClassicDeathOverlay()
    DeathAnimationStyle.ADVANCED -> ThreatDeathOverlay()
    DeathAnimationStyle.BREACH -> EliteBreachDeathOverlay()
    DeathAnimationStyle.FILM -> EliteFilmDeathOverlay()
    DeathAnimationStyle.AFTERMATH -> EliteAftermathDeathOverlay()
}

/**
 * The one sanctioned bullet sprite: the old short shell, no trail. Shared by all
 * styles so the projectile reads the same no matter the explosion picked.
 */
internal class BulletSprite(resources: Resources) {
    private val res = resources
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isAntiAlias = true }
    private val dst = RectF()

    fun draw(canvas: Canvas, x: Float, y: Float, angleDeg: Float, density: Float) {
        val bmp = bitmap
            ?: BitmapFactory.decodeResource(res, R.drawable.bullet)?.also { bitmap = it }
            ?: return
        val longHalf = 11f * density
        val scale = longHalf * 2f / maxOf(bmp.width.toFloat(), bmp.height.toFloat())
        val hw = bmp.width * scale / 2f
        val hh = bmp.height * scale / 2f
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angleDeg)
        dst.set(-hw, -hh, hw, hh)
        canvas.drawBitmap(bmp, null, dst, paint)
        canvas.restore()
    }
}

/**
 * A cached white-hot radial puff, shared by the elite styles. Each elite lays its own lobes and
 * debris over this, which is why no elite pays for a per-frame `RadialGradient`.
 */
internal class ElitePuff(val sizeDp: Int) {
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val dst = RectF()

    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Int) {
        val bmp = bitmap ?: bake().also { bitmap = it }
        paint.color = color
        paint.alpha = alpha
        dst.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun bake(): Bitmap {
        val size = sizeDp * 3
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val half = size / 2f
        Canvas(bmp).drawCircle(
            half, half, half, Paint().apply {
                isAntiAlias = true
                shader = RadialGradient(
                    half, half, half,
                    intArrayOf(
                        Color.argb(255, 255, 255, 255),
                        Color.argb(215, 255, 255, 255),
                        Color.argb(110, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.3f, 0.66f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )
        return bmp
    }
}