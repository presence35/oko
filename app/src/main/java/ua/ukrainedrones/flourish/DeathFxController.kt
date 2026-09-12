package ua.ukrainedrones

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import ua.ukrainedrones.UA_TIGHT_MIN_LAT
import ua.ukrainedrones.UA_TIGHT_MAX_LAT
import ua.ukrainedrones.UA_TIGHT_MIN_LON
import ua.ukrainedrones.UA_TIGHT_MAX_LON
import kotlin.random.Random

private val UA_MIN_LAT = UA_TIGHT_MIN_LAT
private val UA_MAX_LAT = UA_TIGHT_MAX_LAT
private val UA_MIN_LON = UA_TIGHT_MIN_LON
private val UA_MAX_LON = UA_TIGHT_MAX_LON

/** Beat after an intermediate group's last impact before panning to the next one. */
private const val REPLAY_PAN_BEAT_MS = 120L

/** Fixed zoom level during a single strike — wide enough to see the projectile path. */
private const val STRIKE_ZOOM_LEVEL = 10.0

/**
 * Map-side flourish facade: owns the death-animation overlay plus everything that drives it —
 * the strike camera glide, the shot/kill haptics, the bullet take-off origin (a random point on
 * the viewport edge, clamped to Ukraine) and the tally-tap replay orchestration. The map view
 * keeps only the thin policy hooks (visibility gating, input handling) and delegates every
 * flourish mechanic here, so the critical marker loop stays clean.
 */
class DeathFxController(
    private val context: Context,
    private val mapView: () -> MapView?,
    private val iconFor: (ThreatType) -> Drawable,
    /** Formats the FIRED audit line, e.g. "Shots: 21 · Groups: 10" — localized by the caller. */
    private val showDetail: (records: Int, groups: Int) -> String,
    private val scope: CoroutineScope
) {
    /** The overlay itself — added to the map's overlay list and driven per frame. */
    val overlay = ThreatDeathOverlay()

    /** Master "Just Fun" gate: live mirror of the master pref. All flourish entry points
     *  no-op while it's off, and flipping it off ejects anything in flight ([clear]). */
    private val justFunEnabled = MutableStateFlow(false)

    init {
        scope.launch {
            UserPrefs(context).preferences
                .map { it.justFunMasterEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    justFunEnabled.value = enabled
                    if (!enabled) clear()
                }
        }
    }

    private val vibrator = context.getSystemService(Vibrator::class.java)
    // The delayed "shot then kill" haptic jobs — each strike launches one; clear() cancels them
    // all so an eject also silences the pending detonation buzz. Pruned on completion.
    private val hapticJobs = mutableListOf<Job>()
    // A pending "return the camera to where the user was" job — replaced by each new strike.
    private var cameraReturnJob: Job? = null
    // The original camera position before the first strike in a sequence — persists across
    // rapid successive strikes so the camera always returns to where the user actually was,
    // not to whatever mid-animation position the second strike captured.
    private var savedHome: GeoPoint? = null
    private var savedHomeZoom: Double = 0.0
    // The running tally-tap replay, so a red alert can cancel it mid-show (clear()).
    private var replayJob: Job? = null

    /** Countdown before an auto-strike fires: 3 → 2 → 1, then the pending strike executes. */
    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()
    private var countdownJob: Job? = null
    private var pendingAutoStrike: (() -> Unit)? = null

    /** True from when an auto-countdown fires until the death animation ends — the UI shows
     *  a "tap to stop" strip over the footer for the whole duration. */
    private val _autoStrikeActive = MutableStateFlow(false)
    val autoStrikeActive: StateFlow<Boolean> = _autoStrikeActive.asStateFlow()

    /** The threat type being targeted while an auto-countdown runs — null once it fires. */
    private val _strikeType = MutableStateFlow<ThreatType?>(null)
    val strikeType: StateFlow<ThreatType?> = _strikeType.asStateFlow()

    /** Number of auto-strikes currently pending (countdown) or in flight — the count the
     *  countdown overlay shows next to the type label, so it reads N during a wave, not 0. */
    private val _pendingStrikeCount = MutableStateFlow(0)
    val pendingStrikeCount: StateFlow<Int> = _pendingStrikeCount.asStateFlow()

    private val _replayProgress = MutableStateFlow<ReplayProgress?>(null)
    /** During the tally-tap replay: per-group position for the footer copy + overall position
     *  for its progress bar. */
    val replayProgress: StateFlow<ReplayProgress?> = _replayProgress.asStateFlow()

    /** Derived: true when any flourish phase is in progress (countdown, auto-strike, death
     *  animation, tally replay, or MiG flyby). The FlourishFooter uses this to gate the
     *  floating icons and own the entire bottom region. */
    private val _flourishActive = MutableStateFlow(false)
    val flourishActive: StateFlow<Boolean> = _flourishActive.asStateFlow()

    init {
        scope.launch {
            combine(
                _countdown, _autoStrikeActive, overlay.active, _replayProgress
            ) { cd, auto, death, replay ->
                cd != null || auto || death || replay != null
            }.collect { _flourishActive.value = it }
        }
    }

    /** When true, city labels should show all tiers regardless of user settings — toggled
     *  during death animations so the projectile has geographic context. */
    val forceShowAllCities = MutableStateFlow(false)

    val active: StateFlow<Boolean> get() = overlay.active
    val isActive: Boolean get() = overlay.isActive

    /** Whether the tally-tap replay is queued or running — while true, the replay owns the map
     *  camera (no competing follow-me pan or default fit; see MapView's camera block). */
    val isReplayActive: Boolean get() = replayJob?.isActive == true

    fun isActiveFor(id: String?): Boolean = overlay.isActiveFor(id)

    /** Drop every active death + cancel a pending camera return or running replay instantly —
     *  a red alert ejects the flourish (safety outranks the playful replay). If a strike/replay
     *  had parked the camera, glide back home FIRST — an early eject must not leave the view
     *  stuck on the target — then tear everything down behind the scenes. */
    fun clear() {
        // Return-home first: snapshot the pending home, then launch the glide immediately.
        val home = savedHome
        val homeZoom = savedHomeZoom
        savedHome = null
        cameraReturnJob?.cancel()
        if (home != null) {
            val mv = mapView()
            if (mv != null) {
                cameraReturnJob = scope.launch {
                    forceShowAllCities.value = true
                    mv.controller.setZoom(homeZoom)
                    mv.controller.animateTo(home)
                    forceShowAllCities.value = false
                }
            }
        }
        replayJob?.cancel()
        replayJob = null
        _replayProgress.value = null
        forceShowAllCities.value = false
        cancelAutoCountdown()
        _autoStrikeActive.value = false
        _pendingStrikeCount.value = 0
        // An eject must also stop the fun's haptics: cancel any pending/queued shot-kill pulses
        // and cut a buzz already in flight.
        if (hapticJobs.isNotEmpty()) {
            hapticJobs.forEach { it.cancel() }
            hapticJobs.clear()
            vibrator?.cancel()
        }
        overlay.clear()
    }

    /** Launch the tally-tap replay on the controller's scope, replacing any show in flight. */
    fun startReplay(records: List<FlourishRecord>) {
        if (!justFunEnabled.value) return
        replayJob?.cancel()
        _replayProgress.value = null
        replayJob = scope.launch { replay(records) }
    }

    /**
     * Start a 3-second countdown before an auto-strike fires. [onFire] executes when the
     * countdown reaches zero. A new countdown replaces any in-flight one (latest wins).
     * Tap-to-cancel: call [cancelAutoCountdown].
     */
    fun startAutoCountdown(type: ThreatType?, onFire: () -> Unit) {
        if (!justFunEnabled.value) return
        countdownJob?.cancel()
        pendingAutoStrike = onFire
        _strikeType.value = type
        _pendingStrikeCount.update { it + 1 }
        countdownJob = scope.launch {
            try {
                for (n in 3 downTo 1) {
                    _countdown.value = n
                    delay(1000L)
                }
                _countdown.value = null
                _strikeType.value = null
                _autoStrikeActive.value = true
                pendingAutoStrike?.invoke()
                pendingAutoStrike = null
                // Wait for the death animation to finish naturally, then drop the active flag.
                overlay.active.first { !it }
                _autoStrikeActive.value = false
            } finally {
                // A replaced/cancelled countdown never fired — drop its pending count too.
                _pendingStrikeCount.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /** Cancel a running auto-countdown — the pending strike is dropped. */
    fun cancelAutoCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        pendingAutoStrike = null
        _countdown.value = null
        _strikeType.value = null
        _autoStrikeActive.value = false
    }

    /** Eject an in-flight auto-strike: stop the death animation + camera pan instantly. */
    fun ejectAutoStrike() {
        clear()
    }

    /** User-initiated or server-driven strike: spawn the projectile + explosion. The bullet
     *  takes off from a random point on the viewport edge (clamped to Ukraine). Returns true
     *  only when a strike actually launched — false when the Just Fun master is off, so the
     *  caller can skip its side effects (marker hide, user-shot grace). */
    fun strike(
        id: String? = null,
        geo: GeoPoint,
        icon: Drawable? = null,
        rotationDeg: Float = 0f,
        alpha: Float = 1f
    ): Boolean {
        if (!justFunEnabled.value) return false
        overlay.spawn(id, geo, randomEdgeOrigin(), icon, rotationDeg, alpha)
        return true
    }

    /** Follow-up projectile for an already-destroyed threat: no icon, never explodes. Returns
     *  true only when a dud actually launched (master gate + a valid edge origin). */
    fun strikeDud(id: String?, geo: GeoPoint): Boolean {
        if (!justFunEnabled.value) return false
        val origin = randomEdgeOrigin() ?: return false
        overlay.spawnDud(id, geo, origin)
        return true
    }

    /** A random point exactly on the viewport edge (0px), converted to geo and clamped to
     *  Ukraine — the bullet always glides in from the screen edge, and can never originate in
     *  another country even when the whole country fills the screen. */
    private fun randomEdgeOrigin(): GeoPoint? {
        val mapView = mapView() ?: return null
        if (mapView.width <= 0 || mapView.height <= 0) return null
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        val t = Random.nextFloat()
        val (px, py) = when (Random.nextInt(4)) {
            0 -> 0f to t * h      // left edge
            1 -> w to t * h       // right edge
            2 -> t * w to 0f      // top edge
            else -> t * w to h    // bottom edge
        }
        val geo = mapView.projection.fromPixels(px.toInt(), py.toInt())
        return GeoPoint(
            geo.latitude.coerceIn(UA_MIN_LAT, UA_MAX_LAT),
            geo.longitude.coerceIn(UA_MIN_LON, UA_MAX_LON)
        )
    }

    /** Follow-the-bullet: with the setting on, the camera glides onto the strike, then pans
     *  back to where the user was once the explosion has finished. It never scrolls to the
     *  launching city. Off: the camera stays still while the animation plays. A fresh strike
     *  replaces any pending return so rapid successive shots don't fight over the camera. */
            fun followStrike(target: GeoPoint, followBullet: Boolean) {
        // A running replay owns the camera (group jumps + precise return home) — a live
        // resolution's follow-strike would fight its final pan with a competing animateTo.
        if (replayJob?.isActive == true) return
        val mapView = mapView() ?: return
        if (mapView.width <= 0 || mapView.height <= 0 || !followBullet) return
        // Snapshot the coordinates: osmdroid's getMapCenter() returns its projection's reusable
        // internal point, which keeps mutating as the camera moves — holding it across the
        // animation would "return" to whatever that shared point held later (a random spot).
        // On the first strike, save the camera position as home; rapid successive strikes reuse
        // the saved home so the camera always returns to where the user actually started.
        if (savedHome == null) {
            savedHome = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
            savedHomeZoom = mapView.zoomLevelDouble
        }
        val home = savedHome!!
        val homeZoom = savedHomeZoom
        cameraReturnJob?.cancel()
        cameraReturnJob = scope.launch {
            forceShowAllCities.value = true
            mapView.controller.setZoom(STRIKE_ZOOM_LEVEL)
            mapView.controller.animateTo(target)
            delay(DEATH_EXPLOSION_START_MS + DEATH_EXPLOSION_LEN_MS + 300L)
            savedHome = null
            forceShowAllCities.value = false
            val mv = this@DeathFxController.mapView() ?: return@launch
            mv.controller.setZoom(homeZoom)
            mv.controller.animateTo(home)
        }
    }

    /** Fun haptics: a short crisp "shot" as the bullet fires, then a longer pulse when it
     *  detonates. USAGE_ALARM keeps both audible as vibration even when the system "touch
     *  feedback" haptics are off. */
    fun strikeHaptics() {
        if (!justFunEnabled.value) return
        if (BuildConfig.DEBUG) android.util.Log.d("VibTrace", "strikeHaptics() source=flourish")
        val vibrator = vibrator ?: return
        val job = scope.launch {
            vibrator.vibrate(
                VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            )
            delay(DEATH_EXPLOSION_START_MS)
            vibrator.vibrate(
                VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            )
        }
        hapticJobs += job
        job.invokeOnCompletion { hapticJobs.remove(job) }
    }

    /**
     * Tally-tap replay flourish: group the remembered resolutions by how close they sit in the
     * current viewport (screen-size × zoom adaptive), then for each group zoom onto just it,
     * fire its bullets [FLOURISH_STAGGER_MS] apart, and move to the next group — the camera
     * finally returns to where the user was after the LAST group explodes. Pure flourish — a
     * red alert ejects it (see [clear], which also cancels this show mid-flight). Launched via
     * [startReplay]; the caller gates on visibility/alert/lifecycle before invoking.
     */
    suspend fun replay(records: List<FlourishRecord>) {
        if (!justFunEnabled.value) return
        val mapView = mapView() ?: return
        if (records.isEmpty()) return
        // Snapshot — see followStrike; getMapCenter() hands back a live mutable point.
        val preCenter = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
        val preZoom = mapView.zoomLevelDouble
        // Remember the home for an early eject (clear) too — not just the natural ending.
        if (savedHome == null) {
            savedHome = preCenter
            savedHomeZoom = preZoom
        }
        cameraReturnJob?.cancel()
        // A group spans about a third of the current viewport width — zoomed in, groups are
        // tight; zoomed out, everything clusters.
        val mpp = TileSystem.GroundResolution(mapView.mapCenter.latitude, mapView.zoomLevelDouble)
        val groupDist = mpp * mapView.width * 0.45f
        val groups = clusterFlourish(records, groupDist.toDouble())
        DebugLog.recordFlourish(
            DebugLogReason.FIRED,
            detail = showDetail(records.size, groups.size),
            now = System.currentTimeMillis()
        )
                                var index = 0
        val lastGi = groups.lastIndex
        groups.forEachIndexed { gi, group ->
            val finalGroup = gi == lastGi
            // Pre-spawn EVERY target BEFORE the jump: their icons exist in the death list
            // before the viewport changes, so nothing pops in after the camera lands.
            val settle = if (gi == 0) 350L else 250L
            val fireBase = SystemClock.elapsedRealtime() + settle
            group.forEachIndexed { k, rec ->
                overlay.spawn(
                    id = "flourish:${index + k + 1}",
                    geo = GeoPoint(rec.lat, rec.lon),
                    origin = randomEdgeOrigin(),
                    icon = iconFor(rec.type),
                    rotationDeg = 0f,
                    alpha = 1f,
                    // Intermediate groups only show the hits; the LAST group gets the full show.
                    quickBoom = !finalGroup,
                    fireAtDelayMs = settle + k * FLOURISH_STAGGER_MS
                )
            }
            // Jump straight onto this group (no animated glide — bullets must never fly while
            // the camera is still moving), then re-point pending flights to the new edges.
            val box = flourishesBoundingBox(group, null)
            runCatching { mapView.zoomToBoundingBox(box, false) }
            overlay.rebasePendingOrigins { randomEdgeOrigin() }
            // Fire loop aligned to the pre-spawned schedule (drift-free vs the spawn clock):
            // shot k launches at fireBase + k*STAGGER; haptic + footer progress advance per shot.
            group.forEachIndexed { k, _ ->
                val wait = fireBase + k * FLOURISH_STAGGER_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                index++
                _replayProgress.value = ReplayProgress(
                    bulletInGroup = k + 1,
                    groupSize = group.size,
                    bulletOverall = index,
                    totalRecords = records.size
                )
                strikeHaptics()
            }
            if (finalGroup) {
                // Full animation for the finale: linger through the complete explosion window.
                delay(DEATH_EXPLOSION_START_MS + DEATH_EXPLOSION_LEN_MS)
            } else {
                // Pan very shortly after the last bullet HITS — no explosion linger.
                delay(DEATH_EXPLOSION_START_MS + REPLAY_PAN_BEAT_MS)
            }
        }
        _replayProgress.value = null
        // Back home, at peace.
        val mv = mapView() ?: return
        mv.controller.setZoom(preZoom)
        mv.controller.animateTo(preCenter)
        savedHome = null
    }
}