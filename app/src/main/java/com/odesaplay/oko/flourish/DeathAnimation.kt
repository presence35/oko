package com.odesaplay.oko

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.odesaplay.oko.engine.LatLng
import kotlinx.coroutines.flow.StateFlow

/**
 * Swappable death-animation engine. The map view only ever talks to this interface
 * ([DeathFxController.overlay]), so styles come and go without touching UI code.
 * Implementations: [SimpleDeathOverlay] (cheap rings + sparks, no bitmaps),
 * [ClassicDeathOverlay] (the original look, frozen) and [ThreatDeathOverlay] (fuller).
 */
interface DeathAnimation {
    val active: StateFlow<Boolean>
    val isActive: Boolean
    var highQuality: Boolean

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
}

/**
 * The one sanctioned bullet sprite: the old short shell, no trail. Shared by all
 * three styles so the projectile reads the same no matter the explosion picked.
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
