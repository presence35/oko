package com.odesaplay.oko

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.odesaplay.oko.engine.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Explosion tail for the cheap style — flash, rings and sparks, then gone. */
private const val SIMPLE_EXPLOSION_LEN_MS = 1200L
private const val SIMPLE_QUICK_LEN_MS = 600L
private const val SIMPLE_MAX_DEATHS = 420

private class SimpleDeath(
    val id: String?,
    val geo: LatLng,
    val startGeo: LatLng = geo,
    var origin: LatLng?,
    val start: Long,
    val icon: Drawable?,
    val rotationDeg: Float,
    val alpha: Float,
    val dud: Boolean,
    val durationMs: Long,
    val sparks: FloatArray
)

/**
 * Non-HD death animation: same flight (gliding icon, shared short bullet) and a flat
 * flash + expanding rings + a few sparks. No bitmaps, no per-frame allocation —
 * the fallback for low-end devices.
 */
class SimpleDeathOverlay : DeathAnimation {

    private val deaths = mutableListOf<SimpleDeath>()

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    override val isActive: Boolean get() = deaths.isNotEmpty()

    override var highQuality: Boolean = true

    private fun syncActive() {
        val nowActive = deaths.isNotEmpty()
        if (_active.value != nowActive) _active.value = nowActive
    }

    override fun isActiveFor(id: String?): Boolean = id != null && deaths.any { it.id == id }

    override fun rebasePendingOrigins(newOrigin: () -> LatLng?) {
        val now = SystemClock.elapsedRealtime()
        for (d in deaths) {
            if (now < d.start) d.origin = newOrigin()
        }
    }

    override fun spawn(
        id: String?,
        geo: LatLng,
        startGeo: LatLng,
        origin: LatLng?,
        icon: Drawable?,
        rotationDeg: Float,
        alpha: Float,
        quickBoom: Boolean,
        fireAtDelayMs: Long,
        type: ThreatType
    ) {
        if (deaths.size >= SIMPLE_MAX_DEATHS) return
        val start = SystemClock.elapsedRealtime() + fireAtDelayMs.coerceAtLeast(0L)
        val rnd = Random((id?.hashCode() ?: 0) xor start.toInt())
        deaths.add(
            SimpleDeath(
                id, geo, startGeo, origin, start, icon, rotationDeg, alpha, dud = false,
                durationMs = DEATH_EXPLOSION_START_MS +
                    if (quickBoom) SIMPLE_QUICK_LEN_MS else SIMPLE_EXPLOSION_LEN_MS,
                sparks = FloatArray(12) { rnd.nextFloat() * (2.0 * Math.PI).toFloat() }
            )
        )
        syncActive()
    }

    override fun spawnDud(id: String?, geo: LatLng, origin: LatLng?) {
        if (origin == null || deaths.size >= SIMPLE_MAX_DEATHS) return
        deaths.add(
            SimpleDeath(
                id, geo, geo, origin, SystemClock.elapsedRealtime(), null, 0f, 1f,
                dud = true, durationMs = DEATH_EXPLOSION_START_MS, sparks = FloatArray(0)
            )
        )
        syncActive()
    }

    override fun clear() {
        deaths.clear()
        syncActive()
    }

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val flashPaint = Paint().apply { isAntiAlias = true }
    private val sparkPaint = Paint().apply { isAntiAlias = true }
    private var bulletSprite: BulletSprite? = null

    private fun drawIcon(canvas: Canvas, icon: Drawable, x: Float, y: Float, rotationDeg: Float, alpha: Float) {
        val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
        val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
        icon.alpha = (alpha * 255).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotationDeg)
        icon.setBounds(-w.toInt(), -h.toInt(), w.toInt(), h.toInt())
        icon.draw(canvas)
        canvas.restore()
    }

    override fun draw(
        canvas: Canvas,
        density: Float,
        viewWidth: Float,
        viewHeight: Float,
        resources: Resources,
        zoom: Double,
        project: (lat: Double, lon: Double) -> PointF?
    ) {
        if (deaths.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        deaths.removeAll {
            val elapsed = now - it.start
            elapsed > it.durationMs || (it.dud && elapsed > DEATH_EXPLOSION_START_MS)
        }
        if (deaths.isEmpty()) {
            syncActive()
            return
        }
        val bullet = bulletSprite ?: BulletSprite(resources).also { bulletSprite = it }

        for (d in deaths) {
            val rawElapsed = now - d.start
            if (rawElapsed < 0L) {
                d.icon?.let { icon ->
                    val pt = project(d.startGeo.lat, d.startGeo.lon) ?: continue
                    drawIcon(canvas, icon, pt.x, pt.y, d.rotationDeg, d.alpha)
                }
                continue
            }
            val dur = d.durationMs.toFloat()
            val boomT = DEATH_EXPLOSION_START_MS / dur
            val boomLenT = (d.durationMs - DEATH_EXPLOSION_START_MS) / dur
            val pt = project(d.geo.lat, d.geo.lon) ?: continue
            val x = pt.x
            val y = pt.y
            val t = (rawElapsed.toFloat() / dur).coerceIn(0f, 1f)

            d.icon?.let { icon ->
                if (t < boomT) {
                    val p = if (boomT > 0f) (t / boomT).coerceIn(0f, 1f) else 1f
                    val iconPt = project(
                        d.startGeo.lat + (d.geo.lat - d.startGeo.lat) * p,
                        d.startGeo.lon + (d.geo.lon - d.startGeo.lon) * p
                    )
                    if (iconPt != null) drawIcon(canvas, icon, iconPt.x, iconPt.y, d.rotationDeg, d.alpha)
                }
            }

            if (t in 0f..boomT && d.origin != null) {
                val oPt = project(d.origin!!.lat, d.origin!!.lon)
                if (oPt != null) {
                    val dx = x - oPt.x
                    val dy = y - oPt.y
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist > 1f) {
                        val p = (t / boomT).coerceIn(0f, 1f)
                        val bx = oPt.x + dx * p
                        val by = oPt.y + dy * p
                        bullet.draw(
                            canvas, bx, by,
                            (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90).toFloat(),
                            density
                        )
                    }
                }
            }

            if (t >= boomT && !d.dud) {
                val e = ((t - boomT) / boomLenT).coerceIn(0f, 1f)
                val maxR = 40f * density
                val fade = 1f - e

                flashPaint.alpha = (200 * fade).toInt()
                flashPaint.color = Color.WHITE
                canvas.drawCircle(x, y, maxR * 0.22f * (1f - e * 0.5f), flashPaint)

                ringPaint.strokeWidth = 2.4f * density
                ringPaint.color = Color.argb((220 * fade).toInt(), 255, 213, 0)
                canvas.drawCircle(x, y, maxR * (0.35f + 1.05f * e), ringPaint)

                ringPaint.color = Color.argb((150 * fade).toInt(), 255, 120, 0)
                canvas.drawCircle(x, y, maxR * (0.12f + 1.35f * e), ringPaint)

                sparkPaint.color = Color.argb((255 * fade).toInt(), 255, 193, 7)
                for (a in d.sparks) {
                    val dist = maxR * (0.55f + 0.7f * e)
                    canvas.drawCircle(
                        x + cos(a) * dist,
                        y + sin(a) * dist,
                        2.3f * density * fade,
                        sparkPaint
                    )
                }
            }
        }
    }
}
