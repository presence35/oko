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
 * this once per card, so a body declares its own look here instead of the UI hardcoding a
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
    SHARDS,

    /** More than one of the above — the shuffled style's mark. */
    MIXED
}

/**
 * Optional companion to [DeathAnimation] and [ExplosionBody]: a body with one-time setup (a shader
 * compile, a baked sheet) exposes it so the cost is paid before the first blast instead of during
 * it. Bodies with nothing to prepare simply don't implement it.
 */
interface ExplosionWarmer {
    fun warmUp()
}

/**
 * One explosion look. The engine ([DeathEngine]) owns everything a death does — flight, pruning,
 * budget — and hands a body the blast window only.
 *
 * Bodies are plugins with no lifecycle of their own: they keep whatever caches they want for the
 * session ([release] is the single hook for per-death scratch) and are never asked to decide
 * anything about a blast except how it looks.
 */
interface ExplosionBody {
    /** The style this body implements — its identity for the picker and the shuffle bag. */
    val id: DeathAnimationStyle

    /** What it looks like, for the settings card thumbnail. */
    val preview: ExplosionPreview

    /**
     * Blast radius in px at density 1, before the zoom multiplier. Each body keeps the exact size
     * it shipped with, which is why this is the body's number and not the engine's.
     */
    val baseRadius: Float

    /**
     * The blast radius for this frame. Bodies own their sizing outright — Simple, for one, never
     * grew with zoom and must not start now.
     */
    fun radius(density: Float, zoomScale: Float): Float = baseRadius * density * zoomScale

    /** Kinds this body suits; empty means it suits anything and can be dealt for any threat. */
    val kinds: Set<EliteKind> get() = emptySet()

    /** `e` runs 0..1 across the explosion; `budget` says how much the body may spend. */
    fun draw(
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
    )

    /** Per-death scratch cleanup on prune. Bodies with nothing per-death leave it alone. */
    fun release(death: EliteDeath) {}
}

/**
 * Swappable death-animation engine. The map view only ever talks to this interface
 * ([DeathFxController.overlay]), so styles come and go without touching UI code. There is one
 * implementation, [DeathEngine]; which look it draws is decided by the bodies it was built with,
 * which in turn come from the [death_animation_style] pref.
 */
interface DeathAnimation {
    val active: StateFlow<Boolean>
    val isActive: Boolean
    var highQuality: Boolean

    /**
     * Set by the flourish controller while a replay show is running, so a style holding hundreds
     * of blasts can play a cheaper pass without the caller knowing how to degrade it.
     *
     * This is a performance brake and nothing else. Whether flourish is *allowed* to happen at
     * all is `moraleGate.isOn` — never confuse the two.
     */
    var economyMode: Boolean

    /** What this style looks like, for the settings card thumbnail. */
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

/** Every body the engine can draw with, keyed by the style that owns it. */
private val bodiesByStyle: Map<DeathAnimationStyle, () -> ExplosionBody> = mapOf(
    DeathAnimationStyle.SIMPLE to { SimpleBody() },
    DeathAnimationStyle.CLASSIC to { ClassicBody() },
    DeathAnimationStyle.ADVANCED to { AdvancedBody() },
    DeathAnimationStyle.BREACH to { BreachBody() },
    DeathAnimationStyle.FILM to { FilmBody() },
    DeathAnimationStyle.AFTERMATH to { AftermathBody() }
)

/** The styles a shuffled run may deal from. */
internal val shufflePool: List<DeathAnimationStyle> = bodiesByStyle.keys.toList()

/**
 * The engine for [this] style. A fixed style gets its one body; [DeathAnimationStyle.RANDOM] gets
 * the whole pool with per-threat dealing, which is only affordable because there is one engine —
 * one death list, one cap, one budget — behind every body.
 */
fun DeathAnimationStyle.create(): DeathAnimation {
    // RANDOM has no single body: the engine draws every blast with a dealt one instead.
    if (this == DeathAnimationStyle.RANDOM) {
        return DeathEngine(shufflePool.map { bodiesByStyle.getValue(it)() }, shuffled = true)
    }
    return DeathEngine(listOf(bodiesByStyle.getValue(this)()))
}

/**
 * The one sanctioned bullet sprite: the old short shell, no trail. Shared by every style so the
 * projectile reads the same no matter the explosion picked.
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
 * A cached white-hot radial puff, shared by the bodies that build their look from offset blobs.
 * Keeping it here means no body pays for a per-frame `RadialGradient`.
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