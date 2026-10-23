package com.odesaplay.oko

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.odesaplay.oko.AppLanguage
import com.odesaplay.oko.pick
import com.odesaplay.oko.resolveFocus
import com.odesaplay.oko.connection.ConnectionMilestone
import com.odesaplay.oko.connection.ConnEventKind
import com.odesaplay.oko.connection.Monotonic
import com.odesaplay.oko.engine.armedTier
import com.odesaplay.oko.engine.isFastType
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.canonicalOblastId
import com.odesaplay.oko.engine.threatOblastId
import com.odesaplay.oko.engine.OfficialFrontier
import com.odesaplay.oko.engine.officialAnnouncementIsOnset
import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.LatchedEpisode
import com.odesaplay.oko.engine.EpisodeTransition
import com.odesaplay.oko.engine.RestoredResolution
import com.odesaplay.oko.engine.officialStateFor
import com.odesaplay.oko.Transliteration
import com.odesaplay.oko.isOfficialLevelEnabled
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.engine.toThreatType
import com.odesaplay.oko.engine.toEngineString
import com.odesaplay.oko.engine.inOblast
import com.odesaplay.oko.engine.alertRegionName
import com.odesaplay.oko.engine.threatBody
import com.odesaplay.oko.UpdateInfo
import com.odesaplay.oko.BuildConfig
import com.odesaplay.oko.UpdateManager
import com.odesaplay.oko.UpdateState
import com.odesaplay.oko.engine.ZoneParams
import com.odesaplay.oko.data.ApiMonitor
import com.odesaplay.oko.ConnectionLog
import com.odesaplay.oko.DebugLog
import com.odesaplay.oko.DebugLogContext
import com.odesaplay.oko.DebugLogKind
import com.odesaplay.oko.DebugLogReason
import com.odesaplay.oko.data.ManifestResult
import com.odesaplay.oko.data.SystemEntry
import com.odesaplay.oko.data.SystemEntryKind
import com.odesaplay.oko.Strings
import com.odesaplay.oko.UserPrefs
import com.odesaplay.oko.engine.distanceFlat
import com.odesaplay.oko.AlarmEpisodeTally
import com.odesaplay.oko.NeutralizedTally
import com.odesaplay.oko.engine.ThreatEngine
import com.odesaplay.oko.service.ServiceState
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.MonitorEvent
import com.odesaplay.oko.service.MonitorLifecycle
import com.odesaplay.oko.service.SuspendState
import com.odesaplay.oko.service.AlertWakeup
import com.odesaplay.oko.service.AlertWatchdog
import com.odesaplay.oko.service.EmergencyResurrectionWorker
import com.odesaplay.oko.service.OfflineIgnore
import com.odesaplay.oko.service.RaidMuteState
import com.odesaplay.oko.service.FallingDebrisBuffer

class AlertService : Service() {

    companion object {
        const val ACTION_RETRY = AlertNotificationManager.ACTION_RETRY
        const val ACTION_IGNORE_RETRY = AlertNotificationManager.ACTION_IGNORE_RETRY
        const val ACTION_ALERT_OK = AlertNotificationManager.ACTION_ALERT_OK
        const val ACTION_MUTE_RAID = AlertNotificationManager.ACTION_MUTE_RAID
        const val ACTION_MUTE_10 = AlertNotificationManager.ACTION_MUTE_10
        const val ACTION_MUTE_TYPE = AlertNotificationManager.ACTION_MUTE_TYPE
        const val ACTION_CLEAR_MUTE = "com.odesaplay.oko.CLEAR_MUTE"
        const val EXTRA_REVEAL_ID = AlertNotificationManager.EXTRA_REVEAL_ID
        const val EXTRA_REVEAL_LAT = AlertNotificationManager.EXTRA_REVEAL_LAT
        const val EXTRA_REVEAL_LON = AlertNotificationManager.EXTRA_REVEAL_LON
        const val EXTRA_SHOW_UPDATE = AlertNotificationManager.EXTRA_SHOW_UPDATE
        const val EXTRA_SHOW_MAP = AlertNotificationManager.EXTRA_SHOW_MAP

        const val CHANNEL_MONITOR = AlertNotificationManager.CHANNEL_MONITOR
        const val CHANNEL_ALERTS_INNER = AlertNotificationManager.CHANNEL_ALERTS_INNER
        const val CHANNEL_ALERTS_OUTER = AlertNotificationManager.CHANNEL_ALERTS_OUTER
        const val CHANNEL_ALL_CLEAR = AlertNotificationManager.CHANNEL_ALL_CLEAR
        const val CHANNEL_ALERTS_INNER_ALARM = AlertNotificationManager.CHANNEL_ALERTS_INNER_ALARM
        const val CHANNEL_ALERTS_OUTER_ALARM = AlertNotificationManager.CHANNEL_ALERTS_OUTER_ALARM
        const val CHANNEL_OFFLINE = AlertNotificationManager.CHANNEL_OFFLINE
        const val CHANNEL_OFFLINE_CRITICAL = AlertNotificationManager.CHANNEL_OFFLINE_CRITICAL
        const val CHANNEL_UPDATE = AlertNotificationManager.CHANNEL_UPDATE

        const val NOTIF_MONITOR = AlertNotificationManager.NOTIF_MONITOR
        const val NOTIF_ALERT = AlertNotificationManager.NOTIF_ALERT
        const val NOTIF_ALLCLEAR = AlertNotificationManager.NOTIF_ALLCLEAR
        const val NOTIF_MILESTONE = AlertNotificationManager.NOTIF_MILESTONE
        const val NOTIF_OFFLINE_CRITICAL = AlertNotificationManager.NOTIF_OFFLINE_CRITICAL
        const val NOTIF_UPDATE = AlertNotificationManager.NOTIF_UPDATE
        const val NOTIF_MONITORING_PAUSED = AlertNotificationManager.NOTIF_MONITORING_PAUSED

        /** Official notification ids are level-tagged so the posted id names the level it was
         *  posted at — [officialLevelOf] reads these back, and they are the only place either
         *  side of that pair is written. */
        private const val OFFICIAL_ID_RED = "red|"
        private const val OFFICIAL_ID_YELLOW = "yellow|"

        /** "Ignore 30 min": how long offline milestone/critical notifications stay muted. */
        private const val IGNORE_RETRY_MUTE_MS = 30 * 60_000L

        /** "Mute 10 min" alert action. */
        private const val MUTE_10_MS = 10 * 60_000L

        const val CRITICAL_OFFLINE_MIN = 5
        const val CRITICAL_OFFLINE_ALARM_MIN = 1
        private const val ALL_CLEAR_GRACE_MS = 0L

        /** How long an all-clear notification may sit in the shade before it is retired. */
        private const val ALL_CLEAR_TTL_MS = 20 * 60_000L
        private const val MONITOR_TICK_MS = 1_000L
        private const val MONITOR_TICK_IDLE_MS = 30_000L
        private const val SWEEP_THROTTLE_MS = 10_000L
        /** A tick later than this did not run — the loop slept (doze, freeze, or a stalled
         *  process) while the wall clock ran on. Anything larger is not a tick, it is a hole, and
         *  the tick that closes it must re-baseline instead of deciding with a stale clock.
         *  Comfortably above the 30s idle tick so ordinary jitter never trips it. */
        private const val SLEEP_GAP_REBASE_MS = 3L * 60_000
        /**
         * Observation time for one evaluation. `combine` re-fires on every input (threat/alert
         * snapshot, location, prefs) while `nowFlow` advances only on the delay loop, which Doze
         * suspends — so a snapshot evaluated out of a sleep carries a frozen tick clock. A 6min
         * alarm closed on such a tick measured 3min (onset 22:32:36, all-clear evaluated at tick
         * 22:36:01 although the snapshot arrived 22:38:42). The wall clock is the observation;
         * maxOf keeps monotonic forward progress across a backward wall jump.
         */
        internal fun observationNow(tickNowMs: Long, wallMs: Long = System.currentTimeMillis()): Long =
            maxOf(tickNowMs, wallMs)

        /** How long a posted official onset keeps the shared alert slot against an outer-zone
         *  rewrite. The siren that summons the user names the official area and its reason; the
         *  slot must still show that when they look, not the zone row that fired seconds later. */
        private const val OFFICIAL_SLOT_HOLD_MS = 60_000L

        /**
         * Whether a zone primary must wait: it is an outer-zone rewrite, the shown official
         * onset is still a live episode, and its hold has not lapsed. Pure so the branches are
         * unit-tested; the live fact it reads is computed by the caller. INNER always breaks
         * through — an escalation to red is never held for copy.
         */
        internal fun officialSlotHeld(
            primaryZone: ThreatZone?,
            shownOfficialStillLive: Boolean,
            onsetShownAtMs: Long,
            nowMs: Long
        ): Boolean = primaryZone != null && primaryZone != ThreatZone.INNER &&
            shownOfficialStillLive && nowMs - onsetShownAtMs < OFFICIAL_SLOT_HOLD_MS

        /**
         * Start the monitor because the *user* acted — a cold launch, a settings change, the
         * "SERVICE OFFLINE" banner. Deliberately not gated: an explicit gesture always wins.
         * Anything that starts monitoring on its own (alarm, worker, boot, un-pause) must go
         * through [startResilient] instead, which is where the one policy lives.
         */
        fun start(context: Context) {
            MonitoringStatus.startRequested()
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
            } catch (e: Exception) {
                // A failed start must never look like working monitoring �?" leave the flag off
                // so the UI banner surfaces the dead state instead of going silent.
                MonitoringStatus.died(MonitoringStatus.Reason.START_REFUSED)
                MonitorLifecycle.recordNow(context, MonitorEvent.Kind.START, "refused")
                throw e
            }
        }

        /**
         * Background-safe start for WorkManager/watchdog paths. Android 12+ forbids starting a
         * foreground service from the background, so a failure is expected there: swallow it
         * (never crash the worker) and post a tap-to-resume prompt instead. Returns whether the
         * service actually started.
         *
         * The single gate every automatic path answers to, because this is the one place they all
         * funnel through. Silence, never a tap-to-resume prompt, for the paused case: the user
         * chose that state. See [mayStartMonitor] for the rule itself.
         */
        fun startResilient(context: Context, fromUnpause: Boolean = false): Boolean {
            if (!mayStartMonitor(SuspendState.isSuspended(context), fromUnpause)) return false
            return try {
                start(context)
                true
            } catch (e: Exception) {
                // Android 12+ refuses a foreground start from the background. That refusal is the
                // reason monitoring is off, so it belongs in the durable record, not only in a
                // tap-to-resume row the user may swipe away.
                MonitorLifecycle.recordNow(
                    context, MonitorEvent.Kind.RESURRECT_BLOCKED,
                    e.javaClass.simpleName
                )
                AlertNotificationManager(context.applicationContext).postRestartBlocked()
                false
            }
        }

        /**
         * The one policy every automatic start answers to — pure, with the live facts passed in,
         * so both branches are tested rather than reasoned about.
         *
         * A suspended package cannot alert at all: the OS hides its notifications, stops its
         * activities and blocks its audio, so resurrecting it would leave a running service that
         * cannot do its job. That makes pause universal, where the user's auto-restart pref is not
         * (BootReceiver deliberately restarts *over* a live alert).
         *
         * [fromUnpause] is true only for `ACTION_MY_PACKAGE_UNSUSPENDED`, which *is* the authority
         * that the pause is over — so it may not be second-guessed by a read of state the OS could
         * still be committing. Silence on that path would leave the user believing monitoring came
         * back when it did not.
         */
        internal fun mayStartMonitor(suspended: Boolean, fromUnpause: Boolean): Boolean =
            fromUnpause || !suspended

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }

        /**
         * Explicit "Stop & exit": the service stops AND every automatic resurrection path is
         * disarmed (wakeup alarm, both periodic workers), so nothing revives monitoring minutes
         * later with a tap-to-resume prompt. The next cold start re-arms what it needs —
         * [MainActivity] starts the service and schedules the workers — so no flag is needed:
         * a stop with nothing scheduled simply stays stopped.
         */
        suspend fun shutdown(context: Context) {
            val appContext = context.applicationContext
            // Written first, and awaited by the caller: this row is the only thing that will ever
            // tell a later silence apart from an OS kill.
            MonitorLifecycle.record(appContext, MonitorEvent.Kind.STOP_BY_USER)
            MonitoringStatus.stoppedByUser()
            stop(appContext)
            AlertWakeup.cancel(appContext)
            AlertWatchdog.cancel(appContext)
            EmergencyResurrectionWorker.cancel(appContext)
            AlertNotificationManager(appContext).cancelNotification(NOTIF_MONITORING_PAUSED)
            // A stop the user asked for is not the kill the battery card warns about.
            runCatching { UserPrefs(appContext).setServiceResurrected(false) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null

    /** Completed once [monitoringJob]'s restore phase has read the persisted official-episode row
     *  and the tick loop is about to start. A test driving [handleState] directly must await this
     *  first: a tick that lands before the restore would announce its episode and then have the
     *  latch wiped by the restore that lands after it. Production is ordered by construction (the
     *  restore runs before the tick loop is launched in the same coroutine). */
    private val monitoringReady = CompletableDeferred<Unit>()
    internal suspend fun awaitMonitoringReady() = monitoringReady.await()
    /** True once the FGS was (re)started with the location type. Left false when a background
     *  start had to fall back (no location exemption), so the next foreground start re-promotes. */
    private var locationFgsActive = false
    /** True once a foreground notification was actually accepted by the platform - the fact
     *  [MonitoringStatus.running] is allowed to depend on. */
    private var foregroundPromoted = false
    private var lastShownId: String? = null
    /** Frequency policy: the service feeds it per-tick facts and executes verdicts. */
    private val notifyPlugin = NotifyPlugin()
    /** Previous tick's engine tiers — the hysteresis band input. */
    private var lastZoneTiers: Map<String, ThreatZone> = emptyMap()
    private var lastNotifyPrefs: NotifyPrefs? = null
    private var lastPersistedPresence = ""
    /** Announced official episode for ANY level: identity + level + whether it has already been
     *  siren'd at red (see [OfficialFrontier]). Identity is the canonical region, persisted as the
     *  `LEVEL|token|since|city` row; the sound memory is process-local, so a restart re-announces
     *  silently rather than re-sirening. Non-null ONLY while the episode is alive — it is cleared
     *  when the episode ends, so "announced but ended" cannot exist. */
    private var lastAnnouncedFrontier: OfficialFrontier? = null
    /** The announcement row last persisted, verbatim. Held because the LATCH must keep reading the
     *  level it was persisted with — a live-feed downgrade updates [lastAnnouncedFrontier] without
     *  re-announcing, and re-persisting from live state would silently rewrite history. */
    private var lastAnnouncedRow: String? = null
    /** Raw (unscoped) official episode last written to the audit log. Independent of the
     *  notif scope and toggles, so EVERY official alert is logged even when its notifs are
     *  off, muted or sleeping. Live truth, never a restored row. */
    private var lastLoggedRawFrontier: OfficialFrontier? = null
    /** Level the audit trail was last told about, for the episode it names. Purely a
     *  de-duplication stamp for [DebugLogKind.OFFICIAL_LEVEL]: a downgrade must be logged once,
     *  not on every tick of the hour it applies to. */
    private var lastLoggedLevelFrontier: OfficialFrontier? = null
    /** Focus oblast token of the previous tick; a change drops a now-stale latched episode. */
    private var lastFocusTokenSeen: String? = null
    /** True once the latched episode was observed raw-active on a ready feed in this
     *  lifetime. A restored latch starts unconfirmed: only a confirmed episode may end
     *  loudly; an unconfirmed one expires silently on its first inactive snapshot. */
    private var latchedConfirmedLive = false
    private var lastChannelLang: AppLanguage? = null
    private var emptySince: Long? = null

    private var lastMonitorTitle: String? = null
    private var lastMonitorText: String? = null
    private var lastMonitorRetry: String? = null
    private var lastMonitorProgressMax: Int? = null
    private var lastMonitorProgressNow: Int? = null
    private var lastMonitorIgnore: String? = null
    private var lastMonitorAlertLevel: AlertLevel = AlertLevel.NONE

    private var hasShownGpsFallbackToast = false
    /** Last observed "the notice must not be up" state, so the sleep row is retracted on the
     *  TRANSITION. Two ways in — the exemption landing, or the user switching the notice off — and
     *  one field for both because the reason to edge-detect is the same: polling-and-cancelling
     *  every tick would be a binder call per tick forever, and neither cause ever produces the sleep
     *  windows that could otherwise trigger a cleanup. */
    private var lastNoticeRetracted: Boolean? = null
    @Volatile private var wasConnected = true
    private var offlineAlertJob: Job? = null
    private var offlineRestorePending = false
    /**
     * Whether the user is holding the phone, and since when. A bare flag could not carry the second
     * half, and that half is the sleep row's whole policy: it waits for the screen to come on and
     * then waits again [SleepPolicy.WAKE_SETTLE_MS] before it is written, because a notification
     * landing the instant the screen lights interrupts the one thing the user picked the phone up for.
     */
    private val screenFlow = MutableStateFlow(ScreenState(on = true, sinceMillis = 0L))
    /**
     * A sleep the loop just came out of, waiting to be reported. Set by the tick coroutine, read and
     * consumed by the evaluation coroutine — a StateFlow because that is already the hand-off shape
     * here ([nowFlow], [screenFlow]), and because the evaluation needs a re-run when it changes.
     */
    private val pendingSleep = MutableStateFlow<PendingSleep?>(null)

    /**
     * The current night's stalls, folded as each is classified. The row is written from this, not
     * from the stall, so one night produces one summary carrying its own total.
     */
    private var sleepLedger: SleepLedger? = null

    /**
     * One evaluation's inputs, named at class scope rather than inside the monitoring coroutine
     * because [applySleepDecision] is a member and needs to receive the same snapshot the
     * evaluation saw. Source-agnostic by construction: engine currency in, nothing wire-shaped.
     */
    private data class ScreenState(val on: Boolean, val sinceMillis: Long)

    private data class LiveInputs(
        val rawThreats: Map<String, NormalizedThreat>,
        val alerts: List<OblastAlert>,
        val gps: LatLng?,
        val now: Long,
        val alertsReady: Boolean,
        /** Whether the user is actually looking. The sleep row waits for it, so the report lands
         *  with the night's total rather than as a floor-rounded number at 3am. */
        val screen: ScreenState,
        /** The sleep this tick closed, if any. Rides the feed so the classification sees the same
         *  snapshot the evaluation does, and is consumed exactly once. */
        val sleep: PendingSleep? = null
    )
    private var screenReceiver: BroadcastReceiver? = null

    private val notificationManager by lazy { AlertNotificationManager(applicationContext) }
    private val wakeLockManager by lazy { AlertWakeLockManager(applicationContext) }
    /** The all-clear is off the shade and must not be brought back: swiped by the user,
     *  superseded by a new episode, or retired by the TTL. */
    @Volatile private var allClearClosed = false
    /**
     * Wall-clock instant the all-clear notification retires itself, or null when none is armed.
     *
     * A deadline, not a coroutine `delay`, on purpose: the old one-shot timer lived on
     * `uptimeMillis`, which does not advance while the CPU sleeps, so after doze the all-clear
     * could sit in the shade for hours with a frozen countdown — and it was armed exactly once,
     * so a re-post could never bring it back. Re-derived from the clock on every tick instead.
     */
    @Volatile private var allClearDeadlineMs: Long? = null
    private val debrisBuffer by lazy {
        FallingDebrisBuffer(
            scope = scope,
            onTick = { sec ->
                if (!allClearClosed && notificationManager.isAllClearNotificationActive()) {
                    lastCleanAllClearCity?.let { city ->
                        val s = Strings.get(lastChannelLang ?: AppLanguage.EN)
                        postAllClear(s, city, debrisSeconds = sec, silent = true, muted = bellsMuted(), replay = lastEpisodeReplay)
                    }
                }
            },
            onCompleted = {
                if (!allClearClosed && notificationManager.isAllClearNotificationActive()) {
                    lastCleanAllClearCity?.let { city ->
                        val s = Strings.get(lastChannelLang ?: AppLanguage.EN)
                        postAllClear(s, city, debrisSeconds = 0, silent = true, muted = bellsMuted(), replay = lastEpisodeReplay)
                    }
                }
            }
        )
    }
    @Volatile private var lastCleanAllClearCity: String? = null
    @Volatile private var lastEpisodeReplay: List<FlourishRecord> = emptyList()

    private var lastSweepAtMs = 0L

    /** The registry degraded-since stamp the critical offline notif already fired for
     *  (null = not fired this episode). Keyed off the episode, not a boolean, so transient
     *  Connecting states can neither refire nor resurrect a swiped-away notification. */
    private var criticalFiredForEpisode: Long? = null

    /** "Ignore 30 min" mute deadline (session-only, wall clock). Offline milestone/critical
     *  notifications are suppressed until this instant; the connection keeps reconnecting. */
    @Volatile private var notifMuteUntilMs = 0L

    /** True while the user asked offline rows to stay quiet — the in-memory tap plus the
     *  process-wide [OfflineIgnore] the lightweight ignore receiver sets without a service
     *  start, so a tap that lands while the process is cold is still honoured. */
    private fun ignoreMuted(now: Long = System.currentTimeMillis()): Boolean =
        now < notifMuteUntilMs || OfflineIgnore.isMuted(now)

    /** "Mute raid" state for the active raid (see [RaidMute]). A timed mute is persisted, so a
     *  recycled process cannot quietly un-mute a raid the user silenced ten minutes ago. */
    @Volatile private var raidMute: RaidMute = RaidMute.None

    /**
     * The raid is muted: no sound, no vibration and — since "mute means mute" — no new alert
     * notification at all. The alert that was already up keeps updating silently; a raid that
     * starts (or escalates) inside the window is recorded in the audit trail and shown to
     * nobody, which is the deal the user accepted when they pressed the button.
     */
    private fun bellsMuted(): Boolean = raidMute.lapsed(System.currentTimeMillis()).silent(System.currentTimeMillis())

    /** Single writer: keeps the service field, the UI mirror and the persisted deadline in
     *  lockstep. Persisting only a *timed* mute is deliberate — see [RaidMute.encode]. */
    private fun setRaidMute(value: RaidMute) {
        val lapsed = value.lapsed(System.currentTimeMillis())
        raidMute = lapsed
        RaidMuteState.set(lapsed)
        scope.launch {
            ServiceState(applicationContext).setRaidMuteDeadline(lapsed.encode())
        }
    }

    /**
     * Restore a persisted timed mute at service start. A deadline already in the past is dropped
     * rather than restored, so an old mute can never silence the next raid for a second.
     */
    private suspend fun restoreRaidMute() {
        val now = System.currentTimeMillis()
        val restored = RaidMute.decode(ServiceState(applicationContext).raidMuteDeadline().first())
            .lapsed(now)
        if (restored is RaidMute.None) return
        raidMute = restored
        RaidMuteState.set(restored)
    }

    private val tally by lazy { NeutralizedTally(applicationContext, scope) }
    private val episodeTally by lazy { AlarmEpisodeTally(applicationContext, scope) }
    @Volatile private var episodeActive = false
    /**
     * Wall instant the currently-shown official onset was posted, or 0 when the slot holds no
     * official onset. Arms [officialSlotHeld]. Internal for tests (expired-hold simulation).
     */
    @Volatile internal var lastOfficialOnsetShownMs = 0L
    @Volatile private var currentToken: String? = null

    /** One tick's inputs, assembled by the combine above. `internal` rather than private so a
     *  test can drive [handleState] directly — the episode/all-clear gates are only reachable
     *  through it. Not public API: nothing outside this module builds one. */
    internal data class MonitorState(
        val focusOblastAlertActive: Boolean,
        val focusOblastYellowAlertActive: Boolean,
        val focusOblastLevel: AlertLevel,
        val focusOblastRawLevel: AlertLevel,
        val focusOblastRawSince: String?,
        val focusToken: String?,
        val focusOblastAlertSince: String?,
        val focusBannerCity: String,
        val focusCityUa: String?,
        val focusRegion: String,
        val focusPinned: Boolean,
        val officialReason: String?,
        val officialReasonThreatId: String?,
        /** Canonical oblast id of the active official alert, captured at the same point as
         *  [officialRegion]. Carried separately because [officialRegion] is a LOCALIZED display
         *  name — English even rewrites "район" to "district" — so it cannot be resolved back
         *  into an id for a raion alert. */
        val officialOblastId: String?,
        val officialRegion: String?,
        val zoneThreats: Map<String, ThreatZone>,
        val params: ZoneParams,
        val lang: AppLanguage,
        val slowRedArmed: Boolean,
        val slowYellowArmed: Boolean,
        val fastRedArmed: Boolean,
        val fastYellowArmed: Boolean,
        val officialRedAlertsEnabled: Boolean,
        val officialYellowAlertsEnabled: Boolean = true,
        val zoneSirenOverride: Boolean,
        val officialSirenOverride: Boolean,
        val fallingDebrisDelaySec: Int,
        val autoDismissAllClear: Boolean = true,
        val degraded: Boolean = false,
        val threats: Map<String, NormalizedThreat>,
        val rawThreats: Map<String, NormalizedThreat> = emptyMap(),
        val alerts: List<OblastAlert>,
        val alertsReady: Boolean = false,
        val criticalOfflineOverride: Boolean,
        val criticalOfflineBypassSilent: Boolean,
        val focusLocation: LatLng?,
        val gpsIssue: GpsIssue = GpsIssue.NONE,
        val gpsUnverifiedMin: Int = 0,
        val nightActive: Boolean,
        val enabled: Set<ThreatType>,
        val hiddenTypes: Set<String>,
        val notifyPolicyEnabled: Boolean,
        val zonePolicy: ZonePolicy,
        val digestMax: Int,
        val digestWindow: DigestWindow,
        val digestPerType: Boolean,
        /** The city-level gate actually used to derive [focusOblastLevel] this tick. Carried so
         *  the episode latch can be ENDED through the same gate it was announced through —
         *  without it, a city-scoped episode could only end once the whole oblast went quiet. */
        val cityScopeEffective: Boolean = false
    ) {
        /** "Are official alerts on at all" — the master toggle behind the UI row, and the morale
         *  tally's yes/no. Anything acting on ONE level (its announcement, its all-clear, the
         *  notification it left up) must ask [officialLevelEnabled] instead: keying the all-clear
         *  on this master cleared — and sounded — for a level the user had switched off. */
        val officialAlertsEnabled: Boolean
            get() = officialRedAlertsEnabled || officialYellowAlertsEnabled

        /** Whether *this* level is one the user wants announced. */
        fun officialLevelEnabled(level: AlertLevel): Boolean =
            isOfficialLevelEnabled(level, officialRedAlertsEnabled, officialYellowAlertsEnabled)

        /** Follow-me is on and we cannot vouch for the position. Kept as one derived fact so no
         *  consumer can read the issue and re-derive a second, looser boolean. */
        val gpsUnreliable: Boolean get() = gpsIssue != GpsIssue.NONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppSources.init(applicationContext)

        scope.launch {
            ConnectionLog.attach(applicationContext)
            GpsLog.attach(applicationContext)
            DebugLog.attach(applicationContext)
            BatteryLog.attach(applicationContext)
            ApiMonitor.attach(applicationContext)
            ConnectionLog.awaitAttached()
            GpsLog.awaitAttached()
            DebugLog.awaitAttached()
            ApiMonitor.awaitAttached()

            launch {
                val manifestResult = ApiMonitor.checkManifest(applicationContext)
                if (manifestResult is ManifestResult.Changed) {
                    ApiMonitor.record(
                        SystemEntry(
                            System.currentTimeMillis(),
                            SystemEntryKind.SDK_CHANGED,
                            "SHA256: ${manifestResult.oldHash} -> ${manifestResult.newHash}"
                        )
                    )
                } else if (manifestResult is ManifestResult.Failed) {
                    ApiMonitor.record(
                        SystemEntry(
                            System.currentTimeMillis(),
                            SystemEntryKind.SDK_CHECK_FAILED,
                            manifestResult.message
                        )
                    )
                }
            }
        }

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON ->
                        screenFlow.value = ScreenState(on = true, sinceMillis = System.currentTimeMillis())
                    Intent.ACTION_SCREEN_OFF ->
                        screenFlow.value = ScreenState(on = false, sinceMillis = System.currentTimeMillis())
                }
            }
        }
        // The receiver only ever sees a transition, so a restart — which is exactly when a night
        // sleep is reported — would otherwise read the initial value and think the user is looking.
        // The sleep row waits on this, so it must state the truth at start, not just after a flip.
        // "Looking at it now" starts counting from this moment, not from whenever the screen was
        // last lit: a restart is what reports a night of sleep, and a row that believes the
        // screen went on hours ago would post the instant it starts.
        screenFlow.value = ScreenState(
            on = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true,
            sinceMillis = System.currentTimeMillis()
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        // Promote before doing anything disk-backed: a startForegroundService has ~5s to reach
        // startForeground, and channel setup reads DataStore (slow on a fresh install). The
        // monitor channel must exist first or the platform rejects the notification outright,
        // so it goes first (a plain binder call), then the rest off the main thread.
        notificationManager.ensureMonitorChannel()
        // The monitor notification is live from here on: a tap-to-resume prompt left over from
        // an earlier failed start has served its purpose and must not sit beside it.
        notificationManager.cancelNotification(NOTIF_MONITORING_PAUSED)
        startForegroundCompat()
        scope.launch {
            notificationManager.createChannels()
            startForegroundCompat()
        }
        startMonitoring()
        // A timed mute outlives the process that took it: the OS recycles a foreground service
        // under memory pressure, and a mute that vanished on the way back is exactly the "mute
        // doesn't work" report. Restored before the first tick can read it.
        scope.launch { restoreRaidMute() }

        // An all-clear that survived a process death has no episode behind it any more, so its
        // TTL timer is gone and nothing would ever retire it. Drop it rather than leave a
        // permanent "all clear" in the shade.
        if (notificationManager.isAllClearNotificationActive()) {
            notificationManager.cancelNotification(NOTIF_ALLCLEAR)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundPromoted) MonitoringStatus.running()
        MonitorLifecycle.recordNow(this, MonitorEvent.Kind.START)
        // Every start (cold launch, boot, resurrection by worker or alarm) re-arms the Doze-legal
        // wakeup, so no path can leave a device with no next wakeup.
        AlertWakeup.schedule(this)
        // Action-log copy is language-neutral on purpose: an audit row outlives the session and
        // is re-read after a language change, so a localized label would go stale in place.
        val s0 = Strings.get(AppLanguage.EN)
        // A background start (package-replaced/boot) may have had to drop the location type;
        // once we're started again in the foreground, re-promote so background fixes resume.
        if (!locationFgsActive) startForegroundCompat()
        when (intent?.action) {
            ACTION_RETRY -> {
                // Visible effect first: dismiss the rows even if the reconnect below takes a
                // moment — the tap must register before the work finishes, not after.
                notificationManager.cancelNotification(NOTIF_MILESTONE)
                notificationManager.cancelNotification(NOTIF_OFFLINE_CRITICAL)
                scope.launch {
                    AppSources.registry.retryNow()
                    logAction(DebugLogReason.USER_RETRY, s0.actionLog.retryConnection)
                }
                // The user has acted: the one-shot escalations for this episode are noise now, and
                // the only Retry button left in the shade is the ongoing row they pressed.
                showRetryAcknowledged(Strings.get(lastChannelLang ?: AppLanguage.EN))
            }
            ACTION_IGNORE_RETRY -> {
                // Visible effect first, bookkeeping off the main thread: the rows must vanish
                // on tap, even on a cold process where the audit writes below take seconds.
                notificationManager.cancelNotification(NOTIF_MILESTONE)
                notificationManager.cancelNotification(NOTIF_OFFLINE_CRITICAL)
                OfflineIgnore.ignoreFor(IGNORE_RETRY_MUTE_MS)
                notifMuteUntilMs = System.currentTimeMillis() + IGNORE_RETRY_MUTE_MS
                scope.launch {
                    AppSources.registry.annotateConnectionLog(
                        ConnEventKind.IGNORE_MUTED,
                        detail = "${IGNORE_RETRY_MUTE_MS / 60_000} min"
                    )
                    logAction(
                        DebugLogReason.MUTED, s0.actionLog.ignoreOffline,
                        "${IGNORE_RETRY_MUTE_MS / 60_000} min"
                    )
                }
            }
            ACTION_ALERT_OK -> {
                notificationManager.cancelNotification(NOTIF_ALERT)
                logAction(DebugLogReason.SETTING_CHANGED, s0.actionLog.alertDismissed)
            }
            ACTION_MUTE_RAID -> {
                setRaidMute(raidMute.untilClear())
                notificationManager.cancelNotification(NOTIF_ALERT)
                logAction(DebugLogReason.MUTED, s0.actionLog.muteRaid, s0.actionLog.untilClear)
            }
            ACTION_MUTE_10 -> {
                setRaidMute(raidMute.forDuration(System.currentTimeMillis(), MUTE_10_MS))
                notificationManager.cancelNotification(NOTIF_ALERT)
                logAction(
                    DebugLogReason.MUTED, s0.actionLog.muteRaid,
                    "${MUTE_10_MS / 60_000} min"
                )
            }
            ACTION_MUTE_TYPE -> {
                // The type's bells go off for good — the same fact as the Settings toggle, so
                // undoing it lives there too. A red/yellow official alert is a separate fact and
                // keeps standing: only this type's zone bells stop.
                val type = intent.getStringExtra(AlertNotificationManager.EXTRA_MUTE_TYPE)
                    ?.let { name -> ThreatType.entries.firstOrNull { it.name == name } }
                if (type != null && type != ThreatType.UNKNOWN) {
                    scope.launch {
                        UserPrefs(applicationContext).setThreatAlertsEnabled(type, false)
                        notificationManager.cancelNotification(NOTIF_ALERT)
                        logAction(
                            DebugLogReason.MUTED, s0.actionLog.muteType,
                            ThreatTypeCatalog.INFO[type]?.shortLabel(AppLanguage.EN).orEmpty()
                        )
                    }
                }
            }
            ACTION_CLEAR_MUTE -> {
                setRaidMute(RaidMute.None)
                logAction(DebugLogReason.UNMUTED, s0.actionLog.unmuteRaid)
            }
            NeutralizedTally.ACTION_NEUTRALIZED_DISMISS -> tally.reset()
            AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS -> episodeTally.reset()
            AlertNotificationManager.ACTION_ALLCLEAR_DISMISSED -> {
                allClearClosed = true
                allClearDeadlineMs = null
                debrisBuffer.abort()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val s = Strings.get(AppLanguage.EN)
        val notif = notificationManager.buildMonitorNotification(s.notifOngoingTitle, "")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_MONITOR, notif)
            locationFgsActive = true
            return
        }
        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // API 34+ rejects the location type when the start lacks the exemption (e.g. an
        // ACTION_MY_PACKAGE_REPLACED background start) or the runtime permission. Try it, then
        // fall back to the base type so monitoring survives; onStartCommand re-promotes later.
        val wantLocation = hasLocationPermission()
        if (wantLocation && startForegroundTyped(baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, notif)) {
            locationFgsActive = true
            return
        }
        if (startForegroundTyped(baseType, notif)) {
            locationFgsActive = false
            return
        }
        // Even the base type was refused - surface the dead state like startResilient intends.
        MonitoringStatus.died(MonitoringStatus.Reason.START_REFUSED)
        MonitorLifecycle.recordNow(this, MonitorEvent.Kind.STOP_OS, "start-refused")
        AlertNotificationManager(applicationContext).postRestartBlocked()
        stopSelf()
    }

    /** Attempts [ServiceCompat.startForeground]; returns false when the platform refuses the
     *  start. A background start denies the location type via [SecurityException], and denies
     *  the whole start on Android 12+ via [android.app.ForegroundServiceStartNotAllowedException]
     *  (an [IllegalStateException]); both mean "do not crash, degrade instead". */
    private fun startForegroundTyped(type: Int, notif: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_MONITOR, notif, type)
        foregroundPromoted = true
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startMonitoring() {
        val prefs = UserPrefs(applicationContext)
        val svcState = ServiceState(applicationContext)
        LocationTracker.start(applicationContext)

        if (BuildConfig.SELF_UPDATE) {
            scope.launch {
                dailyUpdateCheckLoop()
            }
        }

        scope.launch {
            AppSources.registry.connectionMilestones.collect { onConnectionMilestone(it) }
        }

        scope.launch {
            prefs.preferences
                .map { Triple(it.neutralizedTallyEnabled, it.neutralizedTallyAllUkraine, it.language) }
                .distinctUntilChanged()
                .flatMapLatest { (enabled, allUkraine, lang) ->
                    if (!enabled) emptyFlow()
                    else AppSources.registry.removedThreats.map { Triple(it, allUkraine, lang) }
                }
                .collect { (removed, allUkraine, lang) ->
                    if (!allUkraine) {
                        val token = currentToken ?: return@collect
                        if (!inOblast(removed.region, removed.district, removed.locality, token)) return@collect
                    }
                    tally.onResolved(removed, lang)
                }
        }

        // Per-alarm episode buffer: focus-oblast only by design (the All-of-Ukraine
        // opt-in stays with the running tally). Counted only while an alarm window
        // is open; the window close posts the single summary.
        scope.launch {
            prefs.preferences
                .map { it.alarmEpisodeTallyEnabled }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (!enabled) emptyFlow() else AppSources.registry.removedThreats
                }
                .collect { removed ->
                    if (!episodeActive) return@collect
                    val token = currentToken ?: return@collect
                    if (!inOblast(removed.region, removed.district, removed.locality, token)) return@collect
                    episodeTally.onResolved(removed)
                }
        }

        monitoringJob = scope.launch {
            // Restore the announced official-episode identity from persisted ServiceState keys.
            // Only the identity is trusted back, never the level or the sound memory: both are
            // live-process facts. The episode therefore re-announces silently after a restart
            // (see [OfficialFrontier.isFirstRed] and its parse), which is what stops a reboot from
            // re-sirening an alert that was already announced. The audit frontier is never
            // restored at all (see [lastLoggedRawFrontier]), so a restart cannot double-log.
            val _annToken = svcState.officialAnnouncedToken().first().ifBlank { null }
            val _annCity = svcState.officialAnnouncedCity().first().ifBlank { null }
            if (_annToken != null && _annCity != null) {
                lastAnnouncedFrontier = OfficialFrontier(_annToken, AlertLevel.RED, soundedRed = true)
                // Prefer the full persisted row: it carries the level and the gate the episode was
                // announced through. The legacy `token|city` pair is only the fallback for a row
                // written before that key existed, and it parses as unscoped — which is exactly
                // how such a row was gated.
                lastAnnouncedRow = svcState.officialAnnouncedRow().first()
                    .ifBlank { "$_annToken|$_annCity" }
            }
            // Restore open plugin episodes across restarts: ongoing threats stay
            // handled (no re-siren) and the cold-start repost makes them visible.
            val savedZonesJson = svcState.activeZoneAlerts().first()
            if (savedZonesJson.isNotBlank()) {
                runCatching {
                    val obj = JSONObject(savedZonesJson)
                    val restored = mutableMapOf<String, ThreatZone>()
                    for (k in obj.keys()) {
                        runCatching {
                            restored[k] = ThreatZone.valueOf(obj.getString(k))
                        }
                    }
                    if (restored.isNotEmpty()) {
                        notifyPlugin.seedKnown(restored)
                    }
                }
            }

            if (svcState.offlinePendingSince().first() > 0) {
                wasConnected = false
                offlineRestorePending = true
            }

            // The restore is done and the tick loop is about to take over; see [monitoringReady].
            monitoringReady.complete(Unit)

            val nowFlow = MutableStateFlow(System.currentTimeMillis())
            launch {
                var previous = System.currentTimeMillis()
                while (true) {
                    val fast = screenFlow.value.on
                    delay(if (fast) MONITOR_TICK_MS else MONITOR_TICK_IDLE_MS)
                    // Suspension stops our activities, hides our notifications and blocks our
                    // audio — but by design it does NOT stop a foreground service, which is exactly
                    // how a paused Oko kept monitoring from "Active apps". SuspendReceiver ends it
                    // at the gesture; this is the backstop for a broadcast that never arrived, and
                    // it is the reason a paused monitor cannot survive on an OEM that swallows it.
                    if (SuspendState.isSuspended(applicationContext)) {
                        stopSelf()
                        return@launch
                    }
                    val tick = System.currentTimeMillis()
                    val gap = tick - previous
                    previous = tick
                    // The loop slept through a doze (or the process was frozen) and the wall clock
                    // ran on without us. Everything downstream — zones, staleness, episodes — is
                    // evaluated against `now`, so a tick that wakes up hours later would decide
                    // with a clock that is already ancient: stale threats revived, episodes
                    // synthesized and all-clears fired for alerts nobody was ever told about.
                    // Re-baseline the deltas that cannot be trusted, then let this tick decide from a
                    // clean slate: the tick is NOT skipped. A hidden skip here was misread for days as
                    // "post-Doze wakes decide nothing" — they do decide, which is why a stall shows up
                    // as a gap BETWEEN two decisions rather than as two consecutive gaps.
                    if (gap > SLEEP_GAP_REBASE_MS) {
                        resumeAfterSleep(tick, gap)
                    }
                    nowFlow.value = tick
                }
            }

            val registry = AppSources.registry
            val engine = ThreatEngine(registry.typeCatalog.value)
val mappedThreats = registry.allThreats.map { list ->
                list.associate { it.id to it }
            }
            val liveFlow = combine(
                mappedThreats,
                registry.allAlerts,
                LocationTracker.location,
                nowFlow,
                registry.connectionState,
                registry.degradedSince,
                registry.alertsReady,
                pendingSleep,
                screenFlow
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                LiveInputs(
                    rawThreats = values[0] as Map<String, NormalizedThreat>,
                    alerts = values[1] as List<OblastAlert>,
                    gps = values[2] as LatLng?,
                    now = values[3] as Long,
                    alertsReady = values[6] as Boolean,
                    screen = values[8] as ScreenState,
                    sleep = values[7] as PendingSleep?
                )
            }

            combine(
                liveFlow,
                prefs.preferences
            ) { live, p ->
                val (rawThreats, alerts, gps, tickNow, alertsReady) = live
                // Observation time, never the tick clock: see [observationNow]. Every duration,
                // staleness and TTL below measures against when this evaluation runs.
                val now = observationNow(tickNow, System.currentTimeMillis())
                val nightActive = isNightActive(NightConfig(p.nightEnabled, p.nightStartMin, p.nightEndMin), now)
                val dayParams = ZoneParams(
                    p.slowRedKm, p.slowYellowKm, p.fastRedMin, p.fastYellowMin,
                    p.slowRedArmed, p.slowYellowArmed, p.fastRedArmed, p.fastYellowArmed
                )
                val nightZones = p.nightZones
                val params = effectiveZoneParams(dayParams, nightZones, p.nightUseCustomZones, nightActive)
                // Armed bells are per group×tier, stored for day and night, resolved per tick
                // (mirror rule) — the same effectiveArmed the ViewModel/ZonesSheet use.
                val armed = effectiveArmed(
                    ZoneArmed(p.slowRedArmed, p.slowYellowArmed, p.fastRedArmed, p.fastYellowArmed),
                    nightZones, p.nightUseCustomZones, nightActive
                )
                val zoneSirenOverride = if (nightActive) p.nightZoneSirenOverride else p.sirenOverride
                val officialSirenOverride = if (nightActive) p.nightOfficialSirenOverride else p.sirenOverride

                val enabled = p.alertEnabledTypes
                val hiddenTypeStrings = (ThreatType.values().toSet() - p.mapVisibleTypes)
                    .map { it.toEngineString() }.toSet()
                val threats = rawThreats.filterValues { it.type.toThreatType() in enabled }

                val focus = resolveFocus(p.followMe, gps, LocationTracker.isFresh(now), p.pinnedCity)
                val focusLoc = focus.location
                val focusBannerCity = focus.attribution.bannerCity(p.language)
                val focusCityUa = focus.attribution.bannerCityUa
                val focusRegion = focus.attribution.bannerCityUa.let { ua ->
                    val en = focus.attribution.bannerCityEn.ifBlank { Transliteration.transliterate(ua) }
                    p.language.pick(ua, en, en)
                }
                val focusPinned = focus.pinned
                val gpsUnreliable = focus.gpsUnreliable
                // Location health, derived from what LocationTracker can actually vouch for: a
                // read accessBlocked, a received-time fed by the verify loop, and a position. A
                // stale fix is NOT an access failure — see GpsIssue for why that distinction is
                // the whole point.
                val gpsIssue = resolveGpsIssue(
                    followMe = p.followMe,
                    hasPosition = focus.location != null,
                    ageMs = LocationTracker.lastReceivedAtMs.value?.let { now - it },
                    accessBlocked = LocationTracker.accessBlocked.value
                )
                val gpsUnverifiedMin = LocationTracker.lastReceivedAtMs.value
                    ?.let { ((now - it) / 60_000L).toInt().coerceAtLeast(0) } ?: 0
                val focusToken = focus.attribution.token
                currentToken = focusToken

                // One official fact, derived once: scoped (siren gate) + raw (all-clear
                // gate) from the same matcher the UI/widget consume (mirror rule). Red and
                // yellow share this path — official is official; only the notif toggles
                // and tint/copy differ downstream.
                val cityScopeActive = effectiveCityScope(
                    p.officialAlertCityScope, p.nightOfficialAlertCityScope, nightActive
                )
                val official = alerts.officialStateFor(focusToken, focusCityUa, cityScopeActive)
                val officialRaw = alerts.officialStateFor(focusToken, null, false)
                // Held until here because the report is about the focus oblast's own episode, read
                // through the shared gate above — the policy never re-implements official matching.
                applySleepDecision(live, official.alert?.since, p)
                retractSleepNotice(p)
                publishSleepNotice(live, p)
                val focusOblastLevel = official.level
                val focusOblastRawLevel = officialRaw.level
                val focusOblastRawSince = officialRaw.alert?.since
                val focusOblastAlertSince = official.alert?.since
                val focusOblastAlertActive = official.level == AlertLevel.RED
                val focusOblastYellowAlertActive = official.level == AlertLevel.YELLOW

                val activeOfficialAlert = official.alert
                val (officialReason, officialReasonThreatId) = if (activeOfficialAlert != null) {
                    engine.deriveOfficialAlertReason(
                        activeOfficialAlert,
                        rawThreats.values.toList(),
                        focusLoc,
                        params,
                        p.language,
                        now,
                        hiddenTypes = hiddenTypeStrings
                    )
                } else {
                    null to null
                }

                val zoneThreats = if (focusLoc != null && !registry.isThreatDataStale(Monotonic.now())) {
                    val threatList = threats.values.toList()
                    val engineFocus = LatLng(focusLoc.lat, focusLoc.lon)
                    val eval = engine.evaluate(threatList, engineFocus, params, emptySet(), emptySet(), now, prevTiers = lastZoneTiers)
                    stageInbound(eval, threatList, engineFocus, params, engine::propsFor, now).zoneThreats
                } else {
                    emptyMap()
                }

                MonitorState(
                    focusOblastAlertActive = focusOblastAlertActive,
                    focusOblastYellowAlertActive = focusOblastYellowAlertActive,
                    focusOblastLevel = focusOblastLevel,
                    focusOblastRawLevel = focusOblastRawLevel,
                    focusOblastRawSince = focusOblastRawSince,
                    focusToken = focusToken,
                    focusOblastAlertSince = focusOblastAlertSince,
                    focusBannerCity = focusBannerCity,
                    focusCityUa = focusCityUa,
                    focusRegion = focusRegion,
                    focusPinned = focusPinned,
                    officialReason = officialReason,
                    officialReasonThreatId = officialReasonThreatId,
                    officialRegion = activeOfficialAlert?.let { alertRegionName(it, p.language) },
                    officialOblastId = activeOfficialAlert?.canonicalOblastId(),
                    zoneThreats = zoneThreats,
                    params = params,
                    lang = p.language,
                    slowRedArmed = armed.slowRed,
                    slowYellowArmed = armed.slowYellow,
                    fastRedArmed = armed.fastRed,
                    fastYellowArmed = armed.fastYellow,
                    officialRedAlertsEnabled = effectiveOfficialEnabled(p.officialRedAlertsEnabled, p.nightOfficialRedEnabled, nightActive),
                    officialYellowAlertsEnabled = effectiveOfficialEnabled(p.officialYellowAlertsEnabled, p.nightOfficialYellowEnabled, nightActive),
                    zoneSirenOverride = zoneSirenOverride,
                    officialSirenOverride = officialSirenOverride,
                    fallingDebrisDelaySec = p.fallingDebrisDelaySec,
                    autoDismissAllClear = p.autoDismissAllClear,
                    degraded = registry.degraded.value,
                    threats = threats,
                    rawThreats = rawThreats,
                    alerts = alerts,
                    alertsReady = alertsReady,
                    criticalOfflineOverride = p.criticalOfflineOverride,
                    criticalOfflineBypassSilent = p.criticalOfflineBypassSilent,
                    focusLocation = focusLoc,
                    gpsIssue = gpsIssue,
                    gpsUnverifiedMin = gpsUnverifiedMin,
                    nightActive = nightActive,
                    enabled = enabled,
                    hiddenTypes = hiddenTypeStrings,
                    notifyPolicyEnabled = p.notifyPolicyEnabled,
                    zonePolicy = p.zonePolicy,
                    digestMax = p.digestMax,
                    digestWindow = p.digestWindow,
                    digestPerType = p.digestPerType,
                    cityScopeEffective = cityScopeActive
                ) to now
            }.collect { (state, now) ->
                handleState(state, now, engine)
            }
        }
    }

    /** One tick of decisions. `internal` rather than private so a test can drive a single tick
     *  against a fabricated [MonitorState]; every time-dependent input it uses is already a
     *  parameter (`state`, `now`), which is what makes that possible. */
    internal suspend fun handleState(state: MonitorState, now: Long, engine: ThreatEngine) {
        val s = Strings.get(state.lang)

        // One observation per tick; writes only on transition, so the 1s/30s loop is free.
        GpsLog.observe(
            state.gpsIssue,
            now,
            LocationTracker.lastAccuracyM.value?.toInt(),
            state.focusLocation
        )

        // Power timeline for the beta log export. Sits on this tick rather than the connection
        // watchdog so samples keep arriving when the socket supervisor is stopped — the drain
        // report needs the quiet periods most. Self-throttles to 25s.
        BatteryLog.sample(
            now,
            feedThreats = state.rawThreats.size,
            activeZones = state.zoneThreats.size
        )

        if (state.lang != lastChannelLang) {
            lastChannelLang = state.lang
            notificationManager.updateChannels(s)
        }

        // Time-derived lifecycles, re-derived from the wall clock on every tick instead of from
        // an in-process timer that a sleeping CPU would freeze: the all-clear TTL and the debris
        // countdown. Both used to be one-shot `delay` jobs, so after doze the all-clear sat in
        // the shade for hours and the countdown froze at whatever number it had reached.
        retireAllClearIfExpired(now)
        if (debrisBuffer.isRunning) debrisBuffer.shrinkTo(state.fallingDebrisDelaySec.coerceIn(0, 600))

        // Whether THIS tick's focus change took the episode away from us. Read by the morale window
        // below, which must not conclude anything from a region we just left.
        var focusFlipped = false
        // Focus moved (pinned <-> GPS, or a new pinned city): the latched official episode
        // belongs to the old region, so it must not keep announcing/clearing for it. Drop it
        // silently; the new focus can start a fresh episode on the next tick.
        val prevFocusToken = lastFocusTokenSeen
        if (state.focusToken != null) lastFocusTokenSeen = state.focusToken
        if (prevFocusToken != null && state.focusToken != null && state.focusToken != prevFocusToken) {
            focusFlipped = true
            if (lastAnnouncedFrontier != null) {
                clearOfficialAnnounced()
                lastAnnouncedFrontier = null
                lastAnnouncedRow = null
                latchedConfirmedLive = false
            }
            lastLoggedRawFrontier = null
            lastLoggedLevelFrontier = null
            allClearClosed = false
        }

        val latchedEarly = LatchedEpisode.parse(lastAnnouncedRow)
        // Unknown feed holds the episode: until the first real snapshot arrives the
        // latch counts as alive, so no all-clear, no notif teardown, no tally close.
        val latchedAliveEarly = latchedEarly?.resolve(state.alertsReady, state.alerts) == EpisodeTransition.STAY
        if (latchedEarly != null && state.alertsReady && latchedEarly.isStillAlerting(state.alerts)) {
            latchedConfirmedLive = true
        }
        // Both frontiers are live facts about the SAME episode: identity is the canonical
        // region, so a level flip (yellow→red) is an escalation inside one episode, never a
        // second one. Kept distinct only because they are scoped differently.
        val liveScopedFrontier = OfficialFrontier.of(state.focusOblastLevel, state.focusToken)
        val liveRawFrontier = OfficialFrontier.of(state.focusOblastRawLevel, state.focusToken)
        // The live level beats the latch's remembered one, so a red → yellow downgrade
        // rewrites the announcement instead of leaving a red notification standing.
        val effLevelEarly = liveScopedFrontier?.level ?: if (latchedAliveEarly) latchedEarly!!.level else AlertLevel.NONE

        val registry = AppSources.registry
        val typeCatalog = registry.typeCatalog.value
        val isDegradedNow = state.degraded
        // Offline escalation and its age are measured on the monotonic clock (see PluginRegistry);
        // the wall `now` is still used for engine staleness and display stamps below.
        val nowMono = Monotonic.now()
        val isOfflineNow = registry.isOffline(nowMono)
        val offlineSince = registry.degradedSince.value
        val offlineMinutes = if (isOfflineNow && offlineSince != null) {
            ((nowMono - offlineSince) / 60_000L).toInt()
        } else 0

        // The trident owns the official signal, mirroring the header/widget: official red >
        // official yellow > none. No zone-state influence, no channel-pref gating �?" the monitor
        // always shows the live official level.
        val monitorAlertLevel = when {
            state.focusOblastAlertActive -> AlertLevel.RED
            state.focusOblastYellowAlertActive -> AlertLevel.YELLOW
            else -> AlertLevel.NONE
        }
        // The row says what is true for every cause: the cause is log currency, not a diagnosis
        // the transport can make (a single failed lookup is not proof of DNS filtering).
        val monitorTitle = when {
            isOfflineNow -> s.offlineStatusTitle
            state.focusPinned -> String.format(s.notifMonitoringCityFormat, state.focusBannerCity)
            else -> s.notifOngoingTitle
        }

        val monitorText = when {
            isOfflineNow -> offlineLiveBody(s, offlineMinutes)
            // A stale position is NOT a GPS-access problem, and saying so was the bug: the phone
            // may simply be still. Only a denied permission or a disabled provider earns that
            // wording; anything else says what we can actually vouch for.
            state.gpsIssue == GpsIssue.ACCESS_BLOCKED -> s.gpsUnavailableFollowMe
            state.gpsIssue == GpsIssue.NOT_VERIFIED ->
                String.format(s.gpsNotVerifiedFormat, state.gpsUnverifiedMin)
            isDegradedNow -> s.connDegradedBody
            else -> ""
        }

        notifyMonitor(
            title = monitorTitle,
            text = monitorText,
            retryLabel = if (isOfflineNow) s.offlineRetryAction else null,
            progressMax = if (isOfflineNow) 20 else null,
            progressNow = if (isOfflineNow) offlineMinutes else null,
            ignoreLabel = if (isOfflineNow && !ignoreMuted()) s.offlineIgnoreAction else null,
            alertLevel = monitorAlertLevel
        )

        // Critical offline escalation, once per episode: 5 min normally, 1 min while an
        // official alert (red or yellow) is active on the focus oblast. Dedup is keyed off
        // the episode's degraded-since stamp (nulled by the registry on recovery), so there
        // is no reset branch to misfire on transient Connecting states.
        val criticalThresholdMin =
            if (effLevelEarly != AlertLevel.NONE) CRITICAL_OFFLINE_ALARM_MIN
            else CRITICAL_OFFLINE_MIN
        val episode = offlineSince
        if (isOfflineNow && episode != null && state.criticalOfflineOverride &&
            !ignoreMuted() &&
            offlineMinutes >= criticalThresholdMin && criticalFiredForEpisode != episode
        ) {
            criticalFiredForEpisode = episode
            notificationManager.postCriticalOfflineNotification(
                if (criticalThresholdMin == CRITICAL_OFFLINE_ALARM_MIN) s.offlineCriticalAlarmTitle
                else s.offlineCriticalTitle,
                String.format(s.offlineCriticalFormat, criticalThresholdMin)
            )
        }
        // Episode over (registry nulled the stamp on recovery — transient Connecting never
        // does): clear the one-shot and any lingering milestone/critical notifications. Keyed
        // on the episode having ENDED, never on the critical one-shot having fired: with
        // critical-offline off nothing would ever clear the milestone notification.
        if (offlineSince == null) {
            criticalFiredForEpisode = null
            notificationManager.cancelNotification(NOTIF_MILESTONE)
            notificationManager.cancelNotification(NOTIF_OFFLINE_CRITICAL)
        }

        val all = state.threats

        val latched = latchedEarly
        val latchedAlive = latchedAliveEarly
        val effLevel = effLevelEarly
        val effToken = if (latchedAlive) latched!!.token else state.focusToken
        val effCity = if (latchedAlive) latched!!.city else state.focusBannerCity
        val effSince = if (latchedAlive) latched!!.since else state.focusOblastAlertSince
        // The latched episode's own alert, read through ITS gate — never oblast-wide — so a sibling
        // raion that no longer covers the city can neither name the banner nor keep it alive.
        val effAlert = if (latchedAlive) {
            val l = latched!!
            val scoped = l.cityScoped && !l.cityUa.isNullOrBlank()
            state.alerts.officialStateFor(l.token, l.cityUa.takeIf { scoped }, scoped).alert
        } else null
        val (effReason, effReasonId) = if (latchedAlive && effAlert != null) {
            engine.deriveOfficialAlertReason(
                effAlert, state.rawThreats.values.toList(), state.focusLocation,
                state.params, state.lang, now, hiddenTypes = state.hiddenTypes
            )
        } else {
            state.officialReason to state.officialReasonThreatId
        }
        val effRegion = if (latchedAlive) effAlert?.let { alertRegionName(it, state.lang) } ?: state.officialRegion else state.officialRegion

        fun alertTier(id: String, spatial: ThreatZone): ThreatZone? {
            val fast = all[id]?.let { isFastType(it.type.toThreatType(), typeCatalog) } ?: false
            return armedTier(spatial, fast, state.params)
        }

        val alertable = state.zoneThreats.entries
            .mapNotNull { (id, spatial) -> alertTier(id, spatial)?.let { id to it } }
            .toMap()

        // Frequency policy: the plugin owns episodes and verdicts; the service only
        // feeds facts and executes. A preset switch is a fresh start; digest tweaks
        // clear only the rate buckets (never the episodes — no surprise re-siren).
        // Raw user prefs go through the plugin-owned factory; the toggle's meaning
        // (off = every change) is defined there, never here.
        val notifyPrefs = NotifyPrefs.from(
            state.notifyPolicyEnabled, state.zonePolicy,
            state.digestMax, state.digestWindow, state.digestPerType
        )
        val lastPrefs = lastNotifyPrefs
        if (lastPrefs == null || lastPrefs.preset != notifyPrefs.preset) notifyPlugin.reset()
        else if (lastPrefs != notifyPrefs) notifyPlugin.clearBuckets()
        lastNotifyPrefs = notifyPrefs
        // Episode lifetime tracks identity, not freshness: a threat still present in the
        // feed but momentarily stale must not close its episode and re-sire next tick.
        val pluginInputs = all.values.map { t ->
            val alive = t.status != "resolved" && !t.areaOnly
            PluginInput(
                id = t.id,
                tier = state.zoneThreats[t.id],
                alertTier = alertable[t.id],
                type = t.type.toThreatType(),
                alive = alive
            )
        } + notifyPlugin.snapshot().keys.filterNot { it in all }.mapNotNull { id ->
            // Shot-down id in its grace window: keep the episode frozen so the
            // same-id respawn reads as the same kill, never a new onset.
            if (AppSources.registry.wasUserShotRecently(id)) {
                PluginInput(id, null, null, ThreatType.UNKNOWN, alive = true, shotGrace = true)
            } else null
        }
        // Frequency policy runs only when something can actually be delivered. While a raid is muted
        // there is no verdict to give, and ticking the plugin anyway would spend the episode's
        // "already sounded" memory on a sound nobody heard — leaving the user unmuted, mid-attack,
        // in silence. Skipping the TICK (never the zone maps) keeps that memory intact and the
        // first tick after the mute announces the attack; the audit rows still describe what
        // happened, as BELL_MUTED rather than FIRED.
        val raidMutedNow = bellsMuted()
        val verdicts = if (raidMutedNow) emptyMap() else notifyPlugin.tick(pluginInputs, notifyPrefs, now)
        val presence = JSONObject().apply {
            for ((k, v) in notifyPlugin.snapshot()) put(k, v.name)
        }.toString()
        if (presence != lastPersistedPresence) {
            lastPersistedPresence = presence
            persistPresence(presence)
        }

        /** Desired end-state for NOTIF_ALERT. null = nothing should be showing. */
        data class Primary(
            val identity: String,
            val title: String,
            val body: String,
            val revealThreat: NormalizedThreat?,
            val silent: Boolean,
            val isOnset: Boolean,
            val zone: ThreatZone?,
            val level: String,
        )

        /** Unified official-episode identity for ANY level: the canonical region only
         *  (`token|city`). Level and `since` are deliberately absent — one episode per region,
         *  "red, yellow, or red-then-yellow" included, and a re-stamped `since` is the same
         *  episode, never a fresh onset. */
        fun officialEpisodeId(state: MonitorState): String? {
            if (state.focusOblastLevel == AlertLevel.NONE) return null
            val token = state.focusToken ?: return null
            return "$token|${state.focusBannerCity}"
        }

        /** Scoped frontier (identity + live level) — the episode being announced. */
        fun announcedFrontier(state: MonitorState): OfficialFrontier? =
            OfficialFrontier.of(state.focusOblastLevel, state.focusToken)

        /** Raw (unscoped) frontier: the audit-log equivalent, so an official alert outside the
         *  notif scope is still logged — and a level flip there is still one episode. */
        fun rawFrontier(state: MonitorState): OfficialFrontier? =
            OfficialFrontier.of(state.focusOblastRawLevel, state.focusToken)

        /** True when the area of the last-shown all-clear matches the current focus area. When
         *  the city is unknown (e.g. after a restart) fall back to "an all-clear is showing, so
         *  a new alert must supersede it" — never leave one beside an active alert. */
        fun allClearSameArea(state: MonitorState): Boolean {
            val city = lastCleanAllClearCity ?: return notificationManager.isAllClearNotificationActive()
            return city == state.focusBannerCity
        }

        fun isNewEpisode(state: MonitorState): Boolean =
            officialAnnouncementIsOnset(lastAnnouncedFrontier, announcedFrontier(state))

        /** Build the desired notification end-state from current tick inputs. */
        fun buildPrimary(state: MonitorState, all: Map<String, NormalizedThreat>): Primary? {
            val s = Strings.get(state.lang)
            if (alertable.isNotEmpty()) {
                val (id, zone) = alertable.entries.sortedBy { it.value.ordinal }.first()
                val t = all[id]
                return Primary(
                    identity = "zone|$id|$zone",
                    title = bannerFor(zone, s),
                    body = t?.let { threatBody(it, state.lang) + etaSuffix(t, state) } ?: s.notifBodyRegion,
                    revealThreat = t,
                    silent = false,
                    isOnset = verdicts[id]?.kind == VerdictKind.SOUND,
                    zone = zone, level = if (zone == ThreatZone.INNER) "red" else "yellow"
                )
            }
            // Official is official: one branch for any level. Only the toggle gate,
            // copy and sound differ per level — the episode latch is shared.
            // Region-latched: while the announced oblast is still raw-active, the
            // effective official is the latched one, not the current focus (pinned -> follow-me).
            if (effLevel != AlertLevel.NONE) {
                // The identity names the EFFECTIVE episode — the latch-aware token that pairs with
                // effCity above — never the live frontier: a restored latch holds the episode while
                // the focus-scoped frontier is null (no fix yet, or the alert narrowed off the
                // focus city), and there effLevel is non-NONE with no frontier to read a token from.
                val effFrontier = OfficialFrontier.of(effLevel, effToken) ?: return null
                val announced = if (effLevel == AlertLevel.RED) state.officialRedAlertsEnabled
                else state.officialYellowAlertsEnabled
                if (announced) {
                    val onset = isNewEpisode(state)
                    if (effLevel == AlertLevel.RED) {
                        val reasonThreat = effReasonId?.let { all[it] }
                        return Primary(
                            identity = "$OFFICIAL_ID_RED${effFrontier.token}|$effCity|$effReasonId",
                            title = String.format(s.alertBannerFormat, effCity),
                            body = (effReason ?: effRegion ?: state.focusRegion) + etaSuffix(reasonThreat, state),
                            revealThreat = reasonThreat,
                            silent = !onset,
                            isOnset = onset,
                            zone = null, level = effLevel.name.lowercase()
                        )
                    }
                    return Primary(
                        identity = "$OFFICIAL_ID_YELLOW${effFrontier.token}|$effCity",
                        title = String.format(s.alertYellowBannerFormat, effCity),
                        body = effReason ?: effRegion ?: state.focusRegion,
                        revealThreat = null, silent = !onset,
                        isOnset = onset,
                        zone = null, level = "yellow"
                    )
                }
            }
            return null
        }

        /**
         * The "Mute <type>" action for the alert about to be posted, or null when there is no
         * type to mute: no revealed threat (a plain region red, any yellow), an unconfirmed
         * [ThreatType.UNKNOWN], or a type whose bells are already off — a button that says "mute"
         * about something already muted is a lie, so those alerts keep [AlertActions.ok].
         */
        fun muteTypeActionFor(primary: Primary?, state: MonitorState): MuteTypeAction? {
            val type = primary?.revealThreat?.type?.toThreatType() ?: return null
            if (type == ThreatType.UNKNOWN || type !in state.enabled) return null
            val info = ThreatTypeCatalog.INFO[type] ?: return null
            return MuteTypeAction(
                label = String.format(Strings.get(state.lang).alertActionMuteTypeFormat, info.shortLabel(state.lang)),
                type = type
            )
        }

        /** Reconcile official-episode side-effects for ANY level: wakeLock, DebugLog ON
         *  (always — the log sees every official episode even when its notifs are off
         *  or a zone alert wins the tick), persistence, all-clear.
         *
         *  Returns the one fact only this call can know: did it open a new official episode THIS
         *  tick? The morale window cannot recompute it — the frontier is written back below. */
        fun reconcileEpisode(primary: Primary?, state: MonitorState, all: Map<String, NormalizedThreat>): Boolean {
            val frontier = announcedFrontier(state)
            val raw = rawFrontier(state)
            val mutedNow = bellsMuted()
            var scopedOffLogged = false
            // A new region, or the first red of a live one. A downgrade (red → yellow) and a
            // repeat red (a level that flaps) are neither — they only rewrite the announcement.
            val sounding = officialAnnouncementIsOnset(lastAnnouncedFrontier, frontier)
            if (sounding) {
                // A new official episode supersedes a lingering all-clear — but only for the
                // SAME city/raion the all-clear declared; an unrelated region's all-clear is
                // honest history and stays. Fires even when the new episode's own notification
                // is toggled off, since the raw feed contradicts the all-clear.
                if (allClearSameArea(state)) {
                    clearAllClearNotification()
                }
                val audible = if (state.focusOblastLevel == AlertLevel.RED) state.officialRedAlertsEnabled
                else state.officialYellowAlertsEnabled
                val reasonThreat = if (state.focusOblastLevel == AlertLevel.RED) {
                    state.officialReasonThreatId?.let { all[it] }
                } else null
                // The official row names the OFFICIAL area only — the cause is linked by
                // threatId to its own swept zone/region row (a separate event), never
                // borrowed into this one as locality/distance. threatId is attached only
                // when a focus exists, so the cause row actually appears (computeSweep
                // emits nothing without a focus point) and the link can't dangle.
                val locality = state.officialRegion ?: state.focusCityUa
                val attachCause = state.focusLocation != null
                when {
                    // A zone alert won the shared slot — the official episode still happened.
                    primary?.zone != null -> DebugLog.recordOfficial(
                        DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                        sirenOverride = state.officialSirenOverride,
                            notified = false, reason = DebugLogReason.COALESCED,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            scopeOblastId = state.officialOblastId ?: state.focusToken,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                    )
                    audible -> {
                        if (!mutedNow) wakeLockManager.acquireForAlert()
                        // A muted raid is recorded, not delivered: the row says BELL_MUTED so
                        // the Logs tab cannot claim a siren that was deliberately suppressed.
                        DebugLog.recordOfficial(
                            DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                            sirenOverride = state.officialSirenOverride,
                            notified = !mutedNow,
                            reason = if (mutedNow) DebugLogReason.BELL_MUTED else DebugLogReason.FIRED,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            scopeOblastId = state.officialOblastId ?: state.focusToken,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                        )
                        persistOfficialAnnounced(state)
                    }
                    else -> DebugLog.recordOfficial(
                        DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                        sirenOverride = state.officialSirenOverride,
                            notified = false, reason = DebugLogReason.TOGGLE_OFF,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            scopeOblastId = state.officialOblastId ?: state.focusToken,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                    )
                }
                lastAnnouncedFrontier = frontier!!.rememberSounded(lastAnnouncedFrontier)
                lastAnnouncedRow = announcedRow(state)
                lastLoggedRawFrontier = raw
                lastLoggedLevelFrontier = frontier
            } else if (raw != null && raw.token != lastLoggedRawFrontier?.token) {
                // Raw official alert outside the notif scope (city-scope on, raion not covering
                // the focus city, or a muted/sleep window): record it silently so the Logs tab
                // still carries EVERY official alert, even when no notif was posted. Identity
                // alone decides — a level flip here is the same episode, hence `token !=`.
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa,
                    distanceKm = null,
                    scopeOblastId = raw.token,
                    level = state.focusOblastRawLevel,
                    now = System.currentTimeMillis()
                )
                lastLoggedRawFrontier = raw
                lastLoggedLevelFrontier = raw
            }
            // A downgrade (red → yellow) is neither an onset nor an end: the notification is
            // silently rewritten and nothing at all used to reach the audit trail, so an hour of
            // yellow read as an unbroken hour of red in the story. The level change is now its
            // own row — INFO, so it never sounds and never moves the tallies.
            val loggedLevel = lastLoggedLevelFrontier
            val flippedLevel = (frontier ?: raw)?.takeIf { f ->
                loggedLevel != null && loggedLevel.token == f.token && loggedLevel.level != f.level
            }
            if (!sounding && flippedLevel != null) {
                DebugLog.recordOfficialLevel(
                    flippedLevel.level,
                    state.officialRegion ?: state.focusCityUa,
                    flippedLevel.token,
                    System.currentTimeMillis()
                )
                lastLoggedLevelFrontier = flippedLevel
            }
            val suppressAllClear = sounding && allClearSameArea(state)
            // The level's own toggle, never the red||yellow master: an episode whose level is
            // switched off was never announced, so it must not be "cleared" either — but its
            // latch must not stay armed either, or re-enabling the level later would fire a
            // stale all-clear for an episode that ended long ago.
            val levelStillWanted = latched != null && state.officialLevelEnabled(latched.level)
            if (!suppressAllClear && latched != null && !latchedAlive && levelStillWanted) {
                if (latched.resolveRestored(latchedConfirmedLive, EpisodeTransition.ENDED) == RestoredResolution.EXPIRE_SILENTLY) {
                    // Resurrected latch never observed live in this lifetime: the episode
                    // ended while we were dead. Drop it without notification, chime or log.
                    dropOfficialEpisode()
                    return false
                }
                if (alertable.isEmpty()) cancelAlert()
                val reposted = notificationManager.isAllClearNotificationActive()
                val allClearCity = latched.city
                lastCleanAllClearCity = allClearCity
                if (reposted) DebugLog.recordAllClearReposted(allClearCity, System.currentTimeMillis())
                lastChannelLang = state.lang
                val s = Strings.get(state.lang)
                val delay = state.fallingDebrisDelaySec.coerceIn(0, 600)
                lastEpisodeReplay = episodeTally.snapshot()
                // A second post for an all-clear that is already in the shade is a contract
                // violation ("one clear per episode") and the reason a TTL used to look broken.
                // Leave a lifecycle row for it — an INFO row, so the episode's single
                // OFFICIAL_OFF record is untouched.
                // A muted raid is not told anything, all-clear included: the mute belongs to the
                // whole raid, and an all-clear arriving in the shade is still a notification the
                // user asked not to get. The mute itself is released by the clear, so the NEXT
                // raid rings normally. The debris countdown still runs (it is a safety timer),
                // it just has nothing to update.
                if (!mutedNow) {
                    if (delay > 0) {
                        postAllClear(s, allClearCity, debrisSeconds = delay, silent = false, replay = lastEpisodeReplay)
                        debrisBuffer.start(durationSeconds = delay)
                    } else {
                        postAllClear(s, allClearCity, debrisSeconds = 0, silent = false, replay = lastEpisodeReplay)
                    }
                    // Armed from the one post that opens the notification; the debris re-posts
                    // must never restart the clock.
                    scheduleAllClearExpiry(state.autoDismissAllClear, now)
                }
                setRaidMute(raidMute.cleared())
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride,
                    notified = !mutedNow,
                    reason = if (mutedNow) DebugLogReason.BELL_MUTED else DebugLogReason.FIRED,
                    threatId = null, threatType = null,
                    locality = effRegion ?: allClearCity, distanceKm = null,
                    scopeOblastId = latched.token,
                    now = System.currentTimeMillis()
                )
                dropOfficialEpisode()
                scopedOffLogged = true
                lastLoggedRawFrontier = null
            } else if (!suppressAllClear && latched != null && !latchedAlive) {
                // The level is switched off: no all-clear (it was never announced through that
                // gate), but the latch is dropped so it can never fire one late. The
                // `OFFICIAL_OFF` row below still closes its audit story.
                dropOfficialEpisode()
            }
            if (!scopedOffLogged && raw == null && lastLoggedRawFrontier != null) {
                // Raw alert ended outside the notif scope: close its audit row silently.
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = null, distanceKm = null,
                    scopeOblastId = lastLoggedRawFrontier?.token,
                    now = System.currentTimeMillis()
                )
                lastLoggedRawFrontier = null
            }
            val shownLevel = officialLevelOf(lastShownId)
            if (shownLevel != null && primary == null &&
                latchedAlive && state.officialLevelEnabled(shownLevel)
            ) {
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa, distanceKm = null,
                    scopeOblastId = latched?.token,
                    now = System.currentTimeMillis()
                )
            }

            return sounding
        }

        /** Reconcile NOTIF_ALERT post/cancel based on primary vs lastShownId. */
        suspend fun reconcileNotif(primary: Primary?, state: MonitorState) {
            val s = Strings.get(state.lang)
            val actions = AlertActions(
                ok = s.alertActionOk,
                muteType = muteTypeActionFor(primary, state),
                muteRaid = s.alertActionMuteRaid,
                mute10 = s.alertActionMute10
            )
            if (primary?.identity != lastShownId) {
                // Mute means mute: while it is on, a new alert is NOT posted at all — no sound,
                // no vibration, no notification. `lastShownId` still advances so the state stays
                // convergent (a suppressed post is not a post the user dismissed, and the tick
                // cannot be left trying to raise it forever). The attack itself is still recorded
                // by the sweep and by reconcileEpisode, so the Logs tab can prove it happened.
                val muted = bellsMuted()
                if (primary != null && muted) {
                    lastShownId = primary.identity
                    return
                }
                // Don't tear down a live official notification on a flicker tick:
                // any official id stays up while the latched oblast is still raw-active —
                // and only while the level we posted it at is still switched on.
                val shownLevel = officialLevelOf(lastShownId)
                val shownOfficialStillLive =
                    shownLevel != null && latchedAlive && state.officialLevelEnabled(shownLevel)
                // A fresh official onset holds the shared slot: the siren that just summoned
                // the user named the official area and its reason, so an outer-zone onset
                // seconds later must not swap it out from under them. Heard, not seen: the
                // zone verdict still sounds and is audit-logged. lastShownId stays official,
                // so the zone rewrite lands on its own ticks after the hold lapses.
                val holdOfficialSlot = officialSlotHeld(
                    primary?.zone, shownOfficialStillLive, lastOfficialOnsetShownMs,
                    System.currentTimeMillis()
                )
                when {
                    primary == null -> {
                        if (!shownOfficialStillLive) {
                            cancelAlert()
                        }
                    }
                    primary.isOnset -> {
                        wakeLockManager.acquireForAlert()
                        val muted = bellsMuted()
                        val sirenOverride = state.zoneSirenOverride ?: state.officialSirenOverride
                        if (primary.zone != null) {
                            primary.revealThreat?.let { t ->
                                DebugLog.recordZoneFired(
                                    threatId = t.id,
                                    threatType = t.type.toThreatType(),
                                    tier = primary.zone,
                                    night = state.nightActive,
                                    sirenOverride = state.zoneSirenOverride,
                                    distanceKm = distanceFromFocusKm(t, state),
                                    locality = t.locality ?: t.district ?: t.region,
                                    scopeOblastId = threatOblastId(t),
                                    position = LogPosition(t.lat, t.lon),
                                    // The same pure decision the poster takes, asked here so the
                                    // audit row names the channel that actually rang.
                                    channel = AlertNotificationManager.alertChannelId(
                                        primary.zone, primary.level, sirenOverride
                                    ),
                                    now = System.currentTimeMillis()
                                )
                            }
                        }
                        if (holdOfficialSlot) return
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            sirenOverride,
                            revealThreat = primary.revealThreat,
                            muted = muted, actions = actions)
                        lastOfficialOnsetShownMs =
                            if (primary.zone == null) System.currentTimeMillis() else 0L
                    }
                    alertNotificationShowing() -> {
                        if (holdOfficialSlot) return
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            state.zoneSirenOverride ?: state.officialSirenOverride,
                            revealThreat = primary.revealThreat, silent = true,
                            muted = bellsMuted(), actions = actions)
                        if (primary.zone != null) lastOfficialOnsetShownMs = 0L
                    }
                    else -> { /* dismissed, don't re-raise */ }
                }
                lastShownId = primary?.identity
            }
        }

        // Is the focus observing a live official alert right now? That is the evidence the morale
        // window needs, read straight off the live frontier. The latched confirmation is the wrong
        // proxy: it only becomes true on a tick *after* the announce, so a report's fate depended on
        // which of two ticks did the announcing.
        val episodeLiveNow = liveScopedFrontier != null && liveScopedFrontier.level != AlertLevel.NONE
        // Invoke the reconcile pipeline. Verdicts (plugin-owned) already decided
        // sound/silent/suppress above; below only executes. Official paths untouched.
        val primary = buildPrimary(state, all)
        val officialOnset = reconcileEpisode(primary, state, all)
        // cold start re-post suppression: never (see OFFICIAL_ALERT vs all-clear below).
        reconcileNotif(primary, state)
        // Morale-only episode window: region-latched, same gate as the all-clear.
        // An unconfirmed (never observed live) window closes silently — no summary
        // for an episode this lifetime never owned.
        //
        // It ends on exactly one fact — the feed saying clear — and refuses to conclude anything
        // from any other reason for "no longer active": a focus move drops the old region's latch
        // at the top of this tick, and a feed that has not reported yet proves nothing either way.
        // Posting on one of those and re-opening the window a tick later is how a 27-minute raid
        // was summarised as "1 min".
        val alarmNowActive = effLevel != AlertLevel.NONE
        if (alarmNowActive && !episodeActive) {
            episodeActive = true
            // Stamp the window with the evidence available now, and let it keep it: the close must
            // not have to re-prove liveness after the pipeline has already torn the episode down.
            episodeTally.begin(effCity, now, fromOnset = officialOnset, live = episodeLiveNow)
        } else if (alarmNowActive) {
            // A window opened from a restored latch becomes reportable the moment the live frontier
            // shows this alarm while it is open.
            if (episodeLiveNow) episodeTally.markLive()
        } else if (episodeActive) {
            when {
                // Our focus left the region. Not an ending — drop the window, keep any posted row.
                // Checked before the readiness arm: a focus move is our own fact and needs no feed,
                // so an unready tick must not mask it.
                focusFlipped -> {
                    episodeActive = false
                    episodeTally.abandon()
                }
                // No snapshot heard yet: the same refusal to conclude that `LatchedEpisode.resolve`
                // makes, kept here for the case where no latch is left to fall back on.
                !state.alertsReady -> Unit
                else -> {
                    episodeActive = false
                    // One owner decides whether the window has anything to report — the window
                    // itself — instead of a service latch sampled across the teardown pipeline.
                    episodeTally.finish(state.focusBannerCity, state.lang, state.officialAlertsEnabled, now)
                    lastEpisodeReplay = episodeTally.snapshot()
                }
            }
        }

        val nowForSweep = System.currentTimeMillis()
        val hasNewZone = state.zoneThreats.keys.any { it !in lastZoneTiers }
        lastZoneTiers = state.zoneThreats
        if (hasNewZone || nowForSweep - lastSweepAtMs >= SWEEP_THROTTLE_MS) {
            lastSweepAtMs = nowForSweep
            DebugLog.sweep(
                DebugLogContext(
                    threats = state.rawThreats.ifEmpty { all },
                    focus = state.focusLocation,
                    token = state.focusToken,
                    enabledTypes = state.enabled,                    zoneThreats = state.zoneThreats,
                    alertable = alertable,                    verdicts = verdicts,
                    winnerId = primary?.zone?.let { primary.revealThreat?.id },
                    muted = bellsMuted(),
                    night = state.nightActive,
                    sirenOverride = state.zoneSirenOverride,
                    now = now,
                    focusToken = state.focusToken,
                    typeCatalog = typeCatalog
                )
            )
        }

        if (state.zoneThreats.isEmpty() && effLevel == AlertLevel.NONE) {
            val since = emptySince
            if (since == null) {
                emptySince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since >= ALL_CLEAR_GRACE_MS) {
                emptySince = null
                cancelAlert()
                setRaidMute(raidMute.cleared())
            }
        } else {
            emptySince = null
        }

    }

    private fun persistPresence(json: String) {
        scope.launch {
            ServiceState(applicationContext).setActiveZoneAlerts(json)
        }
    }

    private fun bannerFor(zone: ThreatZone, s: Strings.StringSet): String = when (zone) {
        ThreatZone.INNER -> s.redZoneAlert
        ThreatZone.OUTER -> s.yellowZoneAlert
    }

    private fun distanceFromFocusKm(t: NormalizedThreat?, state: MonitorState): Double? {
        val focus = state.focusLocation ?: return null
        if (t == null) return null
        return distanceFlat(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
    }

    private fun etaSuffix(t: NormalizedThreat?, state: MonitorState): String {
        if (t == null || state.focusLocation == null) return ""
        val distKm = distanceFromFocusKm(t, state) ?: return ""
        val eta = ThreatEngine.etaMinutes(distKm, t.speedKmh) ?: return ""
        val unit = Strings.get(state.lang).etaUnit
        return ", ~${ThreatEngine.formatEtaMinutes(eta)} $unit"
    }

    private fun notifyMonitor(
        title: String,
        text: String,
        retryLabel: String?,
        progressMax: Int? = null,
        progressNow: Int? = null,
        ignoreLabel: String? = null,
        alertLevel: AlertLevel = AlertLevel.NONE
    ) {
        if (title == lastMonitorTitle && text == lastMonitorText && retryLabel == lastMonitorRetry &&
            progressMax == lastMonitorProgressMax && progressNow == lastMonitorProgressNow && ignoreLabel == lastMonitorIgnore &&
            alertLevel == lastMonitorAlertLevel
        ) return
        lastMonitorTitle = title
        lastMonitorText = text
        lastMonitorRetry = retryLabel
        lastMonitorProgressMax = progressMax
        lastMonitorProgressNow = progressNow
        lastMonitorIgnore = ignoreLabel
        lastMonitorAlertLevel = alertLevel
        notificationManager.safeNotify(
            NOTIF_MONITOR,
            notificationManager.buildMonitorNotification(title, text, retryLabel, progressMax, progressNow, ignoreLabel, alertLevel)
        )
    }

    /** Maps supervisor milestone events to one-shot notifications. Stateless: the flow is
     *  already once-per-episode by supervisor guarantee, so there is nothing to reset. */
    private fun onConnectionMilestone(milestone: ConnectionMilestone) {
        if (ignoreMuted()) return
        val registry = AppSources.registry
        val nowMono = Monotonic.now()
        if (!registry.isOffline(nowMono)) return
        val s = Strings.get(lastChannelLang ?: AppLanguage.EN)
        val ageMin = registry.degradedSince.value
            ?.let { ((nowMono - it) / 60_000L).toInt().coerceAtLeast(0) }
            ?: milestone.minutes
        when (milestone) {
            ConnectionMilestone.M5_CRITICAL -> Unit // critical is owned by the per-tick block
            else -> notificationManager.postOfflineNotification(
                s.offlineStatusTitle, offlineLiveBody(s, ageMin)
            )
        }
    }

    private fun offlineLiveBody(s: Strings.StringSet, minutes: Int): String {
        return String.format(s.offlineLiveFormat, minutes)
    }

    /**
     * Immediate feedback for a shade Retry tap. This is the only Retry button the shade has —
     * the offline escalations carry no actions (see [AlertNotificationManager.postOfflineNotification])
     * — so the row it repaints is always the row the user pressed. The tap runs retryNow(), but the
     * monitor row is only painted by the next [handleState] tick, and that tick's content is
     * identical (same offline minutes, same retry label), so its dedup drops the post and the tap
     * looked inert. Swap the body for an explicit "reconnecting" line, keep the headline and both
     * action buttons, and clear the memo so the next real tick is free to repaint over this.
     */
    private fun showRetryAcknowledged(s: Strings.StringSet) {
        val title = lastMonitorTitle ?: s.offlineStatusTitle
        lastMonitorTitle = null
        lastMonitorText = null
        lastMonitorRetry = null
        lastMonitorProgressMax = null
        lastMonitorProgressNow = null
        lastMonitorIgnore = null
        lastMonitorAlertLevel = AlertLevel.NONE
        notificationManager.safeNotify(
            NOTIF_MONITOR,
            notificationManager.buildMonitorNotification(
                title = title,
                text = s.offlineReconnectAck,
                retryLabel = s.offlineRetryAction,
                ignoreLabel = if (!ignoreMuted()) s.offlineIgnoreAction else null
            )
        )
    }

    private suspend fun postAlert(
        zone: ThreatZone?,
        level: String,
        title: String,
        body: String,
        sirenOverride: Boolean,
        revealThreat: NormalizedThreat? = null,
        silent: Boolean = false,
        muted: Boolean = false,
        actions: AlertActions? = null
    ) {
        notificationManager.postAlertNotification(
            channel = AlertNotificationManager.alertChannelId(zone, level, sirenOverride),
            title = title,
            body = body,
            revealThreat = revealThreat,
            silent = silent,
            muted = muted,
            actions = actions
        )
    }

    private fun cancelAlert() {
        lastOfficialOnsetShownMs = 0L
        notificationManager.cancelNotification(NOTIF_ALERT)
    }

    private fun alertNotificationShowing(): Boolean =
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.activeNotifications.any { it.id == NOTIF_ALERT }
        }.getOrDefault(false)

    /** The level an official notification id was posted at; null when [id] isn't one (a zone id,
     *  or nothing posted yet). Reads the same consts [buildPrimary] writes the ids with. */
    private fun officialLevelOf(id: String?): AlertLevel? = when {
        id == null -> null
        id.startsWith(OFFICIAL_ID_RED) -> AlertLevel.RED
        id.startsWith(OFFICIAL_ID_YELLOW) -> AlertLevel.YELLOW
        else -> null
    }

    /** The persisted announcement row for an episode: `LEVEL|token|since|city[|cityUa|scoped]`.
     *  The two trailing fields remember WHICH gate announced this episode, because that is the
     *  only gate allowed to end it — a city-scoped episode must not need the whole oblast to fall
     *  quiet. [LatchedEpisode.parse] reads both the new and the old shape. */
    private fun announcedRow(state: MonitorState): String =
        "${state.focusOblastLevel}|${state.focusToken.orEmpty()}|" +
            "${state.focusOblastAlertSince.orEmpty()}|${state.focusBannerCity}|" +
            "${state.focusCityUa.orEmpty()}|${if (state.cityScopeEffective) 1 else 0}"

    private fun persistOfficialAnnounced(state: MonitorState) {
        scope.launch {
            val svc = ServiceState(applicationContext)
            svc.setOfficialAnnounced(
                state.focusToken, state.focusOblastAlertSince, state.officialReasonThreatId,
                state.focusBannerCity
            )
            // The whole row, so the gate this episode was announced through (city scope, city)
            // outlives the process — a restart used to silently downgrade a city-level episode to
            // oblast-wide, which is how the missing all-clear came back.
            svc.setOfficialAnnouncedRow(announcedRow(state))
        }
    }

    private fun clearOfficialAnnounced() {
        scope.launch {
            val svc = ServiceState(applicationContext)
            svc.setOfficialAnnounced(null, null, null)
            svc.setOfficialAnnouncedRow(null)
        }
    }

    /** The in-memory half of an episode teardown: latch, frontier and the "observed live" fact.
     *  Every ending that is not an all-clear post goes through here, so none of the three can
     *  leak into the next episode. [clearOfficialAnnounced] persists the row. */
    private fun dropOfficialEpisode() {
        clearOfficialAnnounced()
        lastAnnouncedFrontier = null
        lastAnnouncedRow = null
        latchedConfirmedLive = false
    }

    private fun postAllClear(s: Strings.StringSet, city: String, debrisSeconds: Int = 0, silent: Boolean = false, muted: Boolean = false, replay: List<FlourishRecord> = emptyList()) {
        // A post is the open: the debris tick and the TTL belong to THIS notification, not to
        // whatever the user swiped or a new episode superseded before it.
        allClearClosed = false
        val body = if (debrisSeconds > 0) {
            val mm = debrisSeconds / 60
            val ss = debrisSeconds % 60
            val formattedTime = String.format("%d:%02d", mm, ss)
            "${s.allClearText} ${String.format(s.fallingDebrisNotifCountdown, formattedTime)}"
        } else {
            s.allClearText
        }
        notificationManager.postAllClearNotification(
            title = String.format(s.allClearTitle, city),
            body = body,
            silent = silent,
            muted = muted,
            replay = replay
        )
    }

    /**
     * The ONLY way the all-clear is torn down, whatever the reason (swiped, superseded by a
     * new episode, TTL expired). One owner for every teardown side-effect so none can leak
     * into the next episode.
     */
    private fun clearAllClearNotification() {
        allClearDeadlineMs = null
        debrisBuffer.abort()
        allClearClosed = true
        notificationManager.cancelNotification(NOTIF_ALLCLEAR)
    }

    /**
     * Arms the all-clear TTL, at most once per live notification. Called from the post that
     * opens the notification; the debris re-posts neither reset nor re-arm it — the TTL is
     * defined to run from the first post, and re-arming would leave it in the shade forever.
     * Returns true when it armed a new timer, false when one already pends for this notification.
     * [now] is the tick's wall clock, passed in so the TTL stays a pure function of the tick.
     */
    private fun scheduleAllClearExpiry(autoDismiss: Boolean, now: Long): Boolean {
        if (allClearDeadlineMs != null) return false
        if (!autoDismiss) return false
        allClearDeadlineMs = now + ALL_CLEAR_TTL_MS
        return true
    }

    /**
     * The all-clear's live lifecycle as (deadline, closed). Only [retireAllClearIfExpired] and
     * the debris ticks read these fields in production, and both are otherwise observable solely
     * by waiting out the 20-minute TTL — so tests assert them here. Not production API.
     */
    internal fun allClearLifecycleForTest(): Pair<Long?, Boolean> = allClearDeadlineMs to allClearClosed

    /**
     * The identity of the alert notification the last tick decided on. Production only ever compares
     * it for change, so which episode it names — and for which city — is observable here alone.
     * Not production API.
     */
    internal fun lastShownIdForTest(): String? = lastShownId

    /**
     * Retire the all-clear once its TTL has passed. Driven from the monitor tick against the wall
     * clock instead of a coroutine timer: `delay` is uptime-based, so after the CPU sleeps the
     * timer has not moved while hours have — the all-clear then sat in the shade with a frozen
     * countdown, which is the "it never auto-cleared" report.
     */
    private fun retireAllClearIfExpired(now: Long) {
        val deadline = allClearDeadlineMs ?: return
        if (now < deadline) return
        allClearDeadlineMs = null
        if (allClearClosed) return
        clearAllClearNotification()
        DebugLog.recordAllClearExpired(lastCleanAllClearCity, now)
    }

    /** One user action, recorded in the audit trail's own Actions filter. High-signal only —
     *  this ring is the alert audit trail and a per-tap stream would evict it. */
/**
     * Re-baseline after the tick loop slept through a doze. Everything the reconcile does is a
     * function of `now`, and the feed it would reason about is minutes-to-hours old the moment we
     * come back, so the tick that wakes us must decide nothing. Dropped are the deltas that would
     * otherwise be measured across a gap nobody observed:
     *  - [lastZoneTiers] — the spatial hysteresis band would compare against a world that no
     *    longer exists, so the first threat after a sleep could be denied its escalation.
     *  - [lastSweepAtMs] — forced to sweep immediately, so the audit trail shows fresh facts
     *    instead of staying silent for another throttle window.
     *  - [emptySince] — a single post-gap tick must not age a zone episode straight into an
     *    all-clear nobody was ever warned about.
     */
    private fun resumeAfterSleep(now: Long, gapMs: Long) {
    lastZoneTiers = emptyMap()
    lastSweepAtMs = 0L
    emptySince = null
    val window = SleepWindow(startedAtMillis = now - gapMs, wokeAtMillis = now)
    DebugLog.recordPaused(window, now)
    pendingSleep.value = PendingSleep(window = window)
}

    /**
     * Executes the [SleepPolicy] verdict for a sleep the loop just came out of. Deliberately a
     * transport: it consumes the pending sleep, calls the policy once, and does what it says. Every
     * branch that could be mis-ordered (muted, exempt, too short, unreadable feed) lives in the pure
     * decision, so there is no policy in this function to get wrong.
     *
     * The evidence is measured here rather than in the tick because only the evaluation has the
     * feed, and it is frozen into the pending value because the window is expressed against the
     * feed's own clock — re-deriving it on a later tick would slide it forward and grow the count.
     */
    private fun applySleepDecision(
        live: LiveInputs,
        focusAlertSince: String?,
        prefs: UserPreferences
    ) {
        val pending = live.sleep ?: return

        val evidence = pending.evidence
            ?: SleepPolicy.evidenceFor(
                pending.window,
                live.rawThreats.values,
                live.alerts,
                focusAlertSince
            )

        val decision = SleepPolicy.decide(
            pending.window,
            evidence,
            enabled = prefs.sleepNoticeEnabled,
            muted = bellsMuted(),
            exemptionGranted = BatteryOptimization.isIgnoringBatteryOptimizations(this)
        )

        // Deferral must not touch the flow: `pendingSleep` is also the re-evaluation trigger, so
        // consuming and restoring it here would oscillate null<->value and re-run the whole
        // evaluation block in a loop for the length of the mute. The only write on this path is the
        // one-off evidence freeze, which converges because the next pass finds it already measured.
        if (decision == SleepDecision.Hold) {
            if (pending.evidence == null) {
                pendingSleep.compareAndSet(pending, pending.copy(evidence = evidence))
            }
            return
        }

        // A terminal verdict: consume atomically, so a post can never double-fire and a newer sleep
        // that landed while we were deciding is not stolen by this pass.
        if (!pendingSleep.compareAndSet(pending, null)) return

        when (decision) {
            SleepDecision.Silent -> Unit
            SleepDecision.Dismiss -> {
                // The fix landed: the tally goes with the row it justified.
                sleepLedger = null
                notificationManager.clearSleepNotice()
            }
            is SleepDecision.Post ->
                sleepLedger = (sleepLedger ?: SleepLedger.EMPTY)
                    .plus(pending.window, decision.evidence)
            SleepDecision.Hold -> Unit // handled above
        }
    }

    /**
     * Takes the row down when its reason is gone — the exemption landed, or the user switched the
     * notice off — and empties the tally with it. Edge-detected on the transition rather than
     * polled: `clearSleepNotice` is a binder call and both causes are sticky, so checking the
     * condition every tick would fire forever. Checked here rather than in the sleep path because
     * neither cause ever produces another sleep window: an exempt app stops sleeping, and a
     * switched-off notice stops deciding.
     */
    private fun retractSleepNotice(prefs: UserPreferences) {
        val retracted = BatteryOptimization.isIgnoringBatteryOptimizations(this) ||
            !prefs.sleepNoticeEnabled
        if (lastNoticeRetracted == retracted) return
        lastNoticeRetracted = retracted
        if (!retracted) return
        sleepLedger = null
        notificationManager.clearSleepNotice()
    }

    /**
     * Writes (or refreshes) the night's row from the accumulated tally. Driven by the evaluation
     * rather than by a stall, because the row's moment is the user's, not the stall's: it appears on
     * the first tick after they look at the phone, carrying everything that happened until then.
     *
     * The tally is process-local on purpose. A restart loses the running count but not the row —
     * same id, so the next report replaces it instead of adding a second one — and persisting a
     * counter that only ever decorates one notification is not worth a disk write on this path.
     */
    private fun publishSleepNotice(live: LiveInputs, prefs: UserPreferences) {
        val ledger = sleepLedger ?: return        // Belt-and-braces against the ordering of the tick body: the retract block runs later in
        // this same lambda, so on the tick the user switches the notice off a ledger folded a
        // moment ago could still be sitting here. Never publish at a user who asked for silence.
        if (!prefs.sleepNoticeEnabled) return
        val now = live.now
        // Last night's row has retired: start a fresh tally so tonight is not summed into it.
        if (SleepPolicy.rowExpired(ledger, now)) {
            sleepLedger = null
            return
        }
        if (!SleepPolicy.noticeDue(ledger, now, live.screen.takeIf { it.on }?.sinceMillis)) return
        val s = Strings.get(prefs.language)
        notificationManager.postSleepNotice(
            s.sleepNoticeTitle,
            sleepNoticeBody(s, ledger),
            s.batteryAllowButton
        )
        sleepLedger = ledger.posted(now)
    }

private fun logAction(reason: DebugLogReason, label: String, detail: String? = null) {
    DebugLog.recordAction(reason, label, detail, System.currentTimeMillis())
}

private fun nextUpdateCheckMillis(from: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 16)
            set(Calendar.MINUTE, 20)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private suspend fun dailyUpdateCheckLoop() {
        while (true) {
            val now = System.currentTimeMillis()
            val next = nextUpdateCheckMillis(now)
            delay(next - now)
            runDailyUpdateCheck()
        }
    }

    private fun runDailyUpdateCheck() {
        scope.launch {
            val result = UpdateManager(applicationContext).check()
            if (result is UpdateState.Available) {
                val svcState = ServiceState(applicationContext)
                val userPrefs = UserPrefs(applicationContext)
                val lastNotified = svcState.lastNotifiedUpdateCode().first()
                if (result.info.versionCode > lastNotified) {
                    svcState.setLastNotifiedUpdateCode(result.info.versionCode.toLong())
                    val s = Strings.get(userPrefs.preferences.first().language)
                    notificationManager.postUpdateNotification(
                        s.notifUpdateTitle,
                        String.format(s.notifUpdateText, result.info.versionName)
                    )
                }
            }
            val manifestResult = ApiMonitor.checkManifest(applicationContext)
            if (manifestResult is ManifestResult.Changed) {
                ApiMonitor.record(
                    SystemEntry(
                        System.currentTimeMillis(),
                        SystemEntryKind.SDK_CHANGED,
                        "SHA256: ${manifestResult.oldHash} -> ${manifestResult.newHash}"
                    )
                )
            } else if (manifestResult is ManifestResult.Failed) {
                ApiMonitor.record(
                    SystemEntry(
                        System.currentTimeMillis(),
                        SystemEntryKind.SDK_CHECK_FAILED,
                        manifestResult.message
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        val before = MonitoringStatus.state.value
        MonitoringStatus.died(MonitoringStatus.Reason.DESTROYED)
        if (before !is MonitoringStatus.State.StoppedByUser && before !is MonitoringStatus.State.Dead) {
            MonitorLifecycle.recordNow(this, MonitorEvent.Kind.STOP_OS)
        }
        // The mute is deliberately NOT reset here: a destroy is not a mute clear, and the OS
        // recycles a foreground service freely. A timed mute lives in ServiceState (see
        // [restoreRaidMute]) precisely so the next instance keeps honouring it. The UI mirror is
        // reset because it is process-scoped and the process is going away.
        RaidMuteState.reset()
        debrisBuffer.abort()
        // Detach the GNSS/network listeners so the radio can sleep. The next service instance
        // re-subscribes from startMonitoring; start() is idempotent.
        LocationTracker.stop()
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        monitoringJob?.cancel()
        wakeLockManager.release()
        tally.reset()
        episodeTally.reset()
        scope.cancel()
        super.onDestroy()
    }
}
