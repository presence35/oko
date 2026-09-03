package ua.ukrainedrones
import ua.ukrainedrones.engine.distanceFlat

import android.app.Application
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.connection.isConnected
import ua.ukrainedrones.connection.isDegraded
import ua.ukrainedrones.connection.isOffline
import ua.ukrainedrones.ODESA_LAT
import ua.ukrainedrones.ODESA_LON
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toEngineString
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.SpeedSource
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.service.ServiceState
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToLong
import kotlin.random.Random

@Immutable
data class UiState(
    val connected: Boolean = false,
    val neptunDown: Boolean = false,                 // NEPTUN offline
    val degraded: Boolean = false,                   // connected but stream quiet (orange pill)
    val threatsInner: List<NormalizedThreat> = emptyList(), // reaching within the red time tier
    val threatsOuter: List<NormalizedThreat> = emptyList(), // in the yellow time tier, beyond red
    val mapThreats: List<NormalizedThreat> = emptyList(),   // all active threats across Europe
    val userLocation: LatLng? = null,
    val gpsFixAvailable: Boolean = false,         // a GPS/cell fix has arrived at least once
    val slowRedKm: Int = 20,      // slow threats: distance to the red (inner) zone, km
    val slowYellowKm: Int = 50,  // slow threats: distance to the yellow (outer) zone, km
    val fastRedMin: Int = 5,     // fast threats: ETA to the red (inner) zone, minutes
    val fastYellowMin: Int = 20,  // fast threats: ETA to the yellow (outer) zone, minutes
    val slowRedArmed: Boolean = true,
    val slowYellowArmed: Boolean = true,
    val fastRedArmed: Boolean = true,
    val fastYellowArmed: Boolean = true,
    val activeZoneParams: ZoneParams = ZoneParams(20, 50, 5, 20), // effective (night-aware) thresholds
    val activeSlowRedArmed: Boolean = true,
    val activeSlowYellowArmed: Boolean = true,
    val activeFastRedArmed: Boolean = true,
    val activeFastYellowArmed: Boolean = true,
    val nightActive: Boolean = false,                    // night window currently in effect
    val nightWindowText: String = "",                    // localized "22:00–07:00" when configured
    val nightEnabled: Boolean = true,
    val nightStartMin: Int = 22 * 60,
    val nightEndMin: Int = 7 * 60,
    val nightUseCustomZones: Boolean = false,
    val nightSlowRedKm: Int = 20,
    val nightSlowYellowKm: Int = 50,
    val nightFastRedMin: Int = 5,
    val nightFastYellowMin: Int = 20,
    val nightSlowRedArmed: Boolean = true,
    val nightSlowYellowArmed: Boolean = true,
    val nightFastRedArmed: Boolean = true,
    val nightFastYellowArmed: Boolean = true,
    val nightZoneSirenOverride: Boolean = false,
    val nightOfficialSirenOverride: Boolean = false,
    val officialAlertsEnabled: Boolean = true,
    val officialAlertCityScope: Boolean = false,
    val sirenOverride: Boolean = false,
    val criticalOfflineOverride: Boolean = true,
    val criticalOfflineBypassSilent: Boolean = false,
    val hiddenTypes: Set<ThreatType> = emptySet(),      // hidden from the map
    val silencedTypes: Set<ThreatType> = emptySet(),    // alerts off (still on the map, dimmed)
    val activeZone: ThreatZone? = null,           // most specific zone with a threat
    val focusOblastAlertActive: Boolean = false,  // official alert on the focus point's oblast
    val focusBannerCity: String = "",             // localized city name for the alert banner
    val language: AppLanguage = AppLanguage.EN,
    val followMe: Boolean = true,
    val pinnedCity: City? = null,
    val focusLocation: LatLng? = null,            // camera + zone center: GPS (follow) or pinned city
    val redCities: Set<String> = emptySet(),      // nameUa of cities shown red (scope-aware)
    val threatLevel: Double = 0.0,                 // experimental 0..10 gauge for the popup
    val revealRequest: RevealRequest? = null,      // notification tap: pan the camera onto a threat
    val flourish: FlourishShow? = null,            // tally tap: replay the shot-down show
    val flyby: AviationFlybyShow? = null,          // MiG-31K takeoff: full-size pass across the viewport
    val disclaimerCollapsed: Boolean = false,
    val disclaimerReadCount: Int = 0,
    val update: UpdateState = UpdateState.Idle,
    val needsInstallPermission: Boolean = false,
    val latestVersion: String? = null,
    val wizardCompleted: Boolean? = null,   // null = prefs not loaded yet (never gate UI on that)
    val batteryOnboardShown: Boolean = false,
    val threatCardSize: ThreatCardSize = ThreatCardSize.LARGE,
    val iconSet: ThreatIconSet = ThreatIconSet.PHOTO,
    val showMapScale: Boolean = true,
    val showMediumCities: Boolean = true,
    val showSmallCities: Boolean = true,
    val justFunMasterEnabled: Boolean = false,
    val deathAnimationEnabled: Boolean = true,
    val flybyAnimationEnabled: Boolean = true,
    val followBullet: Boolean = true,
    val neutralizedTallyEnabled: Boolean = true,
    val neutralizedTallyAllUkraine: Boolean = false,
    val fastGroupCollapsed: Boolean = false,
    val slowGroupCollapsed: Boolean = false,
    val sheltersEnabled: Boolean = true,
    val sheltersWithKids: Boolean = true,
    val periodicGps: Boolean = false,
    val calmMessagesEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val shelterIndex: ShelterIndex? = null,        // Odesa shelters — null while loading/unavailable
    val mapVisible: Boolean = true,          // the map screen is the visible screen (not settings/shelters/guide)
    val shelterOverlayUp: Boolean = false,   // the shelter overlay is showing (suppresses flourish)
    val alertActive: Boolean = false,        // any threat or official alert live right now
    val threatDataStale: Boolean = false,
    val notificationsDisabledBySystem: Boolean = false
)

/**
 * Popup-only state, deliberately OUTSIDE [UiState]: tapping a threat updates only this flow,
 * so the header/map/footer scopes never recompose on selection. Derived from the selection
 * flows + the latest UiState ambient values (focus/params/policy flags) — no duplicated logic.
 */
@Immutable
data class SelectionUi(
    val selected: NormalizedThreat? = null,
    val proximity: ThreatProximity? = null,
    val neutralized: NormalizedThreat? = null,   // resolved card while the death window plays
    val fakeNeutralize: Boolean = false
)

/** One-shot request from a notification tap to bring the camera onto a threat. */
@Immutable
data class RevealRequest(
    val tick: Int,
    val id: String?,
    val lat: Double,
    val lon: Double
)

/** Distance/ETA facts for the threat popup, computed from the predicted position. */
@Immutable
data class ThreatProximity(
    val predicted: LatLng,
    val distToUserKm: Double?,   // null when GPS unavailable
    val etaToUserMin: Double?,   // null when GPS unavailable or no speed
    val params: ZoneParams,
    val speedSource: SpeedSource,
    val speedKmh: Double?        // the displayed speed value (measured or nominal)
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    companion object {
        private const val DAILY_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val SHELTERS_CACHE_FILE = "odesa_shelters.json"
        /** Odesa centre — the pre-first-fix fallback focus so the first visual is complete. */
        private val ODESA_FALLBACK_FOCUS = LatLng(ODESA_LAT, ODESA_LON)
    }

    private val prefs = UserPrefs(app.applicationContext)
    private val svcState = ServiceState(app.applicationContext)
    private val updateManager = UpdateManager(app.applicationContext)

    /** Tri-state haptics pref → effective value: absent follows the system haptic setting. */
    private fun resolveHaptics(pref: Boolean?): Boolean = when (pref) {
        null -> android.provider.Settings.System.getInt(
            getApplication<Application>().contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
        else -> pref
    }

    private val selectedThreatFlow = MutableStateFlow<NormalizedThreat?>(null)
    // A threat id long-pressed on the map is treated as neutralized so the card
    // self-destructs like a real resolution. Cleared on every selection change.
    private val neutralizedFlow = MutableStateFlow<String?>(null)
    private val revealFlow = MutableStateFlow<RevealRequest?>(null)
    private var revealTick = 0
    private var navigateToMapCounter = 0
    private val _navigateToMapFlow = MutableStateFlow(0)
    val navigateToMapTick: StateFlow<Int> get() = _navigateToMapFlow
    /** Tally-tap replay: remembered resolved threats to shoot down on the map (flourish only). */
    private val flourishFlow = MutableStateFlow<FlourishShow?>(null)
    private var flourishTick = 0
    /** MiG-31K takeoff flyby — one full-size pass across the viewport per new INNER aviation. */
    private val flybyFlow = MutableStateFlow<AviationFlybyShow?>(null)
    private var flybyTick = 0L
    /** AVIATION ids whose flyby already played this process (concurrent — the state combine
     *  lambda runs on whichever dispatcher its upstream flows last emitted on). */
    private val flybyPlayedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val updateStateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val installPermissionFlow = MutableStateFlow(false)
    private val latestVersionFlow = MutableStateFlow<String?>(null)
    private var lastAvailableUpdate: UpdateInfo? = null
    /** Bumped each time a Settings-open check finds an available update (remind-only or fresh). */
    private val updateReminderFlow = MutableStateFlow(0)
    val updateReminderTick: StateFlow<Int> get() = updateReminderFlow
    private val client = ConnectionHolder.getClient(app)
    private val connectionStateFlow = client.connectionState
    private val registry = AppPluginHolder.registry
    private val engine = ThreatEngine(registry.typeCatalog.value)
    private val threatsFlow = registry.allThreats.map { list ->
        list.associate { it.id to it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private val alertsFlow = registry.allAlerts
    /** Sampled NEPTUN state for UI (120ms) — bounds recomposition rate during heavy streams.
     *  AlertService still consumes the raw stream directly (mirror rule). */
    @OptIn(FlowPreview::class)
    private val neptunForUi = combine(connectionStateFlow, threatsFlow, alertsFlow) { cs, t, a ->
        Triple(cs, t, a)
    }.sample(120)
    /** Wall-clock epoch millis, updated once per second. UI components that need a live
     *  timestamp (shelter fix age) collect this instead of reading it from UiState —
     *  removing it from UiState lets StateFlow dedup no-op ticks. */
    val now: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis()).also { it ->
        viewModelScope.launch { while (isActive) { delay(1000); it.value = System.currentTimeMillis() } }
    }
    /** Last NEPTUN frame timestamp, derived from the connection state. Collected by the
     *  connection status sheet to show "last update Xs ago" without polluting UiState. */
    val lastFrameAt: Flow<Long> = connectionStateFlow.map { cs ->
        (cs as? ConnectionState.Connected)?.lastFrameAtMs
            ?: (cs as? ConnectionState.Degraded)?.lastFrameAtMs ?: 0L
    }.distinctUntilChanged()
    private val shelterIndexFlow = MutableStateFlow<ShelterIndex?>(null)
    /** Whether the map screen is the visible screen — the neutralizing animation and death
     *  flourish only run while it is, so no stale half-consumed animations play on return. */
    private val mapVisibleFlow = MutableStateFlow(true)
    /** Whether the shelter overlay is showing on the map — the resolved-threat flourish and
     *  neutralizing card are suppressed while it is (nothing should steal the user's focus). */
    private val shelterModeFlow = MutableStateFlow(false)
    /** Whether the app process is actually foregrounded (unlike [mapVisibleFlow], which tracks
     *  the visible tab even when backgrounded) — the flyby auto-trigger only plays live, so an
     *  off-phone user gets it on notification-tap reveal instead of it firing unseen. */
    private val appForegroundFlow = MutableStateFlow(true)
    /** Whether the current neutralization is user-initiated (long-press) so we show the
     *  "fake" text instead of the real "neutralizing" copy. Cleared on selection change. */
    private val fakeNeutralizeFlow = MutableStateFlow(false)
    private var isChecking = false
    private val zonesFlow = combine(
        prefs.slowRedKm(), prefs.slowYellowKm(), prefs.fastRedMin(), prefs.fastYellowMin()
    ) { slowRed, slowYellow, fastRed, fastYellow ->
        ZoneParams(slowRed, slowYellow, fastRed, fastYellow)
    }

    init {
        AppPluginHolder.init(getApplication())
        LocationTracker.start(getApplication())
        // Auto-check for updates at most once per day; pops only when no alert is active.
        autoCheckForUpdates(allowPopup = true)
        // Shelters load from the bundled snapshot first (offline), then refresh daily.
        viewModelScope.launch { loadShelters() }
    }

    override fun onCleared() {
        super.onCleared()
    }

    /** Everything read from prefs whenever any of them changes. */
    private data class ThreatPrefs(
        val map: Set<ThreatType>,
        val alert: Set<ThreatType>,
        val lang: AppLanguage,
        val disclaimer: Boolean,
        val disclaimerReadCount: Int
    )

    private data class PrefsQuad(
        val pinnedCity: String?,
        val wizardCompleted: Boolean?,
        val batteryOnboardShown: Boolean,
        val cardSize: ThreatCardSize,
        val iconSet: ThreatIconSet,
        val sheltersEnabled: Boolean,
        val sheltersWithKids: Boolean,
        val periodicGps: Boolean,
        val calmMessagesEnabled: Boolean,
        val hapticsEnabled: Boolean?
    )

    /** Night-mode window prefs (raw, day values untouched). */
    private data class NightWindowPrefs(
        val enabled: Boolean,
        val startMin: Int,
        val endMin: Int,
        val useCustomZones: Boolean
    )

    /** Night-mode zone prefs (raw). */
    private data class NightZonesPrefs(
        val slowRedKm: Int,
        val slowYellowKm: Int,
        val fastRedMin: Int,
        val fastYellowMin: Int,
        val slowRedArmed: Boolean,
        val slowYellowArmed: Boolean,
        val fastRedArmed: Boolean,
        val fastYellowArmed: Boolean,
        val zoneSirenOverride: Boolean,
        val officialSirenOverride: Boolean
    )

    private data class NightPrefs(
        val window: NightWindowPrefs,
        val zones: NightZonesPrefs
    )

    private data class PrefsSnapshot(
        val mapEnabled: Set<ThreatType>,
        val alertEnabled: Set<ThreatType>,
        val language: AppLanguage,
        val disclaimerCollapsed: Boolean,
        val disclaimerReadCount: Int,
        val slowRedArmed: Boolean,
        val slowYellowArmed: Boolean,
        val fastRedArmed: Boolean,
        val fastYellowArmed: Boolean,
        val officialAlertsEnabled: Boolean,
        val officialAlertCityScope: Boolean,
        val sirenOverride: Boolean,
        val followMe: Boolean,
        val criticalOfflineOverride: Boolean,
        val criticalOfflineBypassSilent: Boolean,
        val pinnedCity: String?,
        val wizardCompleted: Boolean?,
        val batteryOnboardShown: Boolean,
        val cardSize: ThreatCardSize,
        val iconSet: ThreatIconSet,
        val showMapScale: Boolean,
        val showMediumCities: Boolean,
        val showSmallCities: Boolean,
        val justFunMasterEnabled: Boolean,
        val deathAnimationEnabled: Boolean,
        val flybyAnimationEnabled: Boolean,
        val followBullet: Boolean,
        val neutralizedTallyEnabled: Boolean,
        val neutralizedTallyAllUkraine: Boolean,
val fastGroupCollapsed: Boolean,
        val slowGroupCollapsed: Boolean,
        val sheltersEnabled: Boolean,
        val sheltersWithKids: Boolean,
        val periodicGps: Boolean,
        val calmMessagesEnabled: Boolean,
        val hapticsEnabled: Boolean?,
        val night: NightPrefs
    )

    /** Live inputs that change every frame/second: stream, GPS, selection, time. */
    private data class LiveSnapshot(
        val cs: ConnectionState,
        val threats: Map<String, NormalizedThreat>,
        val alerts: List<OblastAlert>,
        val threatDataStale: Boolean,
        val slowRedKm: Int,
        val slowYellowKm: Int,
        val fastRedMin: Int,
        val fastYellowMin: Int,
        val userLocation: LatLng?,
        val gpsFixAvailable: Boolean,
        val reveal: RevealRequest?,
        val flourish: FlourishShow?,
        val mapVisible: Boolean,
        val shelterModeActive: Boolean
    )

    private data class UpdateUi(
        val update: UpdateState,
        val needsInstallPermission: Boolean,
        val latestVersion: String?
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
        val showMapScale: Boolean,
        val showMediumCities: Boolean,
        val showSmallCities: Boolean,
        val deathAnimationEnabled: Boolean,
        val followBullet: Boolean,
        val neutralizedTallyEnabled: Boolean,
        val neutralizedTallyAllUkraine: Boolean,
        val fastGroupCollapsed: Boolean,
        val slowGroupCollapsed: Boolean,
        val criticalOfflineOverride: Boolean,
        val criticalOfflineBypassSilent: Boolean,
        val flybyAnimationEnabled: Boolean,
        val justFunMasterEnabled: Boolean
    )

    private val liveSnapshot = combine(
        neptunForUi,
        zonesFlow,
        LocationTracker.location,
        LocationTracker.lastFixAtMs,
        client.threatDataStale,
        revealFlow,
        flourishFlow,
        mapVisibleFlow,
        shelterModeFlow
    ) { values: Array<Any?> ->
        val (cs, threats, alerts) = values[0] as Triple<ConnectionState, Map<String, NormalizedThreat>, List<OblastAlert>>
        val radii = values[1] as ZoneParams
        val location = values[2] as LatLng?
        val lastFix = values[3] as Long?
        val threatDataStale = values[4] as Boolean
        val reveal = values[5] as RevealRequest?
        val flourish = values[6] as FlourishShow?
        val mapVisible = values[7] as Boolean
        val shelterModeActive = values[8] as Boolean
        LiveSnapshot(
            cs, threats, alerts, threatDataStale,
            radii.slowRedKm, radii.slowYellowKm, radii.fastRedMin, radii.fastYellowMin,
            location, lastFix != null, reveal, flourish, mapVisible, shelterModeActive
        )
    }

    private val prefsSnapshot = combine(
        combine(
            threatMapFlow(prefs),
            threatAlertFlow(prefs),
            prefs.language(),
            prefs.disclaimerCollapsed(),
            prefs.disclaimerReadCount()
        ) { map, alert, lang, disclaimer, readCount ->
            ThreatPrefs(map, alert, lang, disclaimer, readCount)
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
            prefs.showMapScale(),
            prefs.showMediumCities(),
            prefs.showSmallCities(),
            prefs.deathAnimationEnabled(),
            prefs.followBullet(),
            prefs.neutralizedTallyEnabled(),
            prefs.neutralizedTallyAllUkraine(),
            prefs.fastGroupCollapsed(),
            prefs.slowGroupCollapsed(),
            prefs.criticalOfflineOverride(),
            prefs.criticalOfflineBypassSilent(),
            prefs.flybyAnimationEnabled(),
            prefs.justFunMasterEnabled()
        ) { flags: Array<Boolean> ->
            AlertConfig(
                flags[0], flags[1], flags[2], flags[3], flags[4], flags[5],
                flags[6], flags[7], flags[8], flags[9], flags[10], flags[11], flags[12],
                flags[13], flags[14], flags[15], flags[16], flags[17], flags[18], flags[19],
                flags[20]
            )
        },
        combine(
            combine(
                combine(
                    combine(
                        prefs.pinnedCity(),
                        prefs.wizardCompleted(),
                        prefs.batteryOnboardShown(),
                        prefs.threatCardSize(),
                        prefs.threatIconSet()
                    ) { pinned, wizardDone, batteryShown, card, iconSet ->
                        PrefsQuad(pinned, wizardDone, batteryShown, card, iconSet, false, true, false, true, true)
                    },
                    prefs.sheltersEnabled()
                ) { quad, shelters ->
                    quad.copy(sheltersEnabled = shelters)
                },
                prefs.sheltersWithKidsEnabled()
            ) { quad, kids ->
                quad.copy(sheltersWithKids = kids)
            },
            prefs.periodicGps(),
            prefs.calmMessagesEnabled(),
            prefs.hapticsEnabled()
        ) { quad, periodic, calm, haptics ->
            quad.copy(periodicGps = periodic, calmMessagesEnabled = calm, hapticsEnabled = haptics)
        },
        combine(
            combine(
                prefs.nightEnabled(), prefs.nightStartMin(), prefs.nightEndMin(),
                prefs.nightUseCustomZones()
            ) { enabled, start, end, use ->
                NightWindowPrefs(enabled, start, end, use)
            },
combine(
                    combine(
                        prefs.nightSlowRedKm(), prefs.nightSlowYellowKm(), prefs.nightFastRedMin(),
                        prefs.nightFastYellowMin()
                    ) { sr, sy, fr, fy ->
                        NightZonesPrefs(sr, sy, fr, fy, true, true, true, true, false, false)
                    },
                    combine(
                        prefs.nightSlowRedZoneArmed(), prefs.nightSlowYellowZoneArmed(),
                        prefs.nightFastRedZoneArmed(), prefs.nightFastYellowZoneArmed(),
                        prefs.nightZoneSirenOverride(), prefs.nightOfficialSirenOverride()
                    ) { flags: Array<Boolean> ->
                        flags
                    }
                ) { zones, flags ->
                    zones.copy(
                        slowRedArmed = flags[0],
                        slowYellowArmed = flags[1],
                        fastRedArmed = flags[2],
                        fastYellowArmed = flags[3],
                        zoneSirenOverride = flags[4],
                        officialSirenOverride = flags[5]
                    )
                }
    ) { window, zones ->
        NightPrefs(window, zones)
    }
    ) { a, b, c, night ->
        PrefsSnapshot(
            mapEnabled = a.map,
            alertEnabled = a.alert,
            language = a.lang,
            disclaimerCollapsed = a.disclaimer,
            disclaimerReadCount = a.disclaimerReadCount,
            slowRedArmed = b.slowRedArmed,
            slowYellowArmed = b.slowYellowArmed,
            fastRedArmed = b.fastRedArmed,
            fastYellowArmed = b.fastYellowArmed,
            officialAlertsEnabled = b.officialAlertsEnabled,
            officialAlertCityScope = b.officialAlertCityScope,
            sirenOverride = b.sirenOverride,
            followMe = b.followMe,
            criticalOfflineOverride = b.criticalOfflineOverride,
            criticalOfflineBypassSilent = b.criticalOfflineBypassSilent,
            pinnedCity = c.pinnedCity,
            wizardCompleted = c.wizardCompleted,
            batteryOnboardShown = c.batteryOnboardShown,
            cardSize = c.cardSize,
            iconSet = c.iconSet,
            showMapScale = b.showMapScale,
            showMediumCities = b.showMediumCities,
            showSmallCities = b.showSmallCities,
            justFunMasterEnabled = b.justFunMasterEnabled,
            deathAnimationEnabled = b.deathAnimationEnabled,
            flybyAnimationEnabled = b.flybyAnimationEnabled,
            followBullet = b.followBullet,
            neutralizedTallyEnabled = b.neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = b.neutralizedTallyAllUkraine,
            fastGroupCollapsed = b.fastGroupCollapsed,
            slowGroupCollapsed = b.slowGroupCollapsed,
            sheltersEnabled = c.sheltersEnabled,
            sheltersWithKids = c.sheltersWithKids,
            periodicGps = c.periodicGps,
            calmMessagesEnabled = c.calmMessagesEnabled,
            hapticsEnabled = c.hapticsEnabled,
            night = night
        )
    }

    private val updateUiFlow = combine(
        updateStateFlow,
        installPermissionFlow,
        latestVersionFlow
    ) { update, install, latest ->
        UpdateUi(update, install, latest)
    }

    /**
     * One-time background read that primes the DataStore cache off the main thread, so the
     * first uiState emission already carries the persisted language/radii — no runBlocking on
     * the main thread and no first-frame flash.
     */
    private val seedFlow: Flow<Unit> = flow {
        prefs.language().first()
        prefs.slowRedKm().first()
        prefs.slowYellowKm().first()
        prefs.fastRedMin().first()
        prefs.fastYellowMin().first()
        prefs.slowRedZoneArmed().first()
        prefs.slowYellowZoneArmed().first()
        prefs.fastRedZoneArmed().first()
        prefs.fastYellowZoneArmed().first()
        prefs.followMe().first()
        prefs.pinnedCity().first()
        prefs.wizardCompleted().first()
        prefs.batteryOnboardShown().first()
        prefs.nightEnabled().first()
        prefs.nightStartMin().first()
        prefs.nightEndMin().first()
        prefs.nightUseCustomZones().first()
        prefs.nightSlowRedKm().first()
        prefs.nightSlowYellowKm().first()
        prefs.nightFastRedMin().first()
        prefs.nightFastYellowMin().first()
        prefs.nightSlowRedZoneArmed().first()
        prefs.nightSlowYellowZoneArmed().first()
        prefs.nightFastRedZoneArmed().first()
        prefs.nightFastYellowZoneArmed().first()
        prefs.nightZoneSirenOverride().first()
        prefs.nightOfficialSirenOverride().first()
        prefs.deathAnimationEnabled().first()
        prefs.followBullet().first()
        emit(Unit)
    }.flowOn(Dispatchers.IO)

val uiState: StateFlow<UiState> = combine<Any?, UiState>(
        seedFlow,
        liveSnapshot,
        prefsSnapshot,
        updateUiFlow,
        shelterIndexFlow,
        now,
        flybyFlow
    ) { values ->
        val live = values[1] as LiveSnapshot
        val prefs = values[2] as PrefsSnapshot
        val updateUi = values[3] as UpdateUi
        val shelterIndex = values[4] as ShelterIndex?
        val now = values[5] as Long
        val flyby = values[6] as AviationFlybyShow?
        val nightActive = isNightActive(
            NightConfig(prefs.night.window.enabled, prefs.night.window.startMin, prefs.night.window.endMin),
            now
        )
        val nightZones = NightZones(
            prefs.night.zones.slowRedKm, prefs.night.zones.slowYellowKm,
            prefs.night.zones.fastRedMin, prefs.night.zones.fastYellowMin,
            prefs.night.zones.slowRedArmed, prefs.night.zones.slowYellowArmed,
            prefs.night.zones.fastRedArmed, prefs.night.zones.fastYellowArmed
        )
        val effectiveParams = effectiveZoneParams(
            ZoneParams(live.slowRedKm, live.slowYellowKm, live.fastRedMin, live.fastYellowMin),
            nightZones, prefs.night.window.useCustomZones, nightActive
        )
        val activeArmed = effectiveArmed(
            ZoneArmed(
                prefs.slowRedArmed, prefs.slowYellowArmed,
                prefs.fastRedArmed, prefs.fastYellowArmed
            ),
            nightZones, prefs.night.window.useCustomZones, nightActive
        )
        val uiState = buildUiState(
            cs = live.cs,
            threats = live.threats,
            alerts = live.alerts,
            threatDataStale = live.threatDataStale,
            slowRedKm = live.slowRedKm,
            slowYellowKm = live.slowYellowKm,
            fastRedMin = live.fastRedMin,
            fastYellowMin = live.fastYellowMin,
            effectiveParams = effectiveParams,
            mapEnabledTypes = prefs.mapEnabled,
            alertedTypes = prefs.alertEnabled,
            language = prefs.language,
            userLocation = live.userLocation,
            gpsFixAvailable = live.gpsFixAvailable,
            followMe = prefs.followMe,
            pinnedCity = prefs.pinnedCity?.let { name ->
                Cities.byUa[name]
            },
            now = now,
            reveal = live.reveal,
            flourish = live.flourish,
            officialAlertCityScope = prefs.officialAlertCityScope
        ).copy(
            update = updateUi.update,
            needsInstallPermission = updateUi.needsInstallPermission,
            latestVersion = updateUi.latestVersion,
            disclaimerCollapsed = prefs.disclaimerCollapsed,
            disclaimerReadCount = prefs.disclaimerReadCount,
            slowRedArmed = prefs.slowRedArmed,
            slowYellowArmed = prefs.slowYellowArmed,
            fastRedArmed = prefs.fastRedArmed,
            fastYellowArmed = prefs.fastYellowArmed,
            officialAlertsEnabled = prefs.officialAlertsEnabled,
            officialAlertCityScope = prefs.officialAlertCityScope,
            sirenOverride = prefs.sirenOverride,
            criticalOfflineOverride = prefs.criticalOfflineOverride,
            criticalOfflineBypassSilent = prefs.criticalOfflineBypassSilent,
            activeZoneParams = effectiveParams,
            activeSlowRedArmed = activeArmed.slowRed,
            activeSlowYellowArmed = activeArmed.slowYellow,
            activeFastRedArmed = activeArmed.fastRed,
            activeFastYellowArmed = activeArmed.fastYellow,
            nightActive = nightActive,
            nightWindowText = if (prefs.night.window.enabled) {
                nightWindowText(prefs.night.window.startMin, prefs.night.window.endMin)
            } else "",
            nightEnabled = prefs.night.window.enabled,
            nightStartMin = prefs.night.window.startMin,
            nightEndMin = prefs.night.window.endMin,
            nightUseCustomZones = prefs.night.window.useCustomZones,
            nightSlowRedKm = prefs.night.zones.slowRedKm,
            nightSlowYellowKm = prefs.night.zones.slowYellowKm,
            nightFastRedMin = prefs.night.zones.fastRedMin,
            nightFastYellowMin = prefs.night.zones.fastYellowMin,
            nightSlowRedArmed = prefs.night.zones.slowRedArmed,
            nightSlowYellowArmed = prefs.night.zones.slowYellowArmed,
            nightFastRedArmed = prefs.night.zones.fastRedArmed,
            nightFastYellowArmed = prefs.night.zones.fastYellowArmed,
            nightZoneSirenOverride = prefs.night.zones.zoneSirenOverride,
            nightOfficialSirenOverride = prefs.night.zones.officialSirenOverride,
            wizardCompleted = prefs.wizardCompleted,
            batteryOnboardShown = prefs.batteryOnboardShown,
            threatCardSize = prefs.cardSize,
            iconSet = prefs.iconSet,
            showMapScale = prefs.showMapScale,
            showMediumCities = prefs.showMediumCities,
            showSmallCities = prefs.showSmallCities,
            justFunMasterEnabled = prefs.justFunMasterEnabled,
            deathAnimationEnabled = prefs.deathAnimationEnabled,
            flybyAnimationEnabled = prefs.flybyAnimationEnabled,
            followBullet = prefs.followBullet,
            neutralizedTallyEnabled = prefs.neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = prefs.neutralizedTallyAllUkraine,
            fastGroupCollapsed = prefs.fastGroupCollapsed,
            slowGroupCollapsed = prefs.slowGroupCollapsed,
            sheltersEnabled = prefs.sheltersEnabled,
            sheltersWithKids = prefs.sheltersWithKids,
            periodicGps = prefs.periodicGps,
            calmMessagesEnabled = prefs.calmMessagesEnabled,
            hapticsEnabled = resolveHaptics(prefs.hapticsEnabled),
            shelterIndex = shelterIndex,
            shelterOverlayUp = live.shelterModeActive,
            notificationsDisabledBySystem = !AlertNotificationManager.areNotificationsEnabled(app)
        )
        // A fresh INNER AVIATION (bell on) plays one full-size pass across the viewport; the
        // threat card opens when it lands (onFlybyFinished). Only while genuinely foregrounded
        // — a user away from the phone gets the flyby on notification-tap reveal instead.
        val autoFlyby = AviationFlyby.nextShow(
            uiState.threatsInner, flybyPlayedIds,
            live.mapVisible && appForegroundFlow.value, flybyTick + 1
        )
        if (autoFlyby != null) {
            flybyTick++
            flybyPlayedIds.add(autoFlyby.threatId)
            val threat = threatsFlow.value[autoFlyby.threatId]!!
            val durationMs = calculateFlybyDuration(threat)
            flybyFlow.value = AviationFlybyShow(autoFlyby.tick, autoFlyby.threatId, autoFlyby.courseDeg, durationMs)
        }
        uiState.copy(flyby = flyby)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UiState()
        )

    private data class SelectionInput(
        val selected: NormalizedThreat?,
        val neutralizedId: String?,
        val fakeNeutralize: Boolean,
        val mapVisible: Boolean,
        val shelterOverlayUp: Boolean
    )

    private val selectionInput = combine(
        selectedThreatFlow,
        neutralizedFlow,
        fakeNeutralizeFlow,
        mapVisibleFlow,
        shelterModeFlow
    ) { selected, neutralizedId, fake, mapVisible, shelterUp ->
        SelectionInput(selected, neutralizedId, fake, mapVisible, shelterUp)
    }

    /**
     * Popup state, derived cheaply per selection change (one map lookup + one proximity
     * computation — the expensive evaluate() is NOT re-run). Re-derived on ambient UiState
     * changes too, so focus/params/policy flags always match what the rest of the UI shows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectionUi: StateFlow<SelectionUi> = selectionInput.flatMapLatest { sel ->
        uiState.map { ui ->
            val animOn = ui.deathAnimationEnabled
            // Keep the selected threat pointer fresh (position/status may have updated)
            val refreshed = sel.selected?.let { s -> threatsFlow.value[s.id] }
            val nowMs = System.currentTimeMillis()
            // The selected threat is gone (removed by the server, marked resolved/area-only, a
            // ghost past the hard cap, or long-pressed) — show a brief neutralized card.
            val selectedGone = sel.selected != null && (
                (refreshed?.let { t ->
                    t.status == "resolved" || engine.isGhost(t, engine.propsFor(t.type), nowMs)
                } ?: true) ||
                    sel.selected.id == sel.neutralizedId
                )
            // With the death animation disabled the card never flips to the "Neutralized"
            // compact form nor auto-dismisses; the flourish only runs while the map is visible
            // and the shelter overlay is down (identical gating to the pre-split logic).
            val neutralizedThreat =
                if (FlourishPolicy.showNeutralizedCard(selectedGone, animOn, sel.mapVisible, sel.shelterOverlayUp)) sel.selected else null
            SelectionUi(
                selected = if (FlourishPolicy.dropSelection(selectedGone, animOn)) null else refreshed,
                proximity = refreshed?.let { t ->
                    engine.computeProximity(
                        t,
                        ui.focusLocation?.let { loc -> LatLng(loc.lat, loc.lon) },
                        ZoneParams(ui.activeZoneParams.slowRedKm, ui.activeZoneParams.slowYellowKm, ui.activeZoneParams.fastRedMin, ui.activeZoneParams.fastYellowMin),
                        nowMs
                    )
                }?.let { ep ->
                    ThreatProximity(
                        predicted = ep.predicted,
                        distToUserKm = ep.distToUserKm,
                        etaToUserMin = ep.etaToUserMin,
                        params = ui.activeZoneParams,
                        speedSource = ep.speedSource,
                        speedKmh = ep.speedKmh
                    )
                },
                neutralized = neutralizedThreat,
                fakeNeutralize = sel.fakeNeutralize
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SelectionUi()
    )

    /** Marker highlight feed for the map — selection without dragging the whole popup object around. */
    val selectedThreatId: StateFlow<String?> = selectionUi
        .map { it.selected?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun buildUiState(
        cs: ConnectionState,
        threats: Map<String, NormalizedThreat>,
        alerts: List<OblastAlert>,
        threatDataStale: Boolean,
        slowRedKm: Int,
        slowYellowKm: Int,
        fastRedMin: Int,
        fastYellowMin: Int,
        effectiveParams: ZoneParams,
        mapEnabledTypes: Set<ThreatType>,
        alertedTypes: Set<ThreatType>,
        language: AppLanguage,
        userLocation: LatLng?,
        gpsFixAvailable: Boolean,
        followMe: Boolean,
        pinnedCity: City?,
        now: Long,
        reveal: RevealRequest?,
        flourish: FlourishShow?,
        officialAlertCityScope: Boolean
    ): UiState {
        val params = effectiveParams
        val gpsFresh = LocationTracker.isFresh(now)
        val effectiveUserLoc = if (gpsFresh) userLocation else null
        // Camera + zone center: GPS while following (if fresh), else the pinned city (else GPS fallback).
        // Before the first GPS fix the map still needs a complete first visual, so it anchors on
        // Odesa (where the shelter data lives) until a real fix recentres it.
        val focusLocation = if (followMe) (effectiveUserLoc ?: ODESA_FALLBACK_FOCUS)
        else pinnedCity?.let { LatLng(it.lat, it.lon) } ?: (effectiveUserLoc ?: ODESA_FALLBACK_FOCUS)
        // Official alert state for the FOCUS point: the pinned city's oblast, else the
        // oblast of the nearest listed city to the GPS fix while following.
        val attribution = focusAttribution(followMe, effectiveUserLoc, pinnedCity)
        val focusToken = attribution.token
        val focusOblastAlertActive = officialAlertActiveFor(
            alerts,
            focusToken,
            attribution.bannerCityUa.takeIf { it.isNotBlank() },
            officialAlertCityScope
        )
        val focusBannerCity = (
            if (language == AppLanguage.UA) attribution.bannerCityUa else attribution.bannerCityEn
        ).ifBlank { Strings.get(language).unknownLocation }
        // Oblasts with an official alert: a city label turns red when its oblast is listed.
        val activeRegionTokens = buildSet {
            for (citiesToken in Cities.cityOblast.values) {
                if (alerts.any { it.inOblast(citiesToken) }) add(citiesToken)
            }
        }
        // Cities shown red on the map/picker. Oblast scope (default): any city in an alerting
        // oblast. City scope: only the cities the alert actually covers by name — the shared
        // officialAlertActiveFor gate, so the map agrees with the banner/notifications.
        val redCities = buildSet {
            for (city in Cities.ALL) {
                val token = Cities.cityOblast[city.nameUa] ?: continue
                val covered = if (officialAlertCityScope) {
                    officialAlertActiveFor(alerts, token, city.nameUa, scope = true)
                } else token in activeRegionTokens
                if (covered) add(city.nameUa)
            }
        }

        val threatList = if (threatDataStale) emptyList() else threats.values
            .filter { it.type.toThreatType() in mapEnabledTypes }
        val silencedTypeStrings = alertedTypes.map { it.toEngineString() }.toSet()
        val engineFocus = focusLocation?.let { LatLng(it.lat, it.lon) }
        val engineParams = ZoneParams(params.slowRedKm, params.slowYellowKm, params.fastRedMin, params.fastYellowMin)
        val evaluation = engine.evaluate(
            threats = threatList,
            focus = engineFocus,
            params = engineParams,
            hiddenTypes = emptySet(),
            silencedTypes = silencedTypeStrings,
            now = now
        )
        val inInner = evaluation.threatsInner
        val inOuter = evaluation.threatsOuter
        val mapThreats = evaluation.mapThreats
        val threatScores = evaluation.threatScores

        val activeZone: ThreatZone? = evaluation.activeZone
        val alertActive = activeZone != null || focusOblastAlertActive

        // Short socket blips (drops that recover inside the shared grace window) are invisible
        // here — the pill and status text stay "online" instead of flashing on every handoff.
        // The grace is only applied in the connection log; the UI pill immediately reflects
        // drops so the header and the notification service agree (mirror rule).
        val neptunDown = cs.isOffline

        return UiState(
            connected = cs.isConnected,
            neptunDown = neptunDown,
            degraded = cs.isDegraded,
            threatsInner = inInner,
            threatsOuter = inOuter,
            mapThreats = mapThreats,
            userLocation = userLocation,
            gpsFixAvailable = gpsFixAvailable && gpsFresh,
            slowRedKm = slowRedKm,
            slowYellowKm = slowYellowKm,
            fastRedMin = fastRedMin,
            fastYellowMin = fastYellowMin,
            hiddenTypes = ThreatType.values().toSet() - mapEnabledTypes,
            silencedTypes = ThreatType.values().toSet() - alertedTypes,
            activeZone = activeZone,
            focusOblastAlertActive = focusOblastAlertActive,
            focusBannerCity = focusBannerCity,
            language = language,
            followMe = followMe,
            pinnedCity = pinnedCity,
            focusLocation = focusLocation,
            redCities = redCities,
            threatLevel = evaluation.threatLevel,
            revealRequest = reveal,
            flourish = flourish,
            alertActive = alertActive,
            threatDataStale = threatDataStale
        )
    }

    /** Localized "22:00–07:00" label for the configured night window. */
    private fun nightWindowText(startMin: Int, endMin: Int): String =
        "${timeText(startMin)}–${timeText(endMin)}"

    private fun timeText(min: Int): String =
        String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60)

    fun setSlowRedKm(km: Int) {
        viewModelScope.launch { prefs.setSlowRedKm(km) }
    }

    fun setSlowYellowKm(km: Int) {
        viewModelScope.launch { prefs.setSlowYellowKm(km) }
    }

    fun setFastRedMin(min: Int) {
        viewModelScope.launch { prefs.setFastRedMin(min) }
    }

    fun setFastYellowMin(min: Int) {
        viewModelScope.launch { prefs.setFastYellowMin(min) }
    }

    fun setSlowRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setSlowRedZoneArmed(armed)
        }
    }

    fun setSlowYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setSlowYellowZoneArmed(armed)
        }
    }

    fun setFastRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setFastRedZoneArmed(armed)
        }
    }

    fun setFastYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setFastYellowZoneArmed(armed)
        }
    }

    /** Master alarm switch: arms or silences all four zone bells together. */
    fun setAlertsArmed(armed: Boolean) {
        viewModelScope.launch {
            prefs.setMonitoringEnabled(armed)
            if (armed) AlertService.start(app)
            prefs.setSlowRedZoneArmed(armed)
            prefs.setSlowYellowZoneArmed(armed)
            prefs.setFastRedZoneArmed(armed)
            prefs.setFastYellowZoneArmed(armed)
        }
    }

    fun setOfficialAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfficialAlertsEnabled(enabled) }
    }

    fun setOfficialAlertCityScope(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfficialAlertCityScope(enabled) }
    }

    fun setSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setSirenOverride(override) }
    }

    fun setCriticalOfflineOverride(enabled: Boolean) {
        viewModelScope.launch { prefs.setCriticalOfflineOverride(enabled) }
    }

    fun setCriticalOfflineBypassSilent(enabled: Boolean) {
        viewModelScope.launch { prefs.setCriticalOfflineBypassSilent(enabled) }
    }

    fun setBatteryOnboardShown(shown: Boolean) {
        viewModelScope.launch { prefs.setBatteryOnboardShown(shown) }
    }

    fun setNightEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNightEnabled(enabled) }
    }

    fun setNightStartMin(min: Int) {
        viewModelScope.launch { prefs.setNightStartMin(min) }
    }

    fun setNightEndMin(min: Int) {
        viewModelScope.launch { prefs.setNightEndMin(min) }
    }

    fun setNightUseCustomZones(use: Boolean) {
        viewModelScope.launch { prefs.setNightUseCustomZones(use) }
    }

    fun setNightSlowRedKm(km: Int) {
        viewModelScope.launch { prefs.setNightSlowRedKm(km) }
    }

    fun setNightSlowYellowKm(km: Int) {
        viewModelScope.launch { prefs.setNightSlowYellowKm(km) }
    }

    fun setNightFastRedMin(min: Int) {
        viewModelScope.launch { prefs.setNightFastRedMin(min) }
    }

    fun setNightFastYellowMin(min: Int) {
        viewModelScope.launch { prefs.setNightFastYellowMin(min) }
    }

    fun setNightSlowRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setNightSlowRedZoneArmed(armed)
        }
    }

    fun setNightSlowYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setNightSlowYellowZoneArmed(armed)
        }
    }

    fun setNightFastRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setNightFastRedZoneArmed(armed)
        }
    }

    fun setNightFastYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { prefs.setMonitoringEnabled(true); AlertService.start(app) }
            prefs.setNightFastYellowZoneArmed(armed)
        }
    }

    fun setNightZoneSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setNightZoneSirenOverride(override) }
    }

    fun setNightOfficialSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setNightOfficialSirenOverride(override) }
    }

    fun setSheltersEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSheltersEnabled(enabled) }
    }

    fun setSheltersWithKidsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSheltersWithKidsEnabled(enabled) }
    }

    /** Tracks which screen is visible so map-only work (neutralizing animation, death
     *  flourish) can be skipped while the map is covered by Settings/Shelters/Guide. */
    fun setMapVisible(visible: Boolean) {
        mapVisibleFlow.value = visible
    }

    /** Tracks whether the shelter overlay is up so the resolved-threat flourish and
     *  neutralizing card are suppressed while it is. */
    fun setShelterModeActive(active: Boolean) {
        shelterModeFlow.value = active
    }

    /** Loads the bundled Odesa shelter snapshot, then refreshes it from the update server daily. */
    private suspend fun loadShelters() {
        val context = getApplication<Application>()
        val bundle = runCatching {
            context.resources.openRawResource(R.raw.odesa_shelters).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()?.let { ShelterIndex.fromJson(it) }
        val cacheFile = File(context.filesDir, SHELTERS_CACHE_FILE)
        val cache = if (cacheFile.exists()) ShelterIndex.fromJson(cacheFile.readText()) else null
        shelterIndexFlow.value = cache ?: bundle
        if (cache == null || cacheFile.lastModified() < System.currentTimeMillis() - DAILY_CHECK_INTERVAL_MS) {
            val fresh = updateManager.fetchSheltersJson()
            if (fresh != null) {
                ShelterIndex.fromJson(fresh)?.let {
                    shelterIndexFlow.value = it
                    cacheFile.writeText(fresh)
                }
            }
        }
    }

    /** Follow-me toggle: switching it back on resumes GPS-centered zones/camera. */
    fun setFollowMe(follow: Boolean) {
        viewModelScope.launch { prefs.setFollowMe(follow) }
    }

    /** Periodic 15-min GPS sync toggle to prevent cell-tower drift. */
    fun setPeriodicGps(enabled: Boolean) {
        viewModelScope.launch { prefs.setPeriodicGps(enabled) }
    }

    /** Footer calm-messages toggle (rotating encouragements when no threats are around). */
    fun setCalmMessagesEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCalmMessagesEnabled(enabled) }
    }

    /** Haptic press-feedback toggle. */
    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setHapticsEnabled(enabled) }
    }

    /** Manual one-shot GPS calibration/refresh trigger. */
    fun forceGpsRefresh(onComplete: (() -> Unit)? = null) {
        LocationTracker.forceRefresh(onComplete)
    }

    /** Pin the map to a city. Pinning auto-disables follow-me so the pin takes effect. */
    fun setPinnedCity(city: City?) {
        viewModelScope.launch {
            prefs.setPinnedCity(city?.nameUa)
            if (city != null) prefs.setFollowMe(false)
        }
    }

    fun setThreatMapVisible(type: ThreatType, visible: Boolean) {
        viewModelScope.launch {
            prefs.setThreatMapVisible(type, visible)
            maybeShowToggleHint(mapToast = true)
        }
    }

    fun setThreatAlertsEnabled(type: ThreatType, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setThreatAlertsEnabled(type, enabled)
            if (enabled) prefs.setThreatMapVisible(type, true)
            maybeShowToggleHint(mapToast = false)
        }
    }

    /** Wizard grid: one tap enables/disables a threat type for the map AND alerts together. */
    fun setThreatEnabled(type: ThreatType, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setThreatMapVisible(type, enabled)
            prefs.setThreatAlertsEnabled(type, enabled)
        }
    }

    fun setGroupThreatMapVisible(types: Set<ThreatType>, visible: Boolean) {
        viewModelScope.launch {
            types.forEach { prefs.setThreatMapVisible(it, visible) }
            maybeShowToggleHint(mapToast = true)
        }
    }

    fun setGroupThreatAlertsEnabled(types: Set<ThreatType>, enabled: Boolean) {
        viewModelScope.launch {
            types.forEach {
                prefs.setThreatAlertsEnabled(it, enabled)
                if (enabled) prefs.setThreatMapVisible(it, true)
            }
            maybeShowToggleHint(mapToast = false)
        }
    }

    /** One-time hint (first 3 Map/Alerts toggles ever): a brief toast explaining how they work. */
    private suspend fun maybeShowToggleHint(mapToast: Boolean) {
        val remaining = prefs.threatToggleHintRemaining().first()
        if (remaining <= 0) return
        prefs.setThreatToggleHintRemaining(remaining - 1)
        val s = Strings.get(prefs.language().first())
        val prefix = if (mapToast) s.mapToggleHintPrefix else s.alertToggleHintPrefix
        val rest = if (mapToast) s.mapToggleHintRest else s.alertToggleHintRest
        val message = SpannableString(prefix + rest)
        message.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        showToast(
            getApplication(),
            message,
            cardVisible = uiState.value.mapVisible && selectionUi.value.selected != null
        )
    }

    /** One-time hint (first 3 ejections ever): a modal/background cut a running or queued
     *  shoot-down show — tell the user it will wait until they're back on the map. */
    fun notifyFlourishEjected() {
        viewModelScope.launch {
            val remaining = prefs.flourishEjectHintRemaining().first()
            if (remaining <= 0) return@launch
            prefs.setFlourishEjectHintRemaining(remaining - 1)
            val s = Strings.get(prefs.language().first())
            showToast(getApplication(), s.flourishEjectToast, cardVisible = false)
        }
    }

    fun setDisclaimerCollapsed(collapsed: Boolean) {
        viewModelScope.launch { prefs.setDisclaimerCollapsed(collapsed) }
    }

    fun onDisclaimerShown() {
        viewModelScope.launch {
            prefs.disclaimerReadCount().first().let { count ->
                if (count < 3) prefs.setDisclaimerReadCount(count + 1)
            }
        }
    }

    fun setThreatCardSize(size: ThreatCardSize) {
        viewModelScope.launch { prefs.setThreatCardSize(size) }
    }

    fun setThreatIconSet(set: ThreatIconSet) {
        viewModelScope.launch { prefs.setThreatIconSet(set) }
    }

    fun setShowMediumCities(show: Boolean) {
        viewModelScope.launch { prefs.setShowMediumCities(show) }
    }

    fun setShowSmallCities(show: Boolean) {
        viewModelScope.launch { prefs.setShowSmallCities(show) }
    }

    fun setFastGroupCollapsed(collapsed: Boolean) {
        viewModelScope.launch { prefs.setFastGroupCollapsed(collapsed) }
    }

    fun setSlowGroupCollapsed(collapsed: Boolean) {
        viewModelScope.launch { prefs.setSlowGroupCollapsed(collapsed) }
    }

    fun setShowMapScale(show: Boolean) {
        viewModelScope.launch { prefs.setShowMapScale(show) }
    }

    fun setDeathAnimationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setDeathAnimationEnabled(enabled) }
    }

    /** Master "Just Fun" switch: gates visibility of the Just Fun settings panel.
     *  Does NOT change individual settings — only toggles the panel visibility. */
    fun setJustFunEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setJustFunMasterEnabled(enabled)
        }
    }

    fun setFlybyAnimationEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setFlybyAnimationEnabled(enabled) }
    }

    fun setFollowBullet(enabled: Boolean) {
        viewModelScope.launch { prefs.setFollowBullet(enabled) }
    }

    fun setNeutralizedTallyEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNeutralizedTallyEnabled(enabled) }
    }

    fun setNeutralizedTallyAllUkraine(enabled: Boolean) {
        viewModelScope.launch { prefs.setNeutralizedTallyAllUkraine(enabled) }
    }

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch { prefs.setLanguage(lang) }
    }

    /** Wizard finished (Done or Skip): language picked, setup complete. */
    fun skipLanguageChoose() {
        viewModelScope.launch {
            prefs.setLanguageChosen(true)
            prefs.setWizardCompleted(true)
        }
    }

    /** Tapped "Later" on the first-run wizard — exit all setup chrome for this session:
     *  mark setup complete, skip the battery prompt, and defer the location/notification
     *  permission requests until the next cold start. */
    fun laterLanguageChoose() {
        viewModelScope.launch {
            prefs.setLanguageChosen(true)
            prefs.setWizardCompleted(true)
            prefs.setBatteryOnboardShown(true)
            prefs.setPermissionPromptDeferred(true)
        }
    }

    /** Re-open the first-run setup (language, icon pack, alert groups, feature tour + battery
     *  prompt). Only flips the onboarding-completed flags — no setting is reset. Clears only
     *  wizard_completed so a kill mid-replay doesn't resurrect the wizard on every cold start. */
    fun relaunchSetup() {
        viewModelScope.launch {
            prefs.setWizardCompleted(false)
            prefs.setBatteryOnboardShown(false)
            prefs.setPermissionPromptDeferred(false)
        }
    }

    fun resetAllTips() {
        viewModelScope.launch { prefs.resetAllTips() }
    }

    fun selectThreat(threat: NormalizedThreat?) {
        if (BuildConfig.DEBUG) android.util.Log.d("PerfTrace", "tap selectThreat ${threat?.id} t=${System.currentTimeMillis()}")
        neutralizedFlow.value = null
        fakeNeutralizeFlow.value = false
        selectedThreatFlow.value = threat
    }

    /** Flyby landed: unmount the overlay (the contrail must not linger) and open the threat
     *  card with the takeoff's details (no camera pan). */
    fun onFlybyFinished(threatId: String?) {
        flybyFlow.value = null
        val t = threatId?.let { threatsFlow.value[it] } ?: return
        selectThreat(t)
    }

    /** Treat [id] as neutralized so its card self-destructs (map long-press trigger). */
    fun neutralizeThreat(id: String) {
        neutralizedFlow.value = id
        fakeNeutralizeFlow.value = true
    }

    /** Whether the app process is foregrounded — drives the flyby auto-trigger gate. */
    fun setAppForeground(foreground: Boolean) {
        appForegroundFlow.value = foreground
    }

    /** Calculates flyby duration based on distance to threat (capped 1.5–8 s). */
    private fun calculateFlybyDuration(threat: NormalizedThreat): Long {
        val focus = uiState.value.focusLocation
            ?: return AVIATION_FLYBY_DURATION_MS
        val distKm = distanceFlat(
            focus.lat, focus.lon, threat.lat, threat.lon
        ) / 1000.0
        // MiG-31 cruise ~900 km/h; scale for visibility (1.5x), clamp 1.5–8 s
        val durationSec = (distKm / 900.0) * 3600.0 * 1.5
        return (durationSec * 1000).roundToLong().coerceIn(1500, 8000)
    }

    /** Notification tap (or any intent): navigate to the map screen regardless of current tab. */
    fun navigateToMap() {
        navigateToMapCounter++
        _navigateToMapFlow.value = navigateToMapCounter
    }

    /**
     * A notification tap carrying the triggering threat's id/position: select it so the
     * popup opens, then ask the map to pan the camera onto it. Best-effort selection — on a
     * cold start the stream may not have the threat yet, and the pan still works from the
     * coordinates carried in the intent. With [select] false the camera pans without opening
     * the popup (footer strip taps).
     */
    fun revealThreat(id: String?, lat: Double, lon: Double, select: Boolean = true) {
        revealTick++
        neutralizedFlow.value = null
        val threat = id?.let { threatsFlow.value[it] }
        val aviation = threat?.type?.toThreatType() == ThreatType.AVIATION
        // A MiG-31K tap greets with a full flyby pass — every press, fresh random bearing —
        // and the card only opens when the jet is gone (onFlybyFinished), so selection is
        // deferred here. Marking it played also suppresses the live auto-trigger for the id.
        if (aviation && id != null) {
            flybyPlayedIds.add(id)
            flybyTick++
            val threat = threatsFlow.value[id]!!
            val durationMs = calculateFlybyDuration(threat)
            flybyFlow.value = AviationFlybyShow(flybyTick, id, Random.nextDouble(0.0, 360.0), durationMs)
        } else if (select && id != null) {
            selectedThreatFlow.value = threat
        }
        revealFlow.value = RevealRequest(revealTick, id, lat, lon)
    }

    /** Tally-tap replay: ask the map to shoot down the remembered resolutions in sequence. */
    fun triggerFlourish(records: List<FlourishRecord>) {
        flourishTick++
        flourishFlow.value = FlourishShow(flourishTick, records)
    }

    /**
     * Footer strip tap: pan the camera onto [t]'s dead-reckoned position (where its marker
     * actually sits) and open its popup card.
     */
    fun panToThreat(t: NormalizedThreat) {
        val now = System.currentTimeMillis()
        val nt = t
        val props = engine.propsFor(nt.type)
        val speed = engine.speedCache.estimate(nt.id, nt, props)
        val predicted = speed?.let { engine.predictPosition(nt, it, props, now) }
            ?: LatLng(t.lat, t.lon)
        revealThreat(t.id, predicted.lat, predicted.lon, select = true)
    }

    /** Auto-check at most once per day. [allowPopup] pops the dialog on start when no alert is active. */
    fun autoCheckForUpdates(allowPopup: Boolean) {
        viewModelScope.launch {
            val lastCheck = svcState.lastUpdateCheck().first()
            if (System.currentTimeMillis() - lastCheck >= DAILY_CHECK_INTERVAL_MS) {
                checkForUpdates(notify = false, popupAvailable = allowPopup, popupOnlyWithoutAlert = allowPopup)
            }
        }
    }

    /**
     * Every Settings open: if an update is already known, just re-raise the "update available"
     * reminder (no network hit); otherwise check silently and remind when one turns up. The
     * reminder is surfaced by MainScreen as a snackbar with a Download action.
     */
    fun checkForUpdatesOnSettingsOpen() {
        if (latestVersionFlow.value != null) {
            updateReminderFlow.value++
        } else {
            checkForUpdates(notify = false, popupAvailable = false, remindOnAvailable = true)
        }
    }

    /** Tap on the update reminder: pop the download dialog for the last known version. */
    fun showDownloadScreen() {
        lastAvailableUpdate?.let { updateStateFlow.value = UpdateState.Available(it) }
    }

    /** True when any threat or official alert is currently active — the update dialog stays hidden then. */
    private fun hasActiveAlert(): Boolean = uiState.value.alertActive

    fun checkForUpdates(notify: Boolean = true, popupAvailable: Boolean = true, popupOnlyWithoutAlert: Boolean = false, remindOnAvailable: Boolean = false) {
        if (isChecking) return
        val current = updateStateFlow.value
        if (current is UpdateState.Downloading || current is UpdateState.Downloaded) return
        isChecking = true
        viewModelScope.launch {
            updateStateFlow.value = UpdateState.Checking
            val result = updateManager.check()
            isChecking = false
            svcState.setLastUpdateCheck(System.currentTimeMillis())
            val s = Strings.get(prefs.language().first())
            when (result) {
                is UpdateState.Available -> {
                    lastAvailableUpdate = result.info
                    latestVersionFlow.value = result.info.versionName
                    val showDialog = popupAvailable && (!popupOnlyWithoutAlert || !hasActiveAlert())
                    updateStateFlow.value = if (showDialog) result else UpdateState.Idle
                    if (remindOnAvailable) updateReminderFlow.value++
                }
                is UpdateState.UpToDate -> {
                    latestVersionFlow.value = null
                    updateStateFlow.value = UpdateState.Idle
                    if (notify) {
                        showToast(getApplication(), s.updateUpToDate, cardVisible = false)
                    }
                }
                is UpdateState.Failed -> {
                    updateStateFlow.value = UpdateState.Idle
                    if (notify) {
                        val message = result.message?.let { ": $it" }.orEmpty()
                        showToast(getApplication(), s.updateCheckFailed + message, cardVisible = false)
                    }
                }
                else -> Unit
            }
        }
    }

    fun downloadUpdate() {
        val current = updateStateFlow.value as? UpdateState.Available ?: return
        viewModelScope.launch {
            try {
                val file = updateManager.download(current.info) { progress ->
                    updateStateFlow.value = UpdateState.Downloading(current.info, progress)
                }
                updateStateFlow.value = UpdateState.Downloaded(current.info, file)
            } catch (e: Exception) {
                updateStateFlow.value = UpdateState.Failed(e.message)
            }
        }
    }

    /** Builds the installer intent, or null when the "install unknown apps" permission is missing. */
    fun installIntent(): Intent? {
        val current = updateStateFlow.value as? UpdateState.Downloaded ?: return null
        val intent = updateManager.buildInstallIntent(current.file)
        installPermissionFlow.value = intent == null
        return intent
    }

    fun onInstallResult(canceled: Boolean) {
        if (canceled) {
            updateStateFlow.value = UpdateState.Idle
            installPermissionFlow.value = false
        }
    }

    fun openInstallPermissionSettings() {
        updateManager.openInstallPermissionSettings()
    }

    fun retryDownload() {
        viewModelScope.launch {
            updateStateFlow.value = UpdateState.Checking
            when (val result = updateManager.check()) {
                is UpdateState.Available -> {
                    updateStateFlow.value = result
                    downloadUpdate()
                }
                else -> updateStateFlow.value = result
            }
        }
    }

    fun dismissUpdate() {
        updateStateFlow.value = UpdateState.Idle
    }
}
