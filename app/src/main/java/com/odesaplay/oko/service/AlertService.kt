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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
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
import com.odesaplay.oko.engine.isFastType
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.OfficialFrontier
import com.odesaplay.oko.engine.officialAnnouncementIsOnset
import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.LatchedEpisode
import com.odesaplay.oko.engine.EpisodeTransition
import com.odesaplay.oko.engine.RestoredResolution
import com.odesaplay.oko.engine.officialStateFor
import com.odesaplay.oko.Transliteration
import com.odesaplay.oko.ThreatType
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
import com.odesaplay.oko.NightZones
import com.odesaplay.oko.Strings
import com.odesaplay.oko.UserPrefs
import com.odesaplay.oko.engine.distanceFlat
import com.odesaplay.oko.isWithinNight
import com.odesaplay.oko.AlarmEpisodeTally
import com.odesaplay.oko.NeutralizedTally
import com.odesaplay.oko.engine.ThreatEngine
import com.odesaplay.oko.service.ServiceState
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.RaidMuteState
import com.odesaplay.oko.service.AudioAlarmDispatcher
import com.odesaplay.oko.service.FallingDebrisBuffer

class AlertService : Service() {

    companion object {
        const val ACTION_RETRY = AlertNotificationManager.ACTION_RETRY
        const val ACTION_IGNORE_RETRY = AlertNotificationManager.ACTION_IGNORE_RETRY
        const val ACTION_ALERT_OK = AlertNotificationManager.ACTION_ALERT_OK
        const val ACTION_MUTE_RAID = AlertNotificationManager.ACTION_MUTE_RAID
        const val ACTION_MUTE_10 = AlertNotificationManager.ACTION_MUTE_10
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
        private const val VIBRATION_STRONG = 4
        private const val VIBRATION_ZONE = 3

        fun start(context: Context) {
            MonitoringStatus.setRunning(true)
            try {
                ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
            } catch (e: Exception) {
                // A failed start must never look like working monitoring �?" leave the flag off
                // so the UI banner surfaces the dead state instead of going silent.
                MonitoringStatus.setRunning(false)
                throw e
            }
        }

        /**
         * Background-safe start for WorkManager/watchdog paths. Android 12+ forbids starting a
         * foreground service from the background, so a failure is expected there: swallow it
         * (never crash the worker) and post a tap-to-resume prompt instead. Returns whether the
         * service actually started.
         */
        fun startResilient(context: Context): Boolean = try {
            start(context)
            true
        } catch (_: Exception) {
            AlertNotificationManager(context.applicationContext).postMonitoringPaused()
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    /** True once the FGS was (re)started with the location type. Left false when a background
     *  start had to fall back (no location exemption), so the next foreground start re-promotes. */
    private var locationFgsActive = false
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
    @Volatile private var wasConnected = true
    private var offlineAlertJob: Job? = null
    private var offlineRestorePending = false
    private val screenOnFlow = MutableStateFlow(true)
    private var screenReceiver: BroadcastReceiver? = null

    private val notificationManager by lazy { AlertNotificationManager(applicationContext) }
    private val wakeLockManager by lazy { AlertWakeLockManager(applicationContext) }
    private val audioAlarmDispatcher by lazy { AudioAlarmDispatcher(applicationContext) }
    /** The all-clear is off the shade and must not be brought back: swiped by the user,
     *  superseded by a new episode, or retired by the TTL. */
    @Volatile private var allClearClosed = false
    /**
     * TTL timer for the all-clear notification. Non-null ONLY while a live post's timer is
     * pending: both [clearAllClearNotification] and the timer itself retire it, and
     * [scheduleAllClearExpiry] retires it before arming a new one. That single-owner rule is
     * what stops a stale timer from firing during a later episode and cancelling its all-clear.
     */
    @Volatile private var allClearExpiryJob: Job? = null
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
                    if (!bellsMuted()) audioAlarmDispatcher.dispatchSmallVibration()
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

    /** "Mute raid" bells-off state for the active raid (see [RaidMute]). */
    @Volatile private var raidMute: RaidMute = RaidMute.None

    /** Bells off (danger siren + all-clear chime). Notifications still post, silently. */
    private fun bellsMuted(): Boolean = raidMute.silent(System.currentTimeMillis())

    /** Single writer: keeps the service field and the UI mirror in lockstep. */
    private fun setRaidMute(value: RaidMute) {
        raidMute = value
        RaidMuteState.set(value)
    }

    private val tally by lazy { NeutralizedTally(applicationContext, scope) }
    private val episodeTally by lazy { AlarmEpisodeTally(applicationContext, scope) }
    @Volatile private var episodeActive = false
    @Volatile private var currentToken: String? = null

    private data class MonitorState(
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
        val fastVibrationLevel: Int,
        val slowVibrationLevel: Int,
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
        val digestPerType: Boolean
    ) {
        /** Derived summary of the two sub-channels — the master toggle. Mirrors the UI so the
         *  all-clear gate and the OFF log key on the same red||yellow fact the row shows. */
        val officialAlertsEnabled: Boolean
            get() = officialRedAlertsEnabled || officialYellowAlertsEnabled

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
                    Intent.ACTION_SCREEN_ON -> screenOnFlow.value = true
                    Intent.ACTION_SCREEN_OFF -> screenOnFlow.value = false
                }
            }
        }
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

        // An all-clear that survived a process death has no episode behind it any more, so its
        // TTL timer is gone and nothing would ever retire it. Drop it rather than leave a
        // permanent "all clear" in the shade.
        if (notificationManager.isAllClearNotificationActive()) {
            notificationManager.cancelNotification(NOTIF_ALLCLEAR)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MonitoringStatus.setRunning(true)
        // A background start (package-replaced/boot) may have had to drop the location type;
        // once we're started again in the foreground, re-promote so background fixes resume.
        if (!locationFgsActive) startForegroundCompat()
        when (intent?.action) {
            ACTION_RETRY -> {
                scope.launch {
                    AppSources.registry.retryNow()
                }
            }
            ACTION_IGNORE_RETRY -> {
                notifMuteUntilMs = System.currentTimeMillis() + IGNORE_RETRY_MUTE_MS
                notificationManager.cancelNotification(NOTIF_MILESTONE)
                notificationManager.cancelNotification(NOTIF_OFFLINE_CRITICAL)
                AppSources.registry.annotateConnectionLog(
                    ConnEventKind.IGNORE_MUTED,
                    detail = "${IGNORE_RETRY_MUTE_MS / 60_000} min"
                )
            }
            ACTION_ALERT_OK -> notificationManager.cancelNotification(NOTIF_ALERT)
            ACTION_MUTE_RAID -> {
                setRaidMute(raidMute.untilClear())
                audioAlarmDispatcher.stopActiveAlert()
                notificationManager.cancelNotification(NOTIF_ALERT)
            }
            ACTION_MUTE_10 -> {
                setRaidMute(raidMute.forDuration(System.currentTimeMillis(), MUTE_10_MS))
                audioAlarmDispatcher.stopActiveAlert()
                notificationManager.cancelNotification(NOTIF_ALERT)
            }
            ACTION_CLEAR_MUTE -> setRaidMute(RaidMute.None)
            NeutralizedTally.ACTION_NEUTRALIZED_DISMISS -> tally.reset()
            AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS -> episodeTally.reset()
            AlertNotificationManager.ACTION_ALLCLEAR_DISMISSED -> {
                allClearClosed = true
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
        // Even the base type was refused — surface the dead state like startResilient intends.
        MonitoringStatus.setRunning(false)
        AlertNotificationManager(applicationContext).postMonitoringPaused()
        stopSelf()
    }

    /** Attempts [ServiceCompat.startForeground]; returns false when the platform refuses the
     *  start. A background start denies the location type via [SecurityException], and denies
     *  the whole start on Android 12+ via [android.app.ForegroundServiceStartNotAllowedException]
     *  (an [IllegalStateException]); both mean "do not crash, degrade instead". */
    private fun startForegroundTyped(type: Int, notif: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_MONITOR, notif, type)
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
                lastAnnouncedRow = "$_annToken|$_annCity"
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

            val nowFlow = MutableStateFlow(System.currentTimeMillis())
            launch {
                while (true) {
                    val fast = screenOnFlow.value
                    delay(if (fast) MONITOR_TICK_MS else MONITOR_TICK_IDLE_MS)
                    nowFlow.value = System.currentTimeMillis()
                }
            }

            data class LiveInputs(
                val rawThreats: Map<String, NormalizedThreat>,
                val alerts: List<OblastAlert>,
                val gps: LatLng?,
                val now: Long,
                val alertsReady: Boolean
            )

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
                registry.alertsReady
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                LiveInputs(
                    rawThreats = values[0] as Map<String, NormalizedThreat>,
                    alerts = values[1] as List<OblastAlert>,
                    gps = values[2] as LatLng?,
                    now = values[3] as Long,
                    alertsReady = values[6] as Boolean
                )
            }

            combine(
                liveFlow,
                prefs.preferences
            ) { live, p ->
                val (rawThreats, alerts, gps, now, alertsReady) = live
                val nowMin = nowMinuteOfDay()
                val nightActive = p.nightEnabled && isWithinNight(nowMin, p.nightStartMin, p.nightEndMin)
                val dayParams = ZoneParams(p.slowRedKm, p.slowYellowKm, p.fastRedMin, p.fastYellowMin)
                val nightZones = NightZones(
                    slowRedKm = p.nightSlowRedKm,
                    slowYellowKm = p.nightSlowYellowKm,
                    fastRedMin = p.nightFastRedMin,
                    fastYellowMin = p.nightFastYellowMin,
                    slowRedArmed = p.nightSlowRedArmed,
                    slowYellowArmed = p.nightSlowYellowArmed,
                    fastRedArmed = p.nightFastRedArmed,
                    fastYellowArmed = p.nightFastYellowArmed
                )
                val params = if (nightActive && p.nightUseCustomZones) {
                    effectiveZoneParams(dayParams, nightZones, true, nightActive)
                } else {
                    dayParams
                }
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
                val effectiveCityScope = p.officialAlertCityScope || (nightActive && p.nightOfficialAlertCityScope)
                val official = alerts.officialStateFor(focusToken, focusCityUa, effectiveCityScope)
                val officialRaw = alerts.officialStateFor(focusToken, null, false)
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

                val fastVib = VIBRATION_ZONE
                val slowVib = VIBRATION_ZONE

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
                    fastVibrationLevel = fastVib,
                    slowVibrationLevel = slowVib,
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
                    digestPerType = p.digestPerType
                ) to now
            }.collect { (state, now) ->
                handleState(state, now, engine)
            }
        }
    }

    private suspend fun handleState(state: MonitorState, now: Long, engine: ThreatEngine) {
        val s = Strings.get(state.lang)

        // One observation per tick; writes only on transition, so the 1s/30s loop is free.
        GpsLog.observe(
            state.gpsIssue,
            now,
            LocationTracker.lastAccuracyM.value?.toInt(),
            state.focusLocation
        )

        if (state.lang != lastChannelLang) {
            lastChannelLang = state.lang
            notificationManager.updateChannels(s)
        }

        // Focus moved (pinned <-> GPS, or a new pinned city): the latched official episode
        // belongs to the old region, so it must not keep announcing/clearing for it. Drop it
        // silently; the new focus can start a fresh episode on the next tick.
        val prevFocusToken = lastFocusTokenSeen
        if (state.focusToken != null) lastFocusTokenSeen = state.focusToken
        if (prevFocusToken != null && state.focusToken != null && state.focusToken != prevFocusToken) {
            if (lastAnnouncedFrontier != null) {
                clearOfficialAnnounced()
                lastAnnouncedFrontier = null
                lastAnnouncedRow = null
                latchedConfirmedLive = false
            }
            lastLoggedRawFrontier = null
            allClearClosed = false
        }

        val latchedEarly = LatchedEpisode.parse(lastAnnouncedRow)
        // Unknown feed holds the episode: until the first real snapshot arrives the
        // latch counts as alive, so no all-clear, no notif teardown, no tally close.
        val latchedAliveEarly = latchedEarly?.resolve(state.alertsReady, state.alerts) == EpisodeTransition.STAY
        if (latchedEarly != null && state.alertsReady && latchedEarly.isRawActive(state.alerts)) {
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
            ignoreLabel = if (isOfflineNow && System.currentTimeMillis() >= notifMuteUntilMs) s.offlineIgnoreAction else null,
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
            System.currentTimeMillis() >= notifMuteUntilMs &&
            offlineMinutes >= criticalThresholdMin && criticalFiredForEpisode != episode
        ) {
            criticalFiredForEpisode = episode
            if (state.criticalOfflineBypassSilent) audioAlarmDispatcher.dispatchCriticalOffline(true)
            notificationManager.postCriticalOfflineNotification(
                if (criticalThresholdMin == CRITICAL_OFFLINE_ALARM_MIN) s.offlineCriticalAlarmTitle
                else s.offlineCriticalTitle,
                String.format(s.offlineCriticalFormat, criticalThresholdMin),
                s.offlineRetryAction,
                s.offlineIgnoreAction
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
        val effAlert = if (latchedAlive) state.alerts.officialStateFor(latched!!.token, null, false).alert else null
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
            val red = if (fast) state.fastRedArmed else state.slowRedArmed
            val yellow = if (fast) state.fastYellowArmed else state.slowYellowArmed
            return when (spatial) {
                ThreatZone.INNER -> if (red) ThreatZone.INNER else if (yellow) ThreatZone.OUTER else null
                ThreatZone.OUTER -> if (yellow) ThreatZone.OUTER else null
            }
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
        val verdicts = notifyPlugin.tick(pluginInputs, notifyPrefs, now)
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
            val vibration: Int,
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

        /** The persisted announcement row for an episode: the legacy `LEVEL|token|since|city`
         *  shape, so [LatchedEpisode.parse] and older versions keep reading it. */
        fun announcedRow(state: MonitorState): String =
            "${state.focusOblastLevel}|${state.focusToken}|" +
                "${state.focusOblastAlertSince}|${state.focusBannerCity}"

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
                    vibration = t?.let { if (isFastType(it.type.toThreatType(), typeCatalog)) state.fastVibrationLevel else state.slowVibrationLevel } ?: VIBRATION_STRONG,
                    isOnset = verdicts[id]?.kind == VerdictKind.SOUND,
                    zone = zone, level = if (zone == ThreatZone.INNER) "red" else "yellow"
                )
            }
            // Official is official: one branch for any level. Only the toggle gate,
            // copy and sound differ per level — the episode latch is shared.
            // Region-latched: while the announced oblast is still raw-active, the
            // effective official is the latched one, not the current focus (pinned -> follow-me).
            if (effLevel != AlertLevel.NONE) {
                val announcedFrontier = announcedFrontier(state)
                val announced = if (effLevel == AlertLevel.RED) state.officialRedAlertsEnabled
                else state.officialYellowAlertsEnabled
                if (announced) {
                    val onset = isNewEpisode(state)
                    if (effLevel == AlertLevel.RED) {
                        val reasonThreat = effReasonId?.let { all[it] }
                        return Primary(
                            identity = "red|${announcedFrontier!!.token}|$effCity|$effReasonId",
                            title = String.format(s.alertBannerFormat, effCity),
                            body = (effReason ?: effRegion ?: state.focusRegion) + etaSuffix(reasonThreat, state),
                            revealThreat = reasonThreat,
                            silent = !onset,
                            vibration = reasonThreat?.let { if (isFastType(it.type.toThreatType(), typeCatalog)) state.fastVibrationLevel else state.slowVibrationLevel } ?: VIBRATION_STRONG,
                            isOnset = onset,
                            zone = null, level = effLevel.name.lowercase()
                        )
                    }
                    return Primary(
                        identity = "yellow|${announcedFrontier!!.token}|$effCity",
                        title = String.format(s.alertYellowBannerFormat, effCity),
                        body = effReason ?: effRegion ?: state.focusRegion,
                        revealThreat = null, silent = !onset, vibration = VIBRATION_STRONG,
                        isOnset = onset,
                        zone = null, level = "yellow"
                    )
                }
            }
            return null
        }

        /** Reconcile official-episode side-effects for ANY level: wakeLock, DebugLog ON
         *  (always — the log sees every official episode even when its notifs are off
         *  or a zone alert wins the tick), persistence, all-clear. */
        fun reconcileEpisode(primary: Primary?, state: MonitorState, all: Map<String, NormalizedThreat>) {
            val frontier = announcedFrontier(state)
            val raw = rawFrontier(state)
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
                val vibration = reasonThreat?.let {
                    if (isFastType(it.type.toThreatType(), typeCatalog)) state.fastVibrationLevel else state.slowVibrationLevel
                } ?: VIBRATION_STRONG
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
                        sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                            notified = false, reason = DebugLogReason.COALESCED,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                    )
                    audible -> {
                        wakeLockManager.acquireForAlert()
                        DebugLog.recordOfficial(
                            DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                            sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                            notified = true, reason = DebugLogReason.FIRED,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                        )
                        persistOfficialAnnounced(state)
                    }
                    else -> DebugLog.recordOfficial(
                        DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                        sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                            notified = false, reason = DebugLogReason.TOGGLE_OFF,
                            threatId = if (attachCause) reasonThreat?.id else null,
                            threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = null,
                            level = state.focusOblastLevel,
                            now = System.currentTimeMillis()
                    )
                }
                lastAnnouncedFrontier = frontier!!.rememberSounded(lastAnnouncedFrontier)
                lastAnnouncedRow = announcedRow(state)
                lastLoggedRawFrontier = raw
            } else if (raw != null && raw.token != lastLoggedRawFrontier?.token) {
                // Raw official alert outside the notif scope (city-scope on, raion not covering
                // the focus city, or a muted/sleep window): record it silently so the Logs tab
                // still carries EVERY official alert, even when no notif was posted. Identity
                // alone decides — a level flip here is the same episode, hence `token !=`.
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride, vibrationLevel = VIBRATION_STRONG,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa,
                    distanceKm = null, level = state.focusOblastRawLevel,
                    now = System.currentTimeMillis()
                )
                lastLoggedRawFrontier = raw
            }
            val suppressAllClear = sounding && allClearSameArea(state)
            if (!suppressAllClear && latched != null && !latchedAlive && state.officialAlertsEnabled) {
                if (latched.resolveRestored(latchedConfirmedLive, EpisodeTransition.ENDED) == RestoredResolution.EXPIRE_SILENTLY) {
                    // Resurrected latch never observed live in this lifetime: the episode
                    // ended while we were dead. Drop it without notification, chime or log.
                    clearOfficialAnnounced()
                    lastAnnouncedFrontier = null
                lastAnnouncedRow = null
                    latchedConfirmedLive = false
                    return@reconcileEpisode
                }
                if (alertable.isEmpty()) cancelAlert()
                // A second post for an all-clear that is already in the shade is a contract
                // violation ("one clear per episode") and the reason a TTL used to look broken.
                // Leave a lifecycle row for it — an INFO row, so the episode's single
                // OFFICIAL_OFF record is untouched.
                val reposted = notificationManager.isAllClearNotificationActive()
                scheduleAllClearExpiry(state.autoDismissAllClear)
                val allClearCity = latched.city
                lastCleanAllClearCity = allClearCity
                if (reposted) DebugLog.recordAllClearReposted(allClearCity, System.currentTimeMillis())
                lastChannelLang = state.lang
                val s = Strings.get(state.lang)
                val delay = state.fallingDebrisDelaySec.coerceIn(0, 600)
                lastEpisodeReplay = episodeTally.snapshot()
                val muted = bellsMuted()
                if (delay > 0) {
                    postAllClear(s, state.focusBannerCity, debrisSeconds = delay, silent = true, muted = muted, replay = lastEpisodeReplay)
                    debrisBuffer.start(durationSeconds = delay)
                } else {
                    postAllClear(s, state.focusBannerCity, debrisSeconds = 0, silent = true, muted = muted, replay = lastEpisodeReplay)
                    if (!muted) audioAlarmDispatcher.dispatchAllClearChime()
                }
                setRaidMute(raidMute.cleared())
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride, vibrationLevel = null,
                    notified = true, reason = DebugLogReason.FIRED,
                    threatId = null, threatType = null,
                    locality = effRegion ?: allClearCity, distanceKm = null,
                    now = System.currentTimeMillis()
                )
                lastAnnouncedFrontier = null
                lastAnnouncedRow = null
                latchedConfirmedLive = false
                clearOfficialAnnounced()
                scopedOffLogged = true
                lastLoggedRawFrontier = null
            }
            if (!scopedOffLogged && raw == null && lastLoggedRawFrontier != null) {
                // Raw alert ended outside the notif scope: close its audit row silently.
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride, vibrationLevel = null,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = null, distanceKm = null,
                    now = System.currentTimeMillis()
                )
                lastLoggedRawFrontier = null
            }
            val shownOfficial = lastShownId?.startsWith("red|") == true || lastShownId?.startsWith("yellow|") == true
            if (shownOfficial && primary == null &&
                latchedAlive && state.officialAlertsEnabled
            ) {
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride, vibrationLevel = null,
                    notified = false, reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null, threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa, distanceKm = null,
                    now = System.currentTimeMillis()
                )
            }
        }

        /** Reconcile NOTIF_ALERT post/cancel based on primary vs lastShownId. */
        suspend fun reconcileNotif(primary: Primary?, state: MonitorState) {
            val s = Strings.get(state.lang)
            val actions = AlertActions(
                ok = s.alertActionOk,
                muteRaid = s.alertActionMuteRaid,
                mute10 = s.alertActionMute10
            )
            if (primary?.identity != lastShownId) {
                when {
                    primary == null -> {
                        // Don't tear down a live official notification on a flicker tick:
                        // any official id stays up while the latched oblast is still raw-active.
                        val shownOfficialStillLive =
                            (lastShownId?.startsWith("red|") == true || lastShownId?.startsWith("yellow|") == true) &&
                                latchedAlive && state.officialAlertsEnabled
                        if (!shownOfficialStillLive) {
                            cancelAlert()
                        }
                    }
                    primary.isOnset -> {
                        wakeLockManager.acquireForAlert()
                        val muted = bellsMuted()
                        if (!muted) {
                            audioAlarmDispatcher.dispatchDangerAlarm(
                                isRed = (primary.level == "red"),
                                overrideSilence = (state.zoneSirenOverride ?: state.officialSirenOverride)
                            )
                        }
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            state.zoneSirenOverride ?: state.officialSirenOverride,
                            revealThreat = primary.revealThreat, vibrationLevel = primary.vibration,
                            muted = muted, actions = actions)
                        if (primary.zone != null) {
                            primary.revealThreat?.let { t ->
                                DebugLog.recordZoneFired(
                                    threatId = t.id,
                                    threatType = t.type.toThreatType(),
                                    tier = primary.zone,
                                    night = state.nightActive,
                                    sirenOverride = state.zoneSirenOverride,
                                    vibrationLevel = primary.vibration,
                                    distanceKm = distanceFromFocusKm(t, state),
                                    locality = t.locality ?: t.district ?: t.region,
                                    now = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                    alertNotificationShowing() -> {
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            state.zoneSirenOverride ?: state.officialSirenOverride,
                            revealThreat = primary.revealThreat, silent = true,
                            vibrationLevel = primary.vibration,
                            muted = bellsMuted(), actions = actions)
                    }
                    else -> { /* dismissed, don't re-raise */ }
                }
                lastShownId = primary?.identity
            }
        }

        // Invoke the reconcile pipeline. Verdicts (plugin-owned) already decided
        // sound/silent/suppress above; below only executes. Official paths untouched.
        val primary = buildPrimary(state, all)
        reconcileEpisode(primary, state, all)
        // cold start re-post suppression: never (see OFFICIAL_ALERT vs all-clear below).
        reconcileNotif(primary, state)
        // Morale-only episode window: region-latched, same gate as the all-clear.
        // An unconfirmed (never observed live) window closes silently — no summary
        // for an episode this lifetime never owned.
        val alarmNowActive = effLevel != AlertLevel.NONE
        if (alarmNowActive && !episodeActive) {
            episodeActive = true
            episodeTally.begin(effCity, now)
        } else if (!alarmNowActive && episodeActive) {
            episodeActive = false
            if (latchedConfirmedLive) {
                episodeTally.finish(state.focusBannerCity, state.lang, state.officialAlertsEnabled, now)
                lastEpisodeReplay = episodeTally.snapshot()
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
                    night = state.nightActive,
                    sirenOverride = state.zoneSirenOverride,
                    fastVibrationLevel = state.fastVibrationLevel,
                    slowVibrationLevel = state.slowVibrationLevel,
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
        if (System.currentTimeMillis() < notifMuteUntilMs) return
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
                s.offlineStatusTitle, offlineLiveBody(s, ageMin), s.offlineRetryAction,
                s.offlineIgnoreAction
            )
        }
    }

    private fun offlineLiveBody(s: Strings.StringSet, minutes: Int): String {
        return String.format(s.offlineLiveFormat, minutes)
    }

    private suspend fun postAlert(
        zone: ThreatZone?,
        level: String,
        title: String,
        body: String,
        sirenOverride: Boolean,
        revealThreat: NormalizedThreat? = null,
        silent: Boolean = false,
        vibrationLevel: Int = 3,
        muted: Boolean = false,
        actions: AlertActions? = null
    ) {
        notificationManager.postAlertNotification(
            zone = zone ?: ThreatZone.INNER,
            title = title,
            body = body,
            sirenOverride = sirenOverride,
            revealThreat = revealThreat,
            vibrationLevel = vibrationLevel,
            silent = silent,
            muted = muted,
            actions = actions
        )
    }

    private fun cancelAlert() {
        audioAlarmDispatcher.stopLoopingAlert()
        notificationManager.cancelNotification(NOTIF_ALERT)
    }

    private fun alertNotificationShowing(): Boolean =
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.activeNotifications.any { it.id == NOTIF_ALERT }
        }.getOrDefault(false)

    private fun persistOfficialAnnounced(state: MonitorState) {
        scope.launch {
            ServiceState(applicationContext).setOfficialAnnounced(
                state.focusToken, state.focusOblastAlertSince, state.officialReasonThreatId,
                state.focusBannerCity
            )
        }
    }

    private fun clearOfficialAnnounced() {
        scope.launch {
            ServiceState(applicationContext).setOfficialAnnounced(null, null, null)
        }
    }

    private fun postAllClear(s: Strings.StringSet, city: String, debrisSeconds: Int = 0, silent: Boolean = false, muted: Boolean = false, replay: List<FlourishRecord> = emptyList()) {
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
        allClearExpiryJob?.cancel()
        allClearExpiryJob = null
        debrisBuffer.abort()
        allClearClosed = true
        notificationManager.cancelNotification(NOTIF_ALLCLEAR)
    }

    /**
     * Arms the all-clear TTL, at most once per live notification. Must be called at every
     * all-clear post, but a repeated post (this runs per tick while the dead latch is still
     * visible) must NOT restart the clock: the TTL is defined to run from the first post, and
     * re-arming would leave the notification in the shade indefinitely. Returns true when it
     * armed a new timer, false when one was already pends for the live notification.
     */
    private fun scheduleAllClearExpiry(autoDismiss: Boolean): Boolean {
        allClearClosed = false
        if (allClearExpiryJob != null) return false
        if (!autoDismiss) return false
        allClearExpiryJob = scope.launch {
            delay(ALL_CLEAR_TTL_MS)
            allClearExpiryJob = null
            if (allClearClosed) return@launch
            clearAllClearNotification()
            DebugLog.recordAllClearExpired(lastCleanAllClearCity, System.currentTimeMillis())
        }
        return true
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
        MonitoringStatus.setRunning(false)
        setRaidMute(RaidMute.None)
        audioAlarmDispatcher.release()
        debrisBuffer.abort()
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

internal fun nowMinuteOfDay(): Int {
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

internal fun vibrationPattern(level: Int): LongArray = when (level) {
    0 -> longArrayOf(0)
    1 -> longArrayOf(0, 120, 60, 120)
    2 -> longArrayOf(0, 200, 100, 200)
    4 -> longArrayOf(0, 600, 100, 600, 100, 600)
    else -> longArrayOf(0, 400, 120, 400)
}
