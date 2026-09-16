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
import ua.ukrainedrones.source.SourceState
import ua.ukrainedrones.engine.isFastType
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.AlertLevel
import ua.ukrainedrones.engine.officialStateFor
import ua.ukrainedrones.Transliteration
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.alertRegionName
import ua.ukrainedrones.engine.threatBody
import ua.ukrainedrones.UpdateInfo
import ua.ukrainedrones.UpdateManager
import ua.ukrainedrones.UpdateState
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.ConnectionLog
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
import ua.ukrainedrones.NeutralizedTally
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.service.ServiceState
import ua.ukrainedrones.service.MonitoringStatus
import ua.ukrainedrones.service.AudioAlarmDispatcher
import ua.ukrainedrones.service.FallingDebrisBuffer

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
                // A failed start must never look like working monitoring �?" leave the flag off
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
    private var lastShownId: String? = null
    /** Announced official episode for ANY level (red/yellow share it): LEVEL|token|since|city.
     *  Persisted pre-level as token|since|city — adopted silently on upgrade (see restore). */
    private var lastOfficialEpisode: String? = null
    private var knownZones: Map<String, ThreatZone> = emptyMap()
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
    @Volatile private var allClearSwipedAway = false
    private val debrisBuffer by lazy {
        FallingDebrisBuffer(
            scope = scope,
            onTick = { sec ->
                if (!allClearSwipedAway && notificationManager.isAllClearNotificationActive()) {
                    lastCleanAllClearCity?.let { city ->
                        val s = Strings.get(lastChannelLang ?: AppLanguage.EN)
                        postAllClear(s, city, debrisSeconds = sec, silent = true)
                    }
                }
            },
            onCompleted = {
                if (!allClearSwipedAway && notificationManager.isAllClearNotificationActive()) {
                    lastCleanAllClearCity?.let { city ->
                        val s = Strings.get(lastChannelLang ?: AppLanguage.EN)
                        postAllClear(s, city, debrisSeconds = 0, silent = true)
                    }
                    audioAlarmDispatcher.dispatchSmallVibration()
                }
            }
        )
    }
    @Volatile private var lastCleanAllClearCity: String? = null

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
        val focusOblastYellowAlertActive: Boolean,
        val focusOblastLevel: AlertLevel,
        val focusOblastRawLevel: AlertLevel,
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
        val yellowAlertsEnabled: Boolean = true,
        val zoneSirenOverride: Boolean,
        val officialSirenOverride: Boolean,
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
    ) {
        /** Derived summary of the two sub-channels — the master toggle. Mirrors the UI so the
         *  all-clear gate and the OFF log key on the same red||yellow fact the row shows. */
        val officialAlertsEnabled: Boolean
            get() = officialRedAlertsEnabled || yellowAlertsEnabled
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannels()
        AppSources.init(applicationContext)

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
                    AppSources.registry.retryNow()
                }
            }
            ACTION_IGNORE_RETRY -> {
                scope.launch {
                    AppSources.registry.pauseRetries(30)
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
            prefs.preferences
                .map { it.neutralizedTallyEnabled }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (!enabled) emptyFlow() else AppSources.registry.removedThreats
                }
                .collect { removed ->
                    val p = prefs.preferences.first()
                    if (!p.neutralizedTallyAllUkraine) {
                        val token = currentToken ?: return@collect
                        if (!inOblast(removed.region, removed.district, removed.locality, token)) return@collect
                    }
                    tally.onResolved(removed, p.language)
                }
        }

        monitoringJob = scope.launch {
            // Restore announced official-episode identity from persisted ServiceState
            // keys. Pre-level rows store token|since|city; adopted silently against the
            // first live boundary so an app update never re-sirens an ongoing alert.
            val _annToken = svcState.officialAnnouncedToken().first().ifBlank { null }
            val _annSince = svcState.officialAnnouncedSince().first().ifBlank { null }
            val _annCity = svcState.officialAnnouncedCity().first().ifBlank { null }
            if (_annToken != null && _annSince != null && _annCity != null) {
                lastOfficialEpisode = "$_annToken|$_annSince|$_annCity"
            }

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
                val rawThreats: Map<String, NormalizedThreat>,
                val alerts: List<OblastAlert>,
                val gps: LatLng?,
                val now: Long
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
                nowFlow
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                LiveInputs(
                    rawThreats = values[0] as Map<String, NormalizedThreat>,
                    alerts = values[1] as List<OblastAlert>,
                    gps = values[2] as LatLng?,
                    now = values[3] as Long
                )
            }

            combine(
                liveFlow,
                prefs.preferences
            ) { live, p ->
                val (rawThreats, alerts, gps, now) = live
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
                val zoneSirenOverride = if (nightActive) p.nightZoneSirenOverride else p.sirenOverride
                val officialSirenOverride = if (nightActive) p.nightOfficialSirenOverride else p.sirenOverride

                val enabled = p.alertEnabledTypes
                val threats = rawThreats.filterValues { it.type.toThreatType() in enabled }

                val focus = resolveFocus(p.followMe, gps, LocationTracker.isFresh(now), p.pinnedCity)
                val focusLoc = focus.location
                val focusBannerCity =
                    if (p.language == AppLanguage.UA) focus.attribution.bannerCityUa else focus.attribution.bannerCityEn
                val focusCityUa = focus.attribution.bannerCityUa
                val focusRegion =
                    if (p.language == AppLanguage.UA) focus.attribution.bannerCityUa
                    else focus.attribution.bannerCityEn.ifBlank { Transliteration.transliterate(focus.attribution.bannerCityUa) }
                val focusPinned = focus.pinned
                val gpsFixMissing = focus.gpsFixMissing
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
                val focusOblastAlertSince = official.alert?.since
                val focusOblastAlertActive = official.level == AlertLevel.RED
                val focusOblastYellowAlertActive = official.level == AlertLevel.YELLOW

                val activeOfficialAlert = official.alert
                val (officialReason, officialReasonThreatId) = if (activeOfficialAlert != null) {
                    engine.deriveOfficialAlertReason(
                        activeOfficialAlert,
                        threats.values.toList(),
                        focusLoc,
                        params,
                        p.language,
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
                    focusOblastAlertActive = focusOblastAlertActive,
                    focusOblastYellowAlertActive = focusOblastYellowAlertActive,
                    focusOblastLevel = focusOblastLevel,
                    focusOblastRawLevel = focusOblastRawLevel,
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
                    slowRedArmed = p.slowRedArmed,
                    slowYellowArmed = p.slowYellowArmed,
                    fastRedArmed = p.fastRedArmed,
fastYellowArmed = p.fastYellowArmed,
            officialRedAlertsEnabled = p.officialRedAlertsEnabled,
            yellowAlertsEnabled = p.officialYellowAlertsEnabled,
                    zoneSirenOverride = zoneSirenOverride,
                    officialSirenOverride = officialSirenOverride,
                    degraded = registry.degraded.value,
                    threats = threats,
                    alerts = alerts,
                    criticalOfflineOverride = p.criticalOfflineOverride,
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

        val twentyMinMs = 20 * 60 * 1000L
        val elapsedSinceReconnect = if (isOfflineNow && offlineSince != null) {
            nowMono - offlineSince
        } else 0L

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
            alertLevel = monitorAlertLevel
        )

        val all = state.threats

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

        val droppedZoneIds = knownZones.keys.filterNot { id ->
            id in state.zoneThreats.keys || AppSources.registry.wasUserShotRecently(id)
        }
        if (droppedZoneIds.isNotEmpty()) {
            knownZones = knownZones.filterKeys { it !in droppedZoneIds }
            persistKnownZones()
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

        /** Unified official-episode boundary for ANY level: LEVEL|token|since|city.
         *  Pre-level persisted rows hold token|since|city — [isNewEpisode] treats a
         *  suffix-matching legacy row as the same episode (silent adopt, no re-siren). */
        fun officialBoundary(state: MonitorState): String? {
            if (state.focusOblastLevel == AlertLevel.NONE) return null
            return "${state.focusOblastLevel}|${state.focusToken}|${state.focusOblastAlertSince}|${state.focusBannerCity}"
        }

        fun isNewEpisode(state: MonitorState): Boolean {
            val boundary = officialBoundary(state) ?: return false
            val stored = lastOfficialEpisode ?: return true
            if (stored == boundary) return false
            // Legacy pre-level row for the same episode — adopt it below, not a new onset.
            return stored != boundary.substringAfter('|')
        }

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
                    isOnset = knownZones[id] == null ||
                        (knownZones[id] == ThreatZone.OUTER && zone == ThreatZone.INNER),
                    zone = zone, level = if (zone == ThreatZone.INNER) "red" else "yellow"
                )
            }
            // Official is official: one branch for any level. Only the toggle gate,
            // copy and sound differ per level — the episode latch is shared.
            if (state.focusOblastLevel != AlertLevel.NONE) {
                val announced = if (state.focusOblastLevel == AlertLevel.RED) state.officialRedAlertsEnabled
                else state.yellowAlertsEnabled
                if (announced) {
                    val onset = isNewEpisode(state)
                    if (state.focusOblastLevel == AlertLevel.RED) {
                        val reasonThreat = state.officialReasonThreatId?.let { all[it] }
                        return Primary(
                            identity = "red|${state.focusToken}|${state.focusOblastAlertSince}|${state.focusBannerCity}|${state.officialReasonThreatId}",
                            title = String.format(s.alertBannerFormat, state.focusBannerCity),
                            body = (state.officialReason ?: state.officialRegion ?: state.focusRegion) + etaSuffix(reasonThreat, state),
                            revealThreat = reasonThreat,
                            silent = !onset,
                            vibration = reasonThreat?.let { if (isFastType(it.type.toThreatType(), typeCatalog)) state.fastVibrationLevel else state.slowVibrationLevel } ?: VIBRATION_STRONG,
                            isOnset = onset,
                            zone = null, level = state.focusOblastLevel.name.lowercase()
                        )
                    }
                    return Primary(
                        identity = "yellow|${state.focusToken}|${state.focusOblastAlertSince}|${state.focusBannerCity}",
                        title = String.format(s.alertYellowBannerFormat, state.focusBannerCity),
                        body = state.officialReason ?: state.officialRegion ?: state.focusRegion,
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
            val boundary = officialBoundary(state)
            // Silent adopt of a legacy pre-level persisted row for the same episode.
            if (boundary != null && lastOfficialEpisode != null &&
                lastOfficialEpisode != boundary && lastOfficialEpisode == boundary.substringAfter('|')
            ) {
                lastOfficialEpisode = boundary
            }
            if (boundary != null && lastOfficialEpisode != boundary) {
                val audible = if (state.focusOblastLevel == AlertLevel.RED) state.officialRedAlertsEnabled
                else state.yellowAlertsEnabled
                val reasonThreat = if (state.focusOblastLevel == AlertLevel.RED) {
                    state.officialReasonThreatId?.let { all[it] }
                } else null
                val vibration = reasonThreat?.let {
                    if (isFastType(it.type.toThreatType(), typeCatalog)) state.fastVibrationLevel else state.slowVibrationLevel
                } ?: VIBRATION_STRONG
                val locality = reasonThreat?.let { it.locality ?: it.district ?: it.region }
                    ?: state.officialRegion ?: state.focusCityUa
                when {
                    // A zone alert won the shared slot — the official episode still happened.
                    primary?.zone != null -> DebugLog.recordOfficial(
                        DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                        sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                        notified = false, reason = DebugLogReason.COALESCED,
                        threatId = reasonThreat?.id, threatType = reasonThreat?.type?.toThreatType(),
                        locality = locality, distanceKm = distanceFromFocusKm(reasonThreat, state),
                        now = System.currentTimeMillis()
                    )
                    audible -> {
                        wakeLockManager.acquireForAlert()
                        DebugLog.recordOfficial(
                            DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                            sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                            notified = true, reason = DebugLogReason.FIRED,
                            threatId = reasonThreat?.id, threatType = reasonThreat?.type?.toThreatType(),
                            locality = locality, distanceKm = distanceFromFocusKm(reasonThreat, state),
                            now = System.currentTimeMillis()
                        )
                        persistOfficialAnnounced(state)
                    }
                    else -> DebugLog.recordOfficial(
                        DebugLogKind.OFFICIAL_ON, night = state.nightActive,
                        sirenOverride = state.officialSirenOverride, vibrationLevel = vibration,
                        notified = false, reason = DebugLogReason.TOGGLE_OFF,
                        threatId = reasonThreat?.id, threatType = reasonThreat?.type?.toThreatType(),
                        locality = locality, distanceKm = distanceFromFocusKm(reasonThreat, state),
                        now = System.currentTimeMillis()
                    )
                }
                lastOfficialEpisode = boundary
            }
            if (lastOfficialEpisode != null && state.focusOblastRawLevel == AlertLevel.NONE && state.officialAlertsEnabled) {
                if (alertable.isEmpty()) cancelAlert()
                lastCleanAllClearCity = state.focusBannerCity
                debrisBuffer.start(durationSeconds = 180)
                DebugLog.recordOfficial(
                    DebugLogKind.OFFICIAL_OFF, night = state.nightActive,
                    sirenOverride = state.officialSirenOverride, vibrationLevel = null,
                    notified = true, reason = DebugLogReason.FIRED,
                    threatId = null, threatType = null,
                    locality = state.officialRegion ?: state.focusCityUa, distanceKm = null,
                    now = System.currentTimeMillis()
                )
                lastOfficialEpisode = null
            }
            val shownOfficial = lastShownId?.startsWith("red|") == true || lastShownId?.startsWith("yellow|") == true
            if (shownOfficial && primary == null &&
                state.focusOblastRawLevel != AlertLevel.NONE && state.officialAlertsEnabled
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
        fun reconcileNotif(primary: Primary?, state: MonitorState) {
            if (primary?.identity != lastShownId) {
                when {
                    primary == null -> {
                        // Don't tear down a live official notification on a flicker tick:
                        // any official id stays up while any official level is live upstream.
                        val shownOfficialStillLive =
                            (lastShownId?.startsWith("red|") == true || lastShownId?.startsWith("yellow|") == true) &&
                                state.focusOblastRawLevel != AlertLevel.NONE && state.officialAlertsEnabled
                        if (!shownOfficialStillLive) {
                            if (knownZones.isEmpty() && state.zoneThreats.isEmpty() && !state.focusOblastAlertActive && !state.focusOblastYellowAlertActive) {
                                knownZones = emptyMap(); persistKnownZones()
                            }
                            cancelAlert()
                        }
                    }
                    primary.isOnset -> {
                        debrisBuffer.abort()
                        wakeLockManager.acquireForAlert()
                        audioAlarmDispatcher.dispatchDangerAlarm(
                            isRed = (primary.level == "red"),
                            overrideSilence = (state.zoneSirenOverride ?: state.officialSirenOverride)
                        )
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            state.zoneSirenOverride ?: state.officialSirenOverride,
                            revealThreat = primary.revealThreat, vibrationLevel = primary.vibration)
                    }
                    alertNotificationShowing() -> {
                        postAlert(primary.zone, primary.level, primary.title, primary.body,
                            state.zoneSirenOverride ?: state.officialSirenOverride,
                            revealThreat = primary.revealThreat, silent = true, vibrationLevel = primary.vibration)
                    }
                    else -> { /* dismissed, don't re-raise */ }
                }
                lastShownId = primary?.identity
            }
        }

        // Invoke the reconcile pipeline
        val primary = buildPrimary(state, all)
        val postedId = if (primary?.zone != null && primary.isOnset) primary.identity.substringAfter("zone|").substringBefore('|') else null
        // Mirror knownZones to reality every tick. buildPrimary already read the OLD
        // map to compute isOnset above, so updating here keeps onset/escalation
        // detection correct while letting downgrades (INNER->OUTER) and lateral
        // moves update state silently instead of re-firing as a fresh onset.
        val knownBefore = knownZones
        alertable.forEach { (id, zone) -> knownZones = knownZones + (id to zone) }
        if (knownZones != knownBefore) persistKnownZones()
        reconcileEpisode(primary, state, all)
        reconcileNotif(primary, state)

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
                    now = now,
                    typeCatalog = typeCatalog
                )
            )
        }

        if (state.zoneThreats.isEmpty() && !state.focusOblastAlertActive && !state.focusOblastYellowAlertActive) {
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

        hasActiveThreats = state.zoneThreats.isNotEmpty() || state.focusOblastLevel != AlertLevel.NONE
        isOutage = !AppSources.registry.wsHealthy.value
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

    private fun offlineLiveBody(s: Strings.StringSet, minutes: Int): String {
        val registry = AppSources.registry
        if (registry.connectionState.value == SourceState.PAUSED) return s.offlinePausedBody
        val attempt = registry.retryState.value?.attempt ?: 0
        return String.format(s.offlineLiveFormat, minutes, 20, attempt + 1)
    }

    private fun postAlert(
        zone: ThreatZone?,
        level: String,
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
            vibrationLevel = vibrationLevel,
            silent = silent
        )
    }

    private fun cancelAlert() {
        audioAlarmDispatcher.stopActiveAlert()
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

    private fun postAllClear(s: Strings.StringSet, city: String, debrisSeconds: Int = 0, silent: Boolean = false) {
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
            silent = silent
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
        audioAlarmDispatcher.release()
        debrisBuffer.abort()
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        monitoringJob?.cancel()
        wakeLockManager.release()
        AppSources.clear()
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
