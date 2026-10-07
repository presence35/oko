package com.odesaplay.oko

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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Full-length elite death: projectile flight plus explosion, on the classic 5s clock. */
internal const val ELITE_DEATH_DURATION_MS = 5000L

/** Compressed explosion for quick-boom deaths (intermediate replay groups). */
internal const val ELITE_QUICK_EXPLOSION_LEN_MS = 800L

/**
 * Concurrent-death ceiling for the elite styles. A hard bound, never an expectation: it is
 * only ever reached when a phone sits untouched long enough to bank an overnight replay show.
 */
internal const val ELITE_MAX_DEATHS = 420

/**
 * How much of the explosion body a style is allowed to spend on a single blast this frame.
 *
 * This is the whole budget conversation in one number, so no style has to know why it is
 * being held back: [EliteDeathBase] hands the same [budget] to every elite and expects the
 * cheapest tier above [CHEAP] to still be recognisably that style.
 */
enum class ExplosionBudget {
    /** Ring + core flash only. Always drawn, never skipped — the blast must still read. */
    CHEAP,

    /** Flat body: cached bitmap or gradient, a short ember tail. */
    SOLID,

    /** The style's full body: shader pass, debris, long tail. */
    FULL;

    val richerThanCheap: Boolean get() = this != CHEAP
}

/** Explosion personality per threat type — the elite styles read it, never the raw [ThreatType]. */
enum class EliteKind { SHAHED, BALLISTIC, CRUISE, FPV, AVIATION, KAB, RECON, UNKNOWN;

    companion object {
        fun fromType(type: ThreatType): EliteKind = when (type) {
            ThreatType.SHAHED -> SHAHED
            ThreatType.BALLISTIC -> BALLISTIC
            ThreatType.CRUISE_MISSILE -> CRUISE
            ThreatType.FPV_LOITERING -> FPV
            ThreatType.AVIATION -> AVIATION
            ThreatType.KAB -> KAB
            ThreatType.RECON -> RECON
            ThreatType.UNKNOWN -> UNKNOWN
        }
    }
}

/** One tracked death. [payload] is scratch space owned by the concrete style. */
class EliteDeath(
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
    val kind: EliteKind,
    val seed: Int
) {
    var payload: Any? = null
}

/**
 * Shared scaffolding for the elite explosion styles: the death list, the spawn/prune contract
 * and the whole pre-explosion half of the lifecycle (pre-spawned icon, gliding threat, inbound
 * projectile from just off the screen edge). A concrete style supplies only [drawExplosion] —
 * the part that actually differs — so each elite file contains just its own look.
 *
 * It also owns the frame budget. Cost here is per-blast turbulence times concurrent blasts, so
 * no phone escapes it: the base times its own explosion work, steps [budget] down when the
 * rolling average overruns, and steps back up when there is headroom. The caller never decides
 * how to degrade — it only says [economyMode] ("this is a show, not a single shot").
 */
abstract class EliteDeathBase : DeathAnimation {

    protected val deaths = mutableListOf<EliteDeath>()

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    override val isActive: Boolean get() = deaths.isNotEmpty()

    override var highQuality: Boolean = true

    /** True during a replay show: hold back the full body so hundreds of blasts stay fluid. */
    override var economyMode: Boolean = false

    private fun syncActive() {
        val nowActive = deaths.isNotEmpty()
        if (_active.value != nowActive) _active.value = nowActive
    }

    override fun isActiveFor(id: String?): Boolean = id != null && deaths.any { it.id == id }

    override fun rebasePendingOrigins(newOrigin: () -> LatLng?) {
        val now = SystemClock.elapsedRealtime()
        for (d in deaths) if (now < d.start) d.origin = newOrigin()
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
        if (deaths.size >= ELITE_MAX_DEATHS) return
        val start = SystemClock.elapsedRealtime() + fireAtDelayMs.coerceAtLeast(0L)
        val seed = (id?.hashCode() ?: 0) xor start.toInt() xor type.ordinal * 0x9E3779B9.toInt()
        deaths.add(
            EliteDeath(
                id = id,
                geo = geo,
                startGeo = startGeo,
                origin = origin,
                start = start,
                icon = icon,
                rotationDeg = rotationDeg,
                alpha = alpha,
                dud = false,
                durationMs = if (quickBoom) DEATH_EXPLOSION_START_MS + ELITE_QUICK_EXPLOSION_LEN_MS
                else ELITE_DEATH_DURATION_MS,
                kind = EliteKind.fromType(type),
                seed = seed
            )
        )
        syncActive()
    }

    override fun spawnDud(id: String?, geo: LatLng, origin: LatLng?) {
        if (origin == null || deaths.size >= ELITE_MAX_DEATHS) return
        deaths.add(
            EliteDeath(
                id = id,
                geo = geo,
                startGeo = geo,
                origin = origin,
                start = SystemClock.elapsedRealtime(),
                icon = null,
                rotationDeg = 0f,
                alpha = 1f,
                dud = true,
                durationMs = DEATH_EXPLOSION_START_MS,
                kind = EliteKind.UNKNOWN,
                seed = (id?.hashCode() ?: 0) xor 0x1F123BB5
            )
        )
        syncActive()
    }

    override fun clear() {
        deaths.clear()
        budget = ExplosionBudget.FULL
        syncActive()
    }

    private var bulletSprite: BulletSprite? = null

    /** What each blast may spend this frame. Recomputed once per draw, never per death. */
    private var budget: ExplosionBudget = ExplosionBudget.FULL

    override fun draw(
        canvas: Canvas,
        density: Float,
        viewWidth: Float,
        viewHeight: Float,
        resources: android.content.res.Resources,
        zoom: Double,
        project: (lat: Double, lon: Double) -> PointF?
    ) {
        if (deaths.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        deaths.removeAll {
            val elapsed = now - it.start
            val done = elapsed > it.durationMs || (it.dud && elapsed > DEATH_EXPLOSION_START_MS)
            if (done) releasePayload(it)
            done
        }
        if (deaths.isEmpty()) {
            syncActive()
            return
        }
        val bullet = bulletSprite ?: BulletSprite(resources).also { bulletSprite = it }
        val zoomScale = ((zoom - 9.0) / 4.0 * 2.0 + 1.0).coerceIn(1.0, 3.0).toFloat()
        val frameBudget = frameBudget(deaths.size)
        val startedNs = System.nanoTime()
        // A replay show or a stampede gets the flat body everywhere; a single shot gets the rest.
        var richLeft = if (economyMode) 0 else MAX_RICH_PER_FRAME
        val richCeiling = when (frameBudget) {
            ExplosionBudget.FULL -> MAX_RICH_PER_FRAME
            ExplosionBudget.SOLID -> 1
            ExplosionBudget.CHEAP -> 0
        }
        if (richLeft > richCeiling) richLeft = richCeiling

        for (d in deaths) {
            val rawElapsed = now - d.start
            if (rawElapsed < 0L) {
                drawIcon(canvas, d, project(d.startGeo.lat, d.startGeo.lon) ?: continue)
                continue
            }
            val dur = d.durationMs.toFloat()
            val boomT = DEATH_EXPLOSION_START_MS / dur
            val boomLenT = (d.durationMs - DEATH_EXPLOSION_START_MS) / dur
            val pt = project(d.geo.lat, d.geo.lon) ?: continue
            val x = pt.x
            val y = pt.y
            val t = (rawElapsed.toFloat() / dur).coerceIn(0f, 1f)

            if (t < boomT) {
                val p = if (boomT > 0f) (t / boomT).coerceIn(0f, 1f) else 1f
                val iconPt = project(
                    d.startGeo.lat + (d.geo.lat - d.startGeo.lat) * p,
                    d.startGeo.lon + (d.geo.lon - d.startGeo.lon) * p
                )
                if (iconPt != null) drawIcon(canvas, d, iconPt)
            }

            if (t in 0f..boomT && d.origin != null) {
                drawProjectile(canvas, d, x, y, t / boomT.coerceAtLeast(0.0001f), density, viewWidth, viewHeight, project, bullet)
            }

            if (t >= boomT && !d.dud) {
                val e = ((t - boomT) / boomLenT).coerceIn(0f, 1f)
                val spend = when {
                    richLeft > 0 -> { richLeft--; ExplosionBudget.FULL }
                    frameBudget.richerThanCheap -> ExplosionBudget.SOLID
                    else -> ExplosionBudget.CHEAP
                }
                drawExplosion(
                    canvas = canvas,
                    d = d,
                    x = x,
                    y = y,
                    e = e,
                    fade = 1f - e,
                    density = density,
                    maxR = maxRFor(spend, density, zoomScale),
                    elapsedMs = (t - boomT) * dur,
                    budget = spend
                )
            }
        }
        recordFrameCost(System.nanoTime() - startedNs)
    }

    /** The one thing a concrete elite style has to define. `e` runs 0..1 across the explosion. */
    protected abstract fun drawExplosion(
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
    )

    /** Per-death scratch cleanup on prune; styles holding recycled bitmaps override it. */
    protected open fun releasePayload(d: EliteDeath) {}

    protected fun drawIcon(canvas: Canvas, d: EliteDeath, pt: PointF?) {
        val icon = d.icon ?: return
        val pt = pt ?: return
        val w = icon.intrinsicWidth.coerceAtLeast(1) / 2f
        val h = icon.intrinsicHeight.coerceAtLeast(1) / 2f
        icon.alpha = (d.alpha * 255).toInt()
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(d.rotationDeg)
        icon.setBounds(-w.toInt(), -h.toInt(), w.toInt(), h.toInt())
        icon.draw(canvas)
        canvas.restore()
    }

    private fun drawProjectile(
        canvas: Canvas,
        d: EliteDeath,
        x: Float,
        y: Float,
        p: Float,
        density: Float,
        viewWidth: Float,
        viewHeight: Float,
        project: (Double, Double) -> PointF?,
        bullet: BulletSprite
    ) {
        val oPt = project(d.origin!!.lat, d.origin!!.lon) ?: return
        val ox = oPt.x
        val oy = oPt.y
        val dx = x - ox
        val dy = y - oy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= 1f) return

        // Enter from just outside the viewport edge, so the shell never pops in mid-screen.
        var tnear = -Float.MAX_VALUE
        var tfar = Float.MAX_VALUE
        val t1x = -ox / dx
        val t2x = (viewWidth - ox) / dx
        tnear = maxOf(tnear, minOf(t1x, t2x))
        tfar = minOf(tfar, maxOf(t1x, t2x))
        val t1y = -oy / dy
        val t2y = (viewHeight - oy) / dy
        tnear = maxOf(tnear, minOf(t1y, t2y))
        tfar = minOf(tfar, maxOf(t1y, t2y))
        if (tfar <= 0f || !tnear.isFinite()) return

        val inv = (10f * density) / dist
        val sx = ox + dx * tnear - dx * inv
        val sy = oy + dy * tnear - dy * inv
        // A dud keeps flying past the impact instead of stopping dead on it.
        val diag = sqrt(viewWidth * viewWidth + viewHeight * viewHeight)
        val tx = if (d.dud) x + dx / dist * diag else x
        val ty = if (d.dud) y + dy / dist * diag else y
        val bx = sx + (tx - sx) * p
        val by = sy + (ty - sy) * p
        bullet.draw(canvas, bx, by, (Math.toDegrees(atan2((ty - sy).toDouble(), (tx - sx).toDouble())) + 90).toFloat(), density)
    }

    companion object {
        /** How many blasts per frame may spend the full body before the rest drop a tier. */
        const val MAX_RICH_PER_FRAME = 3

        /** Frame cost above this (ns) counts as a miss — roughly half a 60fps budget. */
        private const val COST_HIGH_NS = 9_000_000L

        /** Comfortably under budget; sustained, this is the signal to climb back up. */
        private const val COST_LOW_NS = 4_500_000L

        /** Consecutive frames needed to move one tier, so a single slow frame can't demote. */
        private const val STEP_FRAMES = 12

        fun hotCore(kind: EliteKind): Int = when (kind) {
            EliteKind.BALLISTIC -> Color.WHITE
            EliteKind.AVIATION -> Color.rgb(190, 225, 255)
            EliteKind.CRUISE -> Color.rgb(255, 180, 80)
            EliteKind.FPV -> Color.rgb(255, 230, 140)
            else -> Color.WHITE
        }

        fun ember(kind: EliteKind): Int = when (kind) {
            EliteKind.FPV -> Color.rgb(255, 220, 100)
            EliteKind.SHAHED -> Color.rgb(255, 160, 40)
            EliteKind.BALLISTIC -> Color.rgb(255, 240, 180)
            EliteKind.AVIATION -> Color.rgb(180, 220, 255)
            EliteKind.CRUISE -> Color.rgb(255, 190, 90)
            else -> Color.rgb(255, 193, 7)
        }

        /** Slow expansion of the shock ring as a fraction of the explosion's max radius. */
        fun shockRadius(maxR: Float, e: Float, speed: Float = 1.35f): Float =
            maxR * (0.1f + speed * e)
    }

    private var costAvgNs = COST_LOW_NS
    private var hotFrames = 0
    private var calmFrames = 0

    /**
     * The self-throttle. It times this style's own explosion work — not the whole frame, which
     * includes tile rendering the style has no business judging — and moves one tier at a time
     * after a sustained run, so a single slow frame can never demote a style mid-blast.
     */
    private fun frameBudget(concurrent: Int): ExplosionBudget {
        // A replay show, or more live blasts than anyone can afford in full: a flat body is the
        // right answer regardless of how fast the frame actually was.
        if (economyMode || concurrent > MAX_RICH_PER_FRAME) return ExplosionBudget.SOLID
        budget = when {
            costAvgNs > COST_HIGH_NS -> {
                calmFrames = 0
                if (++hotFrames < STEP_FRAMES) budget
                else {
                    hotFrames = 0
                    if (budget == ExplosionBudget.FULL) ExplosionBudget.SOLID else ExplosionBudget.CHEAP
                }
            }
            costAvgNs < COST_LOW_NS -> {
                hotFrames = 0
                if (++calmFrames < STEP_FRAMES) budget
                else {
                    calmFrames = 0
                    when (budget) {
                        ExplosionBudget.CHEAP -> ExplosionBudget.SOLID
                        else -> ExplosionBudget.FULL
                    }
                }
            }
            else -> {
                hotFrames = 0
                calmFrames = 0
                budget
            }
        }
        return budget
    }

    private fun recordFrameCost(costNs: Long) {
        if (costNs <= 0L) return
        costAvgNs = (costAvgNs * 3 + costNs) / 4
    }

    private fun maxRFor(budget: ExplosionBudget, density: Float, zoomScale: Float): Float =
        when (budget) {
            ExplosionBudget.FULL -> 52f * density * zoomScale
            ExplosionBudget.SOLID -> 44f * density * zoomScale
            ExplosionBudget.CHEAP -> 34f * density * zoomScale
        }
}

internal const val ELITE_TAU = (2.0 * PI).toFloat()