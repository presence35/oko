package ua.ukrainedrones

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.resolveFocus
import ua.ukrainedrones.connection.Monotonic
import ua.ukrainedrones.connection.ConnEventKind
import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.plugins.SourceEventKind
import ua.ukrainedrones.engine.isFastType
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.Transliteration
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.alertRegionName
import ua.ukrainedrones.engine.officialAlertActiveFor
import ua.ukrainedrones.engine.threatBody
import ua.ukrainedrones.UpdateInfo
import ua.ukrainedrones.UpdateManager
import ua.ukrainedrones.UpdateState
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.ConnectionLog
import ua.ukrainedrones.connection.ConnectionSupervisor
import ua.ukrainedrones.DebugLog
import ua.ukrainedrones.DebugLogContext
import ua.ukrainedrones.DebugLogKind
import ua.ukrainedrones.DebugLogReason
import ua.ukrainedrones.data.ManifestResult
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.data.TelegramNotifier
import ua.ukrainedrones.NightZones
import ua.ukrainedrones.Strings
import ua.ukrainedrones.UserPrefs
import ua.ukrainedrones.engine.distanceFlat
import ua.ukrainedrones.isWithinNight
import ua.ukrainedrones.threatAlertFlow
import ua.ukrainedrones.NeutralizedTally
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.service.ServiceState
import ua.ukrainedrones.service.MonitoringStatus
import ua.ukrainedrones.connection.isConnected
import ua.ukrainedrones.connection.isDegraded
import ua.ukrainedrones.connection.offlineSinceOrNull

class AlertService : Service() {

    companion object {
        const val ACTION_RETRY = AlertNotificationManager.ACTION_RETRY
        const val ACTION_IGNORE_RETRY = AlertNotificationManager.ACTION_IGNORE_RETRY
        const val EXTRA_REVEAL_ID = AlertNotificationManager.EXTRA_REVEAL_ID
        const val EXTRA_REVEAL_LAT = AlertNotificationManager.EXTRA_REVEAL_LAT
        const val EXTRA_REVEAL_LON = AlertNotificationManager.EXTRA_REVEAL_LON
        const val EXTRA_SHOW_UPDATE = AlertNotificationManager.EXTRA_SHOW_UPDATE
        const val EXTRA_SHOW_MAP = AlertNotificationManager.EXTRA_SHOW_MAP

        const val CHANNEL_MONITOR = AlertNotificationManager.CHANNEL_MONITOR
        const val CHANNEL_ALERTS = AlertNotificationManager.CHANNEL_ALERTS
        const val CHANNEL_ALERTS_OUTER = AlertNotificationManager.CHANNEL_ALERTS_OUTER
        const val CHANNEL_ALLCLEAR = AlertNotificationManager.CHANNEL_ALLCLEAR
        const val CHANNEL_ALERTS_ALARM = AlertNotificationManager.CHANNEL_ALERTS_ALARM
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

        const val CRITICAL_OFFLINE_MIN = 5
        private const val ALL_CLEAR_GRACE_MS = 0L
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
                // A failed start must never look like working monitoring — leave the flag off
                // so the UI banner surfaces the dead state instead of going silent.
                MonitoringStatus.setRunning(false)
                throw e
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var wasFocusAlertActive = false
    private var officialRegionToken: String? = null
    private var currentReasonThreatId: String? = null
    private var officialAnnouncedToken: String? = null
    private var officialAnnouncedSince: String? = null
    private var officialAnnouncedReasonId: String? = null
    private var officialAnnouncedCity: String? = null
    private var knownZones: Map<String, ThreatZone> = emptyMap()
    private var debugOfficialActive = false
    private var lastChannelLang: AppLanguage? = null
    private var emptySince: Long? = null

    private var lastMonitorTitle: String? = null
    private var lastMonitorText: String? = null
    private var lastMonitorRetry: String? = null
    private var lastMonitorProgressMax: Int? = null
    private var lastMonitorProgressNow: Int? = null
    private var lastMonitorIgnore: String? = null
    private var lastMonitorRed: Boolean = false

    private var hasShownGpsFallbackToast = false
    @Volatile private var wasConnected = true
    private var offlineAlertJob: Job? = null
    private var offlineRestorePending = false
    private val screenOnFlow = MutableStateFlow(true)
    private var screenReceiver: BroadcastReceiver? = null

    private val notificationManager by lazy { AlertNotificationManager(applicationContext) }
    private val wakeLockManager by lazy { AlertWakeLockManager(applicationContext) }

    private var hasActiveThreats = false
    private var isOutage = false
    private var lastSweepAtMs = 0L

    private var notif3minShown = false
    private var notif6minShown = false
    private var notif10minShown = false
    private var notif20minShown = false
    private var notifCriticalShown = false

    private val tally by lazy { NeutralizedTally(applicationContext, scope) }
    @Volatile private var currentToken: String? = null

    private data class MonitorState(
        val focusOblastAlertActive: Boolean,
        val focusOblastAlertRaw: Boolean,
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
        val officialAlertsEnabled: Boolean,
        val zoneSirenOverride: Boolean,
        val officialSirenOverride: Boolean,
        val connectionState: ConnectionState,
        val degraded: Boolean = false,
        val threats: Map<String, NormalizedThreat>,
        val alerts: List<OblastAlert>,
        val criticalOfflineOverride: Boolean,
        val fastVibrationLevel: Int,
        val slowVibrationLevel: Int,
        val focusLocation: LatLng?,
        val gpsFixMissing: Boolean = false,
        val nightActive: Boolean,
        val enabled: Set<ThreatType>
    )

    private data class AlertConfig(
        val slowRedArmed: Boolean,
        val slowYellowArmed: Boolean,
        val fastRedArmed: Boolean,
        val fastYellowArmed: Boolean,
        val officialAlertsEnabled: Boolean,
        val officialAlertCityScope: Boolean,
        val sirenOverride: Boolean,
        val followMe: Boolean,
        val criticalOfflineOverride: Boolean
    )

    private data class TailPrefs(
        val enabled: Set<ThreatType>,
        val lang: AppLanguage,
        val pinned: String?
    )

    private data class NightWindow(
        val enabled: Boolean,
        val startMin: Int,
        val endMin: Int,
        val useCustomZones: Boolean
    )

    private data class NightSettings(
        val window: NightWindow,
        val zones: NightZones,
        val zoneSirenOverride: Boolean,
        val officialSirenOverride: Boolean
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannels()
        AppPluginHolder.init(applicationContext)

        scope.launch {
            ConnectionLog.attach(applicationContext)
            DebugLog.attach(applicationContext)
            ApiMonitor.attach(applicationContext)
            ConnectionLog.awaitAttached()
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
                    TelegramNotifier.sendSdkChanged(manifestResult.oldHash, manifestResult.newHash)
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

            val client = ConnectionHolder.getClient(applicationContext)
            val sup = ConnectionHolder.getSupervisor(applicationContext)
            val recStart = ServiceState(applicationContext).reconnectStartMillis().first()
            val ignoreUntil = ServiceState(applicationContext).ignoreRetryUntil().first()
            client.start(savedReconnectStartMs = recStart, savedIgnoreUntilMs = ignoreUntil)
            sup.start()

            launch {
                AppPluginHolder.registry.sourceEvents.collect { ev ->
                    when (ev.kind) {
                        SourceEventKind.TOGGLED_ON -> sup.recordEvent(ConnEventKind.SOURCE_TOGGLED, detail = "on:${ev.sourceId}")
                        SourceEventKind.TOGGLED_OFF -> sup.recordEvent(ConnEventKind.SOURCE_TOGGLED, detail = "off:${ev.sourceId}")
                        SourceEventKind.TAKEOVER -> {
                            sup.setActiveSource(ev.sourceId)
                            ConnectionLog.setPendingSource(ev.sourceId)
                            sup.recordEvent(ConnEventKind.FALLBACK_ACTIVE, detail = ev.sourceId)
                        }
                        SourceEventKind.RESTORED -> {
                            sup.setActiveSource(null)
                            ConnectionLog.setPendingSource(null)
                            sup.recordEvent(ConnEventKind.FALLBACK_RESTORED)
                        }
                    }
                }
            }

            launch {
                client.connectionState.collect { cs ->
                    when (cs) {
                        is ConnectionState.Offline -> ServiceState(applicationContext).setReconnectStartMillis(cs.reconnectStartMillis)
                        is ConnectionState.Connected -> ServiceState(applicationContext).setReconnectStartMillis(0L)
                        else -> {}
                    }
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

        startForegroundCompat()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MonitoringStatus.setRunning(true)
        when (intent?.action) {
            ACTION_RETRY -> {
                scope.launch {
                    val client = ConnectionHolder.getClient(applicationContext)
                    client.retryNow()
                }
            }
            ACTION_IGNORE_RETRY -> {
                scope.launch {
                    val client = ConnectionHolder.getClient(applicationContext)
                    client.pauseFor(30)
                }
            }
            NeutralizedTally.ACTION_NEUTRALIZED_DISMISS -> tally.reset()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val s = Strings.get(AppLanguage.EN)
        val notif = notificationManager.buildMonitorNotification(s.notifOngoingTitle, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(this, NOTIF_MONITOR, notif, fgsType)
        } else {
            startForeground(NOTIF_MONITOR, notif)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startMonitoring() {
        val prefs = UserPrefs(applicationContext)
        val svcState = ServiceState(applicationContext)
        LocationTracker.start(applicationContext)

        scope.launch {
            dailyUpdateCheckLoop()
        }

        scope.launch {
            prefs.neutralizedTallyEnabled()
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (!enabled) emptyFlow() else ConnectionHolder.getClient(applicationContext).removedThreats
                }
                .collect { removed ->
                    if (!prefs.neutralizedTallyAllUkraine().first()) {
                        val token = currentToken ?: return@collect
                        if (!inOblast(removed.region, removed.district, removed.locality, token)) return@collect
                    }
                    tally.onResolved(removed, prefs.language().first())
                }
        }

        monitoringJob = scope.launch {
            officialAnnouncedToken = svcState.officialAnnouncedToken().first().ifBlank { null }
            officialAnnouncedSince = svcState.officialAnnouncedSince().first().ifBlank { null }
            officialAnnouncedReasonId = svcState.officialAnnouncedReasonId().first().ifBlank { null }
            officialAnnouncedCity = svcState.officialAnnouncedCity().first().ifBlank { null }

            // Restore active zone alerts across service restarts (Check 7 fix)
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
                    knownZones = restored
                }
            }

            if (svcState.offlinePendingSince().first() > 0) {
                wasConnected = false
                offlineRestorePending = true
            }

            val nowFlow = MutableStateFlow(System.currentTimeMillis())
            launch {
                while (true) {
                    val fast = screenOnFlow.value || hasActiveThreats || isOutage
                    delay(if (fast) MONITOR_TICK_MS else MONITOR_TICK_IDLE_MS)
                    nowFlow.value = System.currentTimeMillis()
                }
            }

            data class LiveInputs(
                val cs: ConnectionState,
                val rawThreats: Map<String, NormalizedThreat>,
                val alerts: List<OblastAlert>,
                val gps: LatLng?,
                val now: Long
            )

            val client = ConnectionHolder.getClient(applicationContext)
            val registry = AppPluginHolder.registry
            val engine = ThreatEngine(registry.typeCatalog.value)
val mappedThreats = registry.allThreats.map { list ->
                list.associate { it.id to it }
            }
            val liveFlow = combine(
                client.connectionState,
                mappedThreats,
                registry.allAlerts,
                LocationTracker.location,
                nowFlow
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                LiveInputs(
                    cs = values[0] as ConnectionState,
                    rawThreats = values[1] as Map<String, NormalizedThreat>,
                    alerts = values[2] as List<OblastAlert>,
                    gps = values[3] as LatLng?,
                    now = values[4] as Long
                )
            }

            combine(
                liveFlow,
                combine(
                    prefs.slowRedKm(), prefs.slowYellowKm(), prefs.fastRedMin(), prefs.fastYellowMin()
                ) { slowRed, slowYellow, fastRed, fastYellow ->
                    ZoneParams(slowRed, slowYellow, fastRed, fastYellow)
                },
                combine(
                    prefs.slowRedZoneArmed(),
                    prefs.slowYellowZoneArmed(),
                    prefs.fastRedZoneArmed(),
                    prefs.fastYellowZoneArmed(),
                    prefs.officialAlertsEnabled(),
                    prefs.officialAlertCityScope(),
                    prefs.sirenOverride(),
                    prefs.followMe(),
                    prefs.criticalOfflineOverride()
                ) { flags: Array<Boolean> ->
                    AlertConfig(
                        flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6], flags[7], flags[8]
                    )
                },
                combine(
                    threatAlertFlow(prefs),
                    prefs.language(),
                    prefs.pinnedCity()
                ) { enabled, lang, pinned ->
                    TailPrefs(enabled, lang, pinned)
                },
                combine(
                    combine(
                        prefs.nightEnabled(), prefs.nightStartMin(), prefs.nightEndMin(),
                        prefs.nightUseCustomZones()
                    ) { enabled, start, end, use ->
                        NightWindow(enabled, start, end, use)
                    },
                    combine(
                        combine(
                            prefs.nightSlowRedKm(), prefs.nightSlowYellowKm(), prefs.nightFastRedMin(),
                            prefs.nightFastYellowMin()
                        ) { sr, sy, fr, fy ->
                            NightZones(sr, sy, fr, fy, slowRedArmed = true, slowYellowArmed = true, fastRedArmed = true, fastYellowArmed = true)
                        },
                        combine(
                            prefs.nightSlowRedZoneArmed(), prefs.nightSlowYellowZoneArmed(),
                            prefs.nightFastRedZoneArmed(), prefs.nightFastYellowZoneArmed()
                        ) { flags: Array<Boolean> ->
                            flags
                        }
                    ) { zones, flags ->
                        zones.copy(
                            slowRedArmed = flags[0],
                            slowYellowArmed = flags[1],
                            fastRedArmed = flags[2],
                            fastYellowArmed = flags[3]
                        )
                    },
                    combine(
                        prefs.nightZoneSirenOverride(), prefs.nightOfficialSirenOverride()
                    ) { zoneOv, officialOv -> zoneOv to officialOv }
                ) { window, zones, ov ->
                    NightSettings(window, zones, ov.first, ov.second)
                }
            ) { live, dayParams, cfg, tail, night ->
                val (cs, rawThreats, alerts, gps, now) = live
                val nowMin = nowMinuteOfDay()
                val nightActive = night.window.enabled && isWithinNight(nowMin, night.window.startMin, night.window.endMin)
                val params = if (nightActive && night.window.useCustomZones) {
                    effectiveZoneParams(dayParams, night.zones, true, nightActive)
                } else {
                    dayParams
                }
                val zoneSirenOverride = if (nightActive) night.zoneSirenOverride else cfg.sirenOverride
                val officialSirenOverride = if (nightActive) night.officialSirenOverride else cfg.sirenOverride

                val enabled = tail.enabled
                val threats = rawThreats.filterValues { it.type.toThreatType() in enabled }

                val focus = resolveFocus(cfg.followMe, gps, LocationTracker.isFresh(now), tail.pinned)
                val focusLoc = focus.location
                val focusBannerCity =
                    if (tail.lang == AppLanguage.UA) focus.attribution.bannerCityUa else focus.attribution.bannerCityEn
                val focusCityUa = focus.attribution.bannerCityUa
                val focusRegion =
                    if (tail.lang == AppLanguage.UA) focus.attribution.bannerCityUa
                    else focus.attribution.bannerCityEn.ifBlank { Transliteration.transliterate(focus.attribution.bannerCityUa) }
                val focusPinned = focus.pinned
                val gpsFixMissing = focus.gpsFixMissing
                val focusToken = focus.attribution.token
                currentToken = focusToken

                // Raw episode = oblast-wide matching (scope=false): the all-clear latch keys on
                // this ending so a scope flip never synthesizes a false all-clear. Effective =
                // the same shared gate the map uses, so a city-scoped user rings only when the
                // alert actually covers the focus (or is oblast-wide). Mirror rule.
                val (focusOblastAlertRaw, focusOblastAlertSince) = if (focusToken != null) {
                    val alert = alerts.firstOrNull { it.inOblast(focusToken) }
                    (alert != null) to alert?.since
                } else {
                    false to null
                }
                val effectiveOfficialActive = officialAlertActiveFor(
                    alerts, focusToken, focusCityUa, cfg.officialAlertCityScope
                )

                val activeOfficialAlert = focusToken?.let { token -> alerts.firstOrNull { it.inOblast(token) } }
                val (officialReason, officialReasonThreatId) = if (activeOfficialAlert != null) {
                    engine.deriveOfficialAlertReason(
                        activeOfficialAlert,
                        threats.values.toList(),
                        focusLoc,
                        params,
                        tail.lang,
                        now
                    )
                } else {
                    null to null
                }

                val zoneThreats = if (focusLoc != null && !registry.isThreatDataStale(Monotonic.now())) {
                    val threatList = threats.values.toList()
                    val engineFocus = LatLng(focusLoc.lat, focusLoc.lon)
                    engine.evaluate(threatList, engineFocus, params, emptySet(), emptySet(), now).zoneThreats
                } else {
                    emptyMap()
                }

                val fastVib = VIBRATION_ZONE
                val slowVib = VIBRATION_ZONE

                MonitorState(
                    focusOblastAlertActive = effectiveOfficialActive,
                    focusOblastAlertRaw = focusOblastAlertRaw,
                    focusToken = focusToken,
                    focusOblastAlertSince = focusOblastAlertSince,
                    focusBannerCity = focusBannerCity,
                    focusCityUa = focusCityUa,
                    focusRegion = focusRegion,
                    focusPinned = focusPinned,
                    officialReason = officialReason,
                    officialReasonThreatId = officialReasonThreatId,
                    officialRegion = activeOfficialAlert?.let { alertRegionName(it, tail.lang) },
                    zoneThreats = zoneThreats,
                    params = params,
                    lang = tail.lang,
                    slowRedArmed = cfg.slowRedArmed,
                    slowYellowArmed = cfg.slowYellowArmed,
                    fastRedArmed = cfg.fastRedArmed,
                    fastYellowArmed = cfg.fastYellowArmed,
                    officialAlertsEnabled = cfg.officialAlertsEnabled,
                    zoneSirenOverride = zoneSirenOverride,
                    officialSirenOverride = officialSirenOverride,
                    connectionState = cs,
                    degraded = registry.degraded.value,
                    threats = threats,
                    alerts = alerts,
                    criticalOfflineOverride = cfg.criticalOfflineOverride,
                    fastVibrationLevel = fastVib,
                    slowVibrationLevel = slowVib,
                    focusLocation = focusLoc,
                    gpsFixMissing = gpsFixMissing,
                    nightActive = nightActive,
                    enabled = enabled
                ) to now
            }.collect { (state, now) ->
                handleState(state, now)
            }
        }
    }

    private fun handleState(state: MonitorState, now: Long) {
        val s = Strings.get(state.lang)

        if (state.lang != lastChannelLang) {
            lastChannelLang = state.lang
            notificationManager.updateChannels(s)
        }

        val registry = AppPluginHolder.registry
        val isDegradedNow = state.degraded
        // Offline escalation and its age are measured on the monotonic clock (see PluginRegistry);
        // the wall `now` is still used for engine staleness and display stamps below.
        val nowMono = Monotonic.now()
        val isOfflineNow = registry.isOffline(nowMono)
        val offlineSince = registry.degradedSince.value
        val offlineMinutes = if (isOfflineNow && offlineSince != null) {
            ((nowMono - offlineSince) / 60_000L).toInt()
        } else 0

        val twentyMinMs = 20 * 60 * 1000L
        val elapsedSinceReconnect = if (isOfflineNow && offlineSince != null) {
            nowMono - offlineSince
        } else 0L

        val redAlert = (state.officialAlertsEnabled && state.focusOblastAlertActive) ||
            state.zoneThreats.values.any { it == ThreatZone.INNER }
        val monitorTitle = when {
            isOfflineNow -> s.offlineStatusTitle
            state.focusPinned -> String.format(s.notifMonitoringCityFormat, state.focusBannerCity)
            else -> s.notifOngoingTitle
        }

        val monitorText = when {
            isOfflineNow -> offlineLiveBody(s, offlineMinutes)
            state.gpsFixMissing -> s.gpsUnavailableFollowMe
            isDegradedNow -> s.connDegradedBody
            else -> ""
        }

        notifyMonitor(
            title = monitorTitle,
            text = monitorText,
            retryLabel = if (isOfflineNow) s.offlineRetryAction else null,
            progressMax = if (isOfflineNow) 20 else null,
            progressNow = if (isOfflineNow) offlineMinutes else null,
            ignoreLabel = if (isOfflineNow && elapsedSinceReconnect >= twentyMinMs) s.offlineIgnoreAction else null,
            red = redAlert
        )

        val all = state.threats

        fun alertTier(id: String, spatial: ThreatZone): ThreatZone? {
            val fast = all[id]?.let { isFastType(it.type.toThreatType()) } ?: false
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

        var posted = false
        var postedId: String? = null
        val newEntries = alertable.entries
            .filter { (id, zone) -> knownZones[id] != zone }
            .sortedBy { it.value.ordinal }

        if (newEntries.isNotEmpty()) {
            val (id, zone) = newEntries.first()
            postedId = id
            val t = all[id]
            val body = t?.let { threatBody(it, state.lang) } ?: s.notifBodyRegion

            wakeLockManager.acquireForAlert()
            postAlert(
                zone,
                bannerFor(zone, s),
                body,
                state.zoneSirenOverride,
                revealThreat = t,
                vibrationLevel = if (t?.let { isFastType(it.type.toThreatType()) } ?: false) state.fastVibrationLevel else state.slowVibrationLevel
            )
            posted = true
            knownZones = knownZones + (id to zone)
            persistKnownZones()
        }

        val client = ConnectionHolder.getClient(applicationContext)
        val droppedZoneIds = knownZones.keys.filterNot { id ->
            id in state.zoneThreats.keys || client.wasUserShotRecently(id)
        }
        if (droppedZoneIds.isNotEmpty()) {
            knownZones = knownZones.filterKeys { it !in droppedZoneIds }
            persistKnownZones()
        }

        // Dropping the announced region: the user stopped monitoring it (focus moved to a
        // different oblast) OR re-pinned to a different city (even within the same oblast —
        // the oblast token alone can't see that, so the announced city is tracked too).
        val pinnedCityChanged = state.focusPinned && officialAnnouncedCity != null &&
            state.focusBannerCity != officialAnnouncedCity
        if (officialRegionToken != null && (state.focusToken != officialRegionToken || pinnedCityChanged)) {
            if (alertable.isEmpty()) {
                cancelAlert()
            }
            currentReasonThreatId = null
            debugOfficialActive = false
            officialRegionToken = null
            wasFocusAlertActive = false
            officialAnnouncedCity = null
            clearOfficialAnnounced()
        }

        if (!wasFocusAlertActive && officialAnnouncedToken != null && officialAnnouncedSince != null &&
            state.focusToken == officialAnnouncedToken &&
            state.focusOblastAlertSince != null &&
            state.focusOblastAlertSince == officialAnnouncedSince
        ) {
            wasFocusAlertActive = true
            currentReasonThreatId = officialAnnouncedReasonId
            officialRegionToken = state.focusToken
            officialAnnouncedCity = state.focusBannerCity
        }
        officialAnnouncedToken = null
        officialAnnouncedSince = null
        officialAnnouncedReasonId = null

        val officialActive = state.officialAlertsEnabled && state.focusOblastAlertActive
        val officialBody = state.officialReason ?: state.focusRegion

        if (!debugOfficialActive && state.focusOblastAlertActive) {
            debugOfficialActive = true
            val reasonThreat = state.officialReasonThreatId?.let { all[it] }
            DebugLog.recordOfficial(
                DebugLogKind.OFFICIAL_ON,
                night = state.nightActive,
                sirenOverride = state.officialSirenOverride,
                vibrationLevel = reasonThreat?.let {
                    if (isFastType(it.type.toThreatType())) state.fastVibrationLevel else state.slowVibrationLevel
                } ?: VIBRATION_STRONG,
                notified = state.officialAlertsEnabled && !posted,
                reason = when {
                    !state.officialAlertsEnabled -> DebugLogReason.TOGGLE_OFF
                    posted -> DebugLogReason.COALESCED
                    else -> DebugLogReason.FIRED
                },
                threatId = reasonThreat?.id,
                threatType = reasonThreat?.type?.toThreatType(),
                locality = reasonThreat?.let { it.locality ?: it.district ?: it.region }
                    ?: state.officialRegion ?: state.focusCityUa,
                distanceKm = distanceFromFocusKm(reasonThreat, state),
                now = System.currentTimeMillis()
            )
        }

        if (officialActive && !wasFocusAlertActive && !posted) {
            val reasonThreat = state.officialReasonThreatId?.let { all[it] }
            wakeLockManager.acquireForAlert()
            postAlert(
                null,
                String.format(s.alertBannerFormat, state.focusBannerCity),
                officialBody,
                state.officialSirenOverride,
                revealThreat = reasonThreat,
                vibrationLevel = reasonThreat?.let {
                    if (isFastType(it.type.toThreatType())) state.fastVibrationLevel else state.slowVibrationLevel
                } ?: VIBRATION_STRONG
            )
            currentReasonThreatId = state.officialReasonThreatId
            officialRegionToken = state.focusToken
            wasFocusAlertActive = true
            officialAnnouncedCity = state.focusBannerCity
            persistOfficialAnnounced(state)
        } else if (officialActive && wasFocusAlertActive && !posted && alertable.isEmpty() &&
            state.officialReasonThreatId != currentReasonThreatId && alertNotificationShowing() &&
            state.officialReasonThreatId != null
        ) {
            // Only refresh the shown notification when the new reason is a threat actually
            // inside the user's zones — once the reason falls back to the bare oblast name
            // (nothing nearby), a dismissed notification must not be re-raised about it.
            val reasonThreat = state.officialReasonThreatId?.let { all[it] }
            postAlert(
                null,
                String.format(s.alertBannerFormat, state.focusBannerCity),
                officialBody,
                state.officialSirenOverride,
                revealThreat = reasonThreat,
                vibrationLevel = reasonThreat?.let {
                    if (isFastType(it.type.toThreatType())) state.fastVibrationLevel else state.slowVibrationLevel
                } ?: VIBRATION_STRONG,
                silent = true
            )
            currentReasonThreatId = state.officialReasonThreatId
            officialAnnouncedCity = state.focusBannerCity
            persistOfficialAnnounced(state)
        }

        if (state.officialAlertsEnabled && wasFocusAlertActive && state.focusOblastAlertRaw &&
            !state.focusOblastAlertActive
        ) {
            if (alertable.isEmpty()) {
                cancelAlert()
            }
            currentReasonThreatId = null
            debugOfficialActive = false
            wasFocusAlertActive = false
            officialAnnouncedCity = null
            clearOfficialAnnounced()
            DebugLog.recordOfficial(
                DebugLogKind.OFFICIAL_OFF,
                night = state.nightActive,
                sirenOverride = state.officialSirenOverride,
                vibrationLevel = null,
                notified = false,
                reason = DebugLogReason.TOGGLE_OFF,
                threatId = null,
                threatType = null,
                locality = state.officialRegion ?: state.focusCityUa,
                distanceKm = null,
                now = System.currentTimeMillis()
            )
        }

        if (state.officialAlertsEnabled && wasFocusAlertActive && !state.focusOblastAlertRaw &&
            state.focusToken == officialRegionToken
        ) {
            if (alertable.isEmpty()) {
                cancelAlert()
            }
            postAllClear(s, state.focusBannerCity)
            currentReasonThreatId = null
            officialRegionToken = null
            debugOfficialActive = false
            officialAnnouncedCity = null
            clearOfficialAnnounced()
            DebugLog.recordOfficial(
                DebugLogKind.OFFICIAL_OFF,
                night = state.nightActive,
                sirenOverride = state.officialSirenOverride,
                vibrationLevel = null,
                notified = true,
                reason = DebugLogReason.FIRED,
                threatId = null,
                threatType = null,
                locality = state.officialRegion ?: state.focusCityUa,
                distanceKm = null,
                now = System.currentTimeMillis()
            )
        }

        if (!state.focusOblastAlertActive) {
            if (debugOfficialActive) {
                debugOfficialActive = false
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF,
                    night = state.nightActive,
                    sirenOverride = state.officialSirenOverride,
                    vibrationLevel = null,
                    notified = false,
                    reason = DebugLogReason.TOGGLE_OFF,
                    threatId = null,
                    threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa,
                    distanceKm = null,
                    now = System.currentTimeMillis()
                )
            }
            wasFocusAlertActive = false
            officialRegionToken = null
            officialAnnouncedCity = null
            clearOfficialAnnounced()
        }

        val nowForSweep = System.currentTimeMillis()
        if (nowForSweep - lastSweepAtMs >= SWEEP_THROTTLE_MS) {
            lastSweepAtMs = nowForSweep
            DebugLog.sweep(
                DebugLogContext(
                    threats = all,
                    focus = state.focusLocation,
                    token = state.focusToken,
                    enabledTypes = state.enabled,
                    zoneThreats = state.zoneThreats,
                    alertable = alertable,
                    knownZones = knownZones,
                    postedId = postedId,
                    night = state.nightActive,
                    sirenOverride = state.zoneSirenOverride,
                    fastVibrationLevel = state.fastVibrationLevel,
                    slowVibrationLevel = state.slowVibrationLevel,
                    now = now
                )
            )
        }

        if (state.zoneThreats.isEmpty() && !state.focusOblastAlertActive) {
            val since = emptySince
            if (since == null) {
                emptySince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since >= ALL_CLEAR_GRACE_MS) {
                emptySince = null
                cancelAlert()
                knownZones = emptyMap()
                persistKnownZones()
            }
        } else {
            emptySince = null
        }

        hasActiveThreats = state.zoneThreats.isNotEmpty() || state.focusOblastAlertActive
        isOutage = !AppPluginHolder.registry.wsHealthy.value
    }

    private fun persistKnownZones() {
        scope.launch {
            val obj = JSONObject()
            for ((k, v) in knownZones) {
                obj.put(k, v.name)
            }
            ServiceState(applicationContext).setActiveZoneAlerts(obj.toString())
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

    private fun notifyMonitor(
        title: String,
        text: String,
        retryLabel: String?,
        progressMax: Int? = null,
        progressNow: Int? = null,
        ignoreLabel: String? = null,
        red: Boolean = false
    ) {
        if (title == lastMonitorTitle && text == lastMonitorText && retryLabel == lastMonitorRetry &&
            progressMax == lastMonitorProgressMax && progressNow == lastMonitorProgressNow && ignoreLabel == lastMonitorIgnore &&
            red == lastMonitorRed
        ) return
        lastMonitorTitle = title
        lastMonitorText = text
        lastMonitorRetry = retryLabel
        lastMonitorProgressMax = progressMax
        lastMonitorProgressNow = progressNow
        lastMonitorIgnore = ignoreLabel
        lastMonitorRed = red
        notificationManager.safeNotify(
            NOTIF_MONITOR,
            notificationManager.buildMonitorNotification(title, text, retryLabel, progressMax, progressNow, ignoreLabel, red)
        )
    }

    private fun offlineLiveBody(s: Strings.StringSet, minutes: Int): String {
        val client = ConnectionHolder.getClient(applicationContext)
        val cs = client.connectionState.value
        if (cs is ConnectionState.Paused) return s.offlinePausedBody
        val attempt = when (cs) {
            is ConnectionState.Offline -> cs.attempt
            is ConnectionState.Connecting -> cs.attempt
            else -> 0
        }
        return String.format(s.offlineLiveFormat, minutes, 20, attempt + 1)
    }

    private fun postAlert(
        zone: ThreatZone?,
        title: String,
        body: String,
        sirenOverride: Boolean,
        revealThreat: NormalizedThreat? = null,
        silent: Boolean = false,
        vibrationLevel: Int = 3
    ) {
        notificationManager.postAlertNotification(
            zone = zone ?: ThreatZone.INNER,
            title = title,
            body = body,
            sirenOverride = sirenOverride,
            revealThreat = revealThreat,
            vibrationLevel = vibrationLevel
        )
    }

    private fun cancelAlert() {
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

    private fun postAllClear(s: Strings.StringSet, city: String) {
        notificationManager.postAllClearNotification(
            title = String.format(s.allClearTitle, city),
            body = s.allClearText
        )
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
                    val s = Strings.get(userPrefs.language().first())
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
                TelegramNotifier.sendSdkChanged(manifestResult.oldHash, manifestResult.newHash)
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
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        monitoringJob?.cancel()
        wakeLockManager.release()
        ConnectionHolder.clear()
        LocationTracker.stop()
        tally.reset()
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
