package com.odesaplay.oko

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.odesaplay.oko.engine.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/** Total length of a death: projectile flight plus explosion, on one clock for every style. */
internal const val DEATH_DURATION_MS = 5000L

/** The projectile hits and the explosion begins this many ms into the animation. */
const val DEATH_EXPLOSION_START_MS = 2000L

/** Explosion window ends this many ms after it starts; the neutralized card fades across it. */
const val DEATH_EXPLOSION_LEN_MS = DEATH_DURATION_MS - DEATH_EXPLOSION_START_MS

/** Compressed explosion for quick-boom deaths (intermediate replay groups). */
internal const val QUICK_EXPLOSION_LEN_MS = 800L

/**
 * Concurrent-death ceiling. A hard bound, never an expectation: it is only ever reached when a
 * phone sits untouched long enough to bank an overnight replay show. Global on purpose — there is
 * exactly one engine, so a cap here is the cap for the whole map no matter how the deaths are styled.
 */
internal const val MAX_DEATHS = 420

/**
 * How much of the explosion body a blast may spend this frame.
 *
 * This is the whole performance conversation in one enum, so no body has to know why it is being
 * held back: the engine hands the same [ExplosionBudget] to every body and expects the cheapest
 * tier above [CHEAP] to still be recognisably that style.
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

/** Explosion personality per threat type — bodies read it, never the raw [ThreatType]. */
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

/** One tracked death, plus the body that will draw it. [payload] is the body's own scratch. */
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
    val seed: Int,
    /** Assigned once at spawn. Fixed styles get their one body; a shuffled engine deals one. */
    val body: ExplosionBody
) {
    var payload: Any? = null

    /**
     * Screen-space bearing of the inbound shot, resolved once on the first explosion frame. Only
     * Advanced reads it (to bias shards away from where the threat came from), but computing it
     * here keeps the projection lambda out of bodies.
     */
    var inboundAngleRad: Float? = null
}

/**
 * The one engine behind every explosion style. It owns the whole lifecycle — the death list, the
 * spawn/prune contract, the pre-spawned icon, the gliding threat, the inbound projectile, the dud
 * fly-past and the frame budget — and delegates exactly one thing to a style: how a blast looks,
 * via [ExplosionBody].
 *
 * That split is what makes a per-threat shuffled style possible without a composite. There is one
 * death list, one [MAX_DEATHS] cap, one `active` flow and one `economyMode` no matter how many
 * different styles are on screen at once; each death simply remembers which body draws it.
 */
class DeathEngine(
    /** The styles this engine may draw with. One entry is a fixed style; several is a shuffle. */
    bodies: List<ExplosionBody>,
    private val shuffled: Boolean = false
) : DeathAnimation {

    private val allBodies = bodies
    private val bag = if (shuffled) ShuffleBag(bodies) else null

    private val deaths = mutableListOf<EliteDeath>()

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    override val isActive: Boolean get() = deaths.isNotEmpty()

    override var highQuality: Boolean = true

    /** True during a replay show: hold back the full body so hundreds of blasts stay fluid. */
    override var economyMode: Boolean = false

    /** A fixed style shows its own look; a shuffled engine shows the "all of them" mark. */
    override val preview: ExplosionPreview =
        if (shuffled) ExplosionPreview(Color.WHITE, Color.rgb(255, 200, 90), ExplosionShape.MIXED)
        else allBodies.first().preview

    /** Pay every body's one-time setup before the first blast needs it. */
    fun warmUp() = allBodies.forEach { (it as? ExplosionWarmer)?.warmUp() }

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
        if (deaths.size >= MAX_DEATHS) return
        val kind = EliteKind.fromType(type)
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
                durationMs = if (quickBoom) DEATH_EXPLOSION_START_MS + QUICK_EXPLOSION_LEN_MS
                else DEATH_DURATION_MS,
                kind = kind,
                seed = seed,
                body = bag?.deal(kind) ?: allBodies.first()
            )
        )
        syncActive()
    }

    override fun spawnDud(id: String?, geo: LatLng, origin: LatLng?) {
        if (origin == null || deaths.size >= MAX_DEATHS) return
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
                seed = (id?.hashCode() ?: 0) xor 0x1F123BB5,
                body = bag?.deal(EliteKind.UNKNOWN) ?: allBodies.first()
            )
        )
        syncActive()
    }

    override fun clear() {
        deaths.forEach { it.body.release(it) }
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
            if (done) it.body.release(it)
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
                // Resolve the inbound bearing once, on the first explosion frame.
                if (d.inboundAngleRad == null && d.origin != null) {
                    project(d.origin!!.lat, d.origin!!.lon)?.let { o ->
                        d.inboundAngleRad = atan2((y - o.y).toDouble(), (x - o.x).toDouble()).toFloat()
                    }
                }
                val spend = when {
                    richLeft > 0 -> { richLeft--; ExplosionBudget.FULL }
                    frameBudget.richerThanCheap -> ExplosionBudget.SOLID
                    else -> ExplosionBudget.CHEAP
                }
                d.body.draw(
                    canvas = canvas,
                    death = d,
                    x = x,
                    y = y,
                    e = e,
                    fade = 1f - e,
                    density = density,
                    // Radius is the body's own: each style keeps the exact size it shipped with.
                    maxR = d.body.radius(density, zoomScale),
                    elapsedMs = (t - boomT) * dur,
                    budget = spend,
                    hq = highQuality
                )
            }
        }
        recordFrameCost(System.nanoTime() - startedNs)
    }

    private fun drawIcon(canvas: Canvas, d: EliteDeath, pt: PointF?) {
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
    }

    private var costAvgNs = COST_LOW_NS
    private var hotFrames = 0
    private var calmFrames = 0

    /**
     * The self-throttle. It times this engine's own explosion work — not the whole frame, which
     * includes tile rendering it has no business judging — and moves one tier at a time after a
     * sustained run, so a single slow frame can never demote a style mid-blast.
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
}

internal const val ELITE_TAU = (2.0 * PI).toFloat()

/** The white-hot centre colour for a threat type. */
fun hotCore(kind: EliteKind): Int = when (kind) {
    EliteKind.BALLISTIC -> Color.WHITE
    EliteKind.AVIATION -> Color.rgb(190, 225, 255)
    EliteKind.CRUISE -> Color.rgb(255, 180, 80)
    EliteKind.FPV -> Color.rgb(255, 230, 140)
    else -> Color.WHITE
}

/** The ember/particle colour for a threat type. */
fun ember(kind: EliteKind): Int = when (kind) {
    EliteKind.FPV -> Color.rgb(255, 220, 100)
    EliteKind.SHAHED -> Color.rgb(255, 160, 40)
    EliteKind.BALLISTIC -> Color.rgb(255, 240, 180)
    EliteKind.AVIATION -> Color.rgb(180, 220, 255)
    EliteKind.CRUISE -> Color.rgb(255, 190, 90)
    else -> Color.rgb(255, 193, 7)
}

/** Slow expansion of the shock ring as a fraction of the explosion's max radius. */
/** Blast-wave radius: Sedov–Taylor `R ~ t^2/5` — violent early expansion that visibly outruns
 *  the fireball, then yields. Same endpoint as the old linear track, starts at 0 instead of
 *  popping to a nonzero ring on the first frame. */
fun shockRadius(maxR: Float, e: Float, speed: Float = 1.35f): Float =
    maxR * (0.1f + speed) * e.pow(0.4f)