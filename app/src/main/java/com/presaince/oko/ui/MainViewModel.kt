package com.presaince.oko
import com.presaince.oko.engine.distanceFlat

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
import com.presaince.oko.connection.Monotonic
import com.presaince.oko.AlertNotificationManager
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.OblastAlert
import com.presaince.oko.engine.inOblast
import com.presaince.oko.engine.canonicalOblastId
import com.presaince.oko.engine.ThreatZone
import com.presaince.oko.AlertService
import com.presaince.oko.DebugLog
import com.presaince.oko.DebugLogKind
import com.presaince.oko.DebugLogReason
import com.presaince.oko.DigestWindow
import com.presaince.oko.NotifyPlugin
import com.presaince.oko.NotifyPrefs
import com.presaince.oko.ZonePolicy
import com.presaince.oko.engine.AlertLevel
import com.presaince.oko.engine.toEngineString
import com.presaince.oko.engine.toThreatType
import com.presaince.oko.engine.SpeedSource
import com.presaince.oko.engine.ZoneParams
import com.presaince.oko.service.ServiceState
import com.presaince.oko.service.MonitoringStatus
import com.presaince.oko.ShelterIndex
import com.presaince.oko.UpdateManager
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

enum class ProtectionState {
    ACTIVE,
    REDUCED,
    OFFLINE
}

@Immutable
data class UiState(
    val connected: Boolean = false,
    val neptunDown: Boolean = false,                 // NEPTUN offline
    val degraded: Boolean = false,                   // connected but stream quiet (orange pill)
    val monitoringRunning: Boolean = true,           // AlertService foreground monitor alive
    val bootRestartEnabled: Boolean = true,          // Settings: restart monitoring after reboot
    val threatsInner: List<NormalizedThreat> = emptyList(), // reaching within the red time tier
    val threatsOuter: List<NormalizedThreat> = emptyList(), // in the yellow time tier, beyond red
    val mapThreats: List<NormalizedThreat> = emptyList(),   // all active threats across Europe
    val userLocation: LatLng? = null,
    val gpsFixAvailable: Boolean = false,         // a GPS/cell fix has arrived at least once
    val gpsFixMissing: Boolean = false,           // followMe on, no fix ever → persistent warning
    val slowRedKm: Int = UserPreferences.DEFAULT.slowRedKm,      // slow threats: distance to the red (inner) zone, km
    val slowYellowKm: Int = UserPreferences.DEFAULT.slowYellowKm,  // slow threats: distance to the yellow (outer) zone, km
    val fastRedMin: Int = UserPreferences.DEFAULT.fastRedMin,     // fast threats: ETA to the red (inner) zone, minutes
    val fastYellowMin: Int = UserPreferences.DEFAULT.fastYellowMin,  // fast threats: ETA to the yellow (outer) zone, minutes
    val slowRedArmed: Boolean = UserPreferences.DEFAULT.slowRedArmed,
    val slowYellowArmed: Boolean = UserPreferences.DEFAULT.slowYellowArmed,
    val fastRedArmed: Boolean = UserPreferences.DEFAULT.fastRedArmed,
    val fastYellowArmed: Boolean = UserPreferences.DEFAULT.fastYellowArmed,
    val activeZoneParams: ZoneParams = ZoneParams(
        UserPreferences.DEFAULT.slowRedKm,
        UserPreferences.DEFAULT.slowYellowKm,
        UserPreferences.DEFAULT.fastRedMin,
        UserPreferences.DEFAULT.fastYellowMin
    ), // effective (night-aware) thresholds
    val activeSlowRedArmed: Boolean = UserPreferences.DEFAULT.slowRedArmed,
    val activeSlowYellowArmed: Boolean = UserPreferences.DEFAULT.slowYellowArmed,
    val activeFastRedArmed: Boolean = UserPreferences.DEFAULT.fastRedArmed,
    val activeFastYellowArmed: Boolean = UserPreferences.DEFAULT.fastYellowArmed,
    val nightActive: Boolean = false,                    // night window currently in effect
    val nightWindowText: String = "",                    // localized "22:00–07:00" when configured
    val nightEnabled: Boolean = UserPreferences.DEFAULT.nightEnabled,
    val nightStartMin: Int = UserPreferences.DEFAULT.nightStartMin,
    val nightEndMin: Int = UserPreferences.DEFAULT.nightEndMin,
    val nightUseCustomZones: Boolean = UserPreferences.DEFAULT.nightUseCustomZones,
    val nightSlowRedKm: Int = UserPreferences.DEFAULT.nightSlowRedKm,
    val nightSlowYellowKm: Int = UserPreferences.DEFAULT.nightSlowYellowKm,
    val nightFastRedMin: Int = UserPreferences.DEFAULT.nightFastRedMin,
    val nightFastYellowMin: Int = UserPreferences.DEFAULT.nightFastYellowMin,
    val nightSlowRedArmed: Boolean = UserPreferences.DEFAULT.nightSlowRedArmed,
    val nightSlowYellowArmed: Boolean = UserPreferences.DEFAULT.nightSlowYellowArmed,
    val nightFastRedArmed: Boolean = UserPreferences.DEFAULT.nightFastRedArmed,
    val nightFastYellowArmed: Boolean = UserPreferences.DEFAULT.nightFastYellowArmed,
    val nightZoneSirenOverride: Boolean = UserPreferences.DEFAULT.nightZoneSirenOverride,
    val nightOfficialSirenOverride: Boolean = UserPreferences.DEFAULT.nightOfficialSirenOverride,
    val officialRedAlertsEnabled: Boolean = true,
    val officialYellowAlertsEnabled: Boolean = true,
     val officialAlertCityScope: Boolean = false,
     val nightOfficialAlertCityScope: Boolean = false,
     val sirenOverride: Boolean = false,
    val criticalOfflineOverride: Boolean = true,
    val criticalOfflineBypassSilent: Boolean = false,
    val hiddenTypes: Set<ThreatType> = emptySet(),      // hidden from the map
    val silencedTypes: Set<ThreatType> = emptySet(),    // alerts off (still on the map, dimmed)
    val activeZone: ThreatZone? = null,           // most specific zone with a threat
    val focusOblastAlertActive: Boolean = false,  // official alert on the focus point's oblast
    val focusOblastYellowAlertActive: Boolean = false, // yellow-level official alert on the focus point's oblast
    val focusBannerCity: String = "",             // localized city name for the alert banner
    val language: AppLanguage = AppLanguage.EN,
    val followMe: Boolean = true,
    val pinnedCity: City? = null,
    val focusLocation: LatLng? = null,            // camera + zone center: GPS (follow) or pinned city
    val cityAlerts: Map<String, AlertLevel> = emptyMap(), // per-city alert level (RED > YELLOW > NONE)
    val threatLevel: Double = 0.0,                 // experimental 0..10 gauge for the popup
    val revealRequest: RevealRequest? = null,      // notification tap: pan the camera onto a threat
    val centerRequest: CenterRequest? = null,      // locate button: centre the map on a threat
    val flourish: FlourishShow? = null,            // tally tap: replay the shot-down show
    val flyby: AviationFlybyShow? = null,          // MiG-31K takeoff: full-size pass across the viewport
    val disclaimerCollapsed: Boolean = false,
    val disclaimerReadCount: Int = 0,
    val update: UpdateState = UpdateState.Idle,
    val needsInstallPermission: Boolean = false,
    val latestVersion: String? = null,
    val wizardCompleted: Boolean? = null,   // null = prefs not loaded yet (never gate UI on that)
    val batteryOnboardShown: Boolean = false,
    val serviceResurrected: Boolean = false,
    val threatCardSize: ThreatCardSize = ThreatCardSize.LARGE,
    val iconSet: ThreatIconSet = ThreatIconSet.PHOTO,
    val overlapMode: OverlapMode = OverlapMode.DEFAULT,
    val showMapScale: Boolean = true,
    val showMediumCities: Boolean = true,
    val showSmallCities: Boolean = false,
    val showLargeCities: Boolean = true,
    val showThreatIdsOnMap: Boolean = false,
    val alertRegionMode: AlertRegionMode = AlertRegionMode.CITY_LABELS,
    val showBorders: Boolean = true,
    val showRegionBorders: Boolean = false,
    val alertOblastIds: Set<String> = emptySet(),
    val alertRaionKeys: Set<Pair<String, String>> = emptySet(),
    val alertYellowOblastIds: Set<String> = emptySet(),
    val alertYellowRaionKeys: Set<Pair<String, String>> = emptySet(),
    val alertingOblastCount: Int = 0,
    val moraleMasterEnabled: Boolean = false,
    val moraleVoice: MoraleVoice = MoraleVoice.RANDOM,
    val deathAnimationEnabled: Boolean = true,
    val flybyAnimationEnabled: Boolean = true,
    val followBullet: Boolean = true,
    val highQualityExplosions: Boolean = true,
    val neutralizedTallyEnabled: Boolean = true,
    val neutralizedTallyAllUkraine: Boolean = false,
    val alarmEpisodeTallyEnabled: Boolean = true,
    val threatIconZoom: Boolean = true,
    val fastGroupCollapsed: Boolean = false,
    val slowGroupCollapsed: Boolean = false,
    val sheltersEnabled: Boolean = true,
    val sheltersWithKids: Boolean = true,
    val periodicGps: Boolean = false,
    val calmMessagesEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val shelterIndex: ShelterIndex? = null,        // shelters — null while loading/unavailable
    val mapVisible: Boolean = true,          // the map screen is the visible screen (not settings/shelters/guide)
    val shelterOverlayUp: Boolean = false,   // the shelter overlay is showing (suppresses flourish)
    val alertActive: Boolean = false,        // any threat or official alert live right now
    val threatDataStale: Boolean = false,
    val notificationsDisabledBySystem: Boolean = false,
    val protectionState: ProtectionState = ProtectionState.ACTIVE
) {
    /** Derived summary of the two sub-channels — the master toggle. Can never be ON while red
     *  and yellow are both OFF, so the row can never read enabled with nothing selected. */
    val officialAlertsEnabled: Boolean
        get() = officialRedAlertsEnabled || officialYellowAlertsEnabled

    val redCities: Set<String> get() = cityAlerts.filterValues { it == AlertLevel.RED }.keys
}

@Immutable
data class SettingsState(
    val prefs: UserPreferences = UserPreferences()
) {
    val language: AppLanguage get() = prefs.language
    val hiddenTypes: Set<ThreatType> get() = ThreatType.values().toSet() - prefs.mapVisibleTypes
    val silencedTypes: Set<ThreatType> get() = ThreatType.values().toSet() - prefs.alertEnabledTypes
    val mapVisibleTypes: Set<ThreatType> get() = prefs.mapVisibleTypes
    val alertEnabledTypes: Set<ThreatType> get() = prefs.alertEnabledTypes
    val slowRedKm: Int get() = prefs.slowRedKm
    val slowYellowKm: Int get() = prefs.slowYellowKm
    val fastRedMin: Int get() = prefs.fastRedMin
    val fastYellowMin: Int get() = prefs.fastYellowMin
    val slowRedArmed: Boolean get() = prefs.slowRedArmed
    val slowYellowArmed: Boolean get() = prefs.slowYellowArmed
    val fastRedArmed: Boolean get() = prefs.fastRedArmed
    val fastYellowArmed: Boolean get() = prefs.fastYellowArmed
    val notifyPolicyEnabled: Boolean get() = prefs.notifyPolicyEnabled
    val zonePolicy: ZonePolicy get() = prefs.zonePolicy
    val digestMax: Int get() = prefs.digestMax
    val digestWindow: DigestWindow get() = prefs.digestWindow
    val digestPerType: Boolean get() = prefs.digestPerType
    val officialRedAlertsEnabled: Boolean get() = prefs.officialRedAlertsEnabled
    val officialYellowAlertsEnabled: Boolean get() = prefs.officialYellowAlertsEnabled
    val officialAlertsEnabled: Boolean get() = officialRedAlertsEnabled || officialYellowAlertsEnabled
    val officialAlertCityScope: Boolean get() = prefs.officialAlertCityScope
    val sirenOverride: Boolean get() = prefs.sirenOverride
    val fallingDebrisDelaySec: Int get() = prefs.fallingDebrisDelaySec
    val criticalOfflineOverride: Boolean get() = prefs.criticalOfflineOverride
    val criticalOfflineBypassSilent: Boolean get() = prefs.criticalOfflineBypassSilent
    val bootRestartEnabled: Boolean get() = prefs.bootRestartEnabled
    val nightEnabled: Boolean get() = prefs.nightEnabled
    val nightStartMin: Int get() = prefs.nightStartMin
    val nightEndMin: Int get() = prefs.nightEndMin
    val nightUseCustomZones: Boolean get() = prefs.nightUseCustomZones
    val nightSlowRedKm: Int get() = prefs.nightSlowRedKm
    val nightSlowYellowKm: Int get() = prefs.nightSlowYellowKm
    val nightFastRedMin: Int get() = prefs.nightFastRedMin
    val nightFastYellowMin: Int get() = prefs.nightFastYellowMin
    val nightSlowRedArmed: Boolean get() = prefs.nightSlowRedArmed
    val nightSlowYellowArmed: Boolean get() = prefs.nightSlowYellowArmed
    val nightFastRedArmed: Boolean get() = prefs.nightFastRedArmed
    val nightFastYellowArmed: Boolean get() = prefs.nightFastYellowArmed
    val nightZoneSirenOverride: Boolean get() = prefs.nightZoneSirenOverride
    val nightOfficialSirenOverride: Boolean get() = prefs.nightOfficialSirenOverride
    val nightOfficialAlertCityScope: Boolean get() = prefs.nightOfficialAlertCityScope
    val nightOfficialRedEnabled: Boolean get() = prefs.nightOfficialRedEnabled
    val nightOfficialYellowEnabled: Boolean get() = prefs.nightOfficialYellowEnabled
    /** "Just let me sleep!" is on when the night settings are the fully-muted combination. */
    val nightSleepActive: Boolean
        get() = prefs.nightEnabled && prefs.nightUseCustomZones &&
            !prefs.nightSlowRedArmed && !prefs.nightSlowYellowArmed &&
            !prefs.nightFastRedArmed && !prefs.nightFastYellowArmed &&
            !prefs.nightZoneSirenOverride && !prefs.nightOfficialSirenOverride &&
            !prefs.nightOfficialRedEnabled && !prefs.nightOfficialYellowEnabled
    val followMe: Boolean get() = prefs.followMe
    val pinnedCity: City? get() = prefs.pinnedCity?.let { Cities.byUa[it] }
    val pinnedCityName: String? get() = prefs.pinnedCity
    val periodicGps: Boolean get() = prefs.periodicGps
    val calmMessagesEnabled: Boolean get() = prefs.moraleMasterEnabled && prefs.calmMessagesEnabled
    val hapticsEnabled: Boolean get() = prefs.hapticsEnabled ?: true
    val disclaimerCollapsed: Boolean get() = prefs.disclaimerCollapsed
    val disclaimerReadCount: Int get() = prefs.disclaimerReadCount
    val threatCardSize: ThreatCardSize get() = prefs.threatCardSize
    val iconSet: ThreatIconSet get() = prefs.threatIconSet
    val overlapMode: OverlapMode get() = prefs.overlapMode
    val showMapScale: Boolean get() = prefs.showMapScale
    val showMediumCities: Boolean get() = prefs.showMediumCities
    val showSmallCities: Boolean get() = prefs.showSmallCities
    val showLargeCities: Boolean get() = prefs.showLargeCities
    val alertRegionMode: AlertRegionMode get() = prefs.alertRegionMode
    val showBorders: Boolean get() = prefs.showBorders
    val showRegionBorders: Boolean get() = prefs.showRegionBorders
    val sheltersEnabled: Boolean get() = prefs.sheltersEnabled
    val sheltersWithKids: Boolean get() = prefs.sheltersWithKidsEnabled
    val moraleMasterEnabled: Boolean get() = prefs.moraleMasterEnabled
    val moraleVoice: MoraleVoice get() = prefs.moraleVoice
    val deathAnimationEnabled: Boolean get() = prefs.deathAnimationEnabled
    val highQualityExplosions: Boolean get() = prefs.highQualityExplosions
    val flybyAnimationEnabled: Boolean get() = prefs.flybyAnimationEnabled
    val followBullet: Boolean get() = prefs.followBullet
    val neutralizedTallyEnabled: Boolean get() = prefs.neutralizedTallyEnabled
    val neutralizedTallyAllUkraine: Boolean get() = prefs.neutralizedTallyAllUkraine
    val alarmEpisodeTallyEnabled: Boolean get() = prefs.alarmEpisodeTallyEnabled
    val threatIconZoom: Boolean get() = prefs.threatIconZoom
    val showThreatIdsOnMap: Boolean get() = prefs.showThreatIdsOnMap
    val fastGroupCollapsed: Boolean get() = prefs.fastGroupCollapsed
    val slowGroupCollapsed: Boolean get() = prefs.slowGroupCollapsed
}

/**
 * Popup-only state, deliberately OUTSIDE [UiState]: tapping a threat updates only this flow,
 * so the header/map/footer scopes never recompose on selection. Derived from the selection
 * flows + the latest UiState ambient values (focus/params/policy flags) — no duplicated logic.
 */
@Immutable
data class SelectionUi(
    val selected: NormalizedThreat? = null,
    val proximity: ThreatProximity? = null,
    val zoneTier: ThreatZone? = null,
    val cardLevel: Double = 0.0,          // per-threat gauge score (banner aggregate never enters the card)
    val alertsOff: Boolean = false,       // selected type silenced in Settings (chip state travels with the card)
    val neutralized: NormalizedThreat? = null,   // resolved card while the death window plays
    val fakeNeutralize: Boolean = false
)

/** Stable holder for the selection flow: StateFlow is an unstable interface, so the flow
 *  itself can never be a skippable parameter — this trusted wrapper can. The host collects
 *  inside its own subtree, so taps still never invalidate the parent. */
@Immutable
data class SelectionSource(val flow: StateFlow<SelectionUi>)

// Freshness granularity for the popup: pushes bump updatedAtMillis constantly, the card
// only cares at this resolution (its elapsed clock ticks on its own).
private const val FRESHNESS_BUCKET_MS = 10_000L

// Stabilizes card state: suppresses re-emission during 120ms tick loops unless user-visible content changes.
internal fun areSelectionUiVisuallyEqual(old: SelectionUi, new: SelectionUi): Boolean {
    if (old === new) return true
    if (old.fakeNeutralize != new.fakeNeutralize) return false
    if (old.alertsOff != new.alertsOff) return false
    if (old.zoneTier != new.zoneTier) return false
    if ((old.neutralized == null) != (new.neutralized == null)) return false
    if (old.neutralized?.id != new.neutralized?.id) return false
    if ((old.selected == null) != (new.selected == null)) return false

    val oldSel = old.selected
    val newSel = new.selected
    if (oldSel != null && newSel != null) {
        if (oldSel.id != newSel.id) return false
        if (oldSel.type != newSel.type) return false
        if (oldSel.status != newSel.status) return false
        if (oldSel.count != newSel.count) return false
        if (oldSel.locality != newSel.locality || oldSel.district != newSel.district || oldSel.region != newSel.region) return false
        if (oldSel.confirmations != newSel.confirmations) return false
        if (oldSel.areaOnly != newSel.areaOnly) return false
        if (oldSel.reliability != newSel.reliability) return false
        if (oldSel.heading != newSel.heading || oldSel.bearingDeg != newSel.bearingDeg) return false
        // Freshness is coarse: live pushes bump updatedAtMillis every frame, but the card
        // shows elapsed time via its own 1s leaf clock — per-push re-emission would recompose
        // the whole card at socket rate on old phones. Real field changes above still emit
        // instantly, and selected<->neutralized flips are caught by the null checks above.
        if ((oldSel.updatedAtMillis ?: 0L) / FRESHNESS_BUCKET_MS != (newSel.updatedAtMillis ?: 0L) / FRESHNESS_BUCKET_MS) return false
    }

    val oldProx = old.proximity
    val newProx = new.proximity
    if ((oldProx == null) != (newProx == null)) return false
    if (oldProx != null && newProx != null) {
        if (oldProx.speedSource != newProx.speedSource) return false
        if (oldProx.params != newProx.params) return false
        if (oldProx.distToUserKm?.roundToInt() != newProx.distToUserKm?.roundToInt()) return false

        val oldEta = oldProx.etaToUserMin?.let { ThreatEngine.formatEtaMinutes(it) }
        val newEta = newProx.etaToUserMin?.let { ThreatEngine.formatEtaMinutes(it) }
        if (oldEta != newEta) return false

        val oldSpeed = oldProx.speedKmh?.roundToInt()
        val newSpeed = newProx.speedKmh?.roundToInt()
        if (oldSpeed != newSpeed) return false
    }
    if ((old.cardLevel * 10).roundToInt() != (new.cardLevel * 10).roundToInt()) return false
    return true
}

/** One-shot request from a notification tap to bring the camera onto a threat. */
@Immutable
data class RevealRequest(
    val tick: Int,
    val id: String?,
    val lat: Double,
    val lon: Double
)

/** One-shot request from the locate button to center the map on a threat (threat-only framing). */
@Immutable
data class CenterRequest(
    val tick: Int,
    val id: String,
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
    private val centerFlow = MutableStateFlow<CenterRequest?>(null)
    private var centerTick = 0
    private var navigateToMapCounter = 0
    private val _navigateToMapFlow = MutableStateFlow(0)
    val navigateToMapTick: StateFlow<Int> get() = _navigateToMapFlow
    /** Tally-tap replay: remembered resolved threats to shoot down on the map (flourish only). */
    private val flourishFlow = MutableStateFlow<FlourishShow?>(null)
    private var flourishTick = 0
    /** Fake post-wizard greeting shot: a synthetic SHAHED near the focus (flourish only). */
    private val welcomeShootdownFlow = MutableStateFlow<WelcomeShootdown?>(null)
    val welcomeShootdown: StateFlow<WelcomeShootdown?> get() = welcomeShootdownFlow
    private var welcomeShootdownTick = 0
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
    /** Stable flow handles so Settings collects update state in its own leaf item instead of
     *  re-reading uiState (which rebuilds on the live threat feed). */
    val updateFlow: StateFlow<UpdateState> get() = updateStateFlow
    val latestVersionState: StateFlow<String?> get() = latestVersionFlow

    // The plugin registry must exist before any property below reads it — property
    // initializers run before the init{} block, so init it here (idempotent; the later
    // init block also calls it after AlertService may have started).
    init {
        AppSources.init(getApplication())
    }

    private val registry = AppSources.registry
    private val engine = ThreatEngine(registry.typeCatalog.value)
    /** Previous tick's engine tiers — the hysteresis band input for the map evaluation. */
    private var lastZoneTiers: Map<String, ThreatZone> = emptyMap()
    private val threatsFlow = registry.allThreats.map { list ->
        list.associate { it.id to it }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private val alertsFlow = registry.allAlerts
    /** Sampled merged feed for UI (120ms) — bounds recomposition rate during heavy streams.
     *  AlertService still consumes the raw merged feed directly (mirror rule). */
    @OptIn(FlowPreview::class)
    private val liveFeed = combine(threatsFlow, alertsFlow) { threats, alerts -> threats to alerts }.sample(120)
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
    private val zonesFlow = prefs.preferences.map {
        ZoneParams(it.slowRedKm, it.slowYellowKm, it.fastRedMin, it.fastYellowMin)
    }.distinctUntilChanged()

    init {
        AppSources.init(getApplication())
        LocationTracker.start(getApplication())
        loadShelters()
        // Auto-check for updates at most once per day; pops only when no alert is active.
        autoCheckForUpdates(allowPopup = true)
    }

    override fun onCleared() {
        super.onCleared()
    }

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
        val officialRedAlertsEnabled: Boolean,
        val officialYellowAlertsEnabled: Boolean,
        val officialAlertCityScope: Boolean,
        val nightOfficialAlertCityScope: Boolean,
        val sirenOverride: Boolean,
        val followMe: Boolean,
        val criticalOfflineOverride: Boolean,
        val criticalOfflineBypassSilent: Boolean,
        val pinnedCity: String?,
        val wizardCompleted: Boolean?,
        val batteryOnboardShown: Boolean,
        val serviceResurrected: Boolean,
        val cardSize: ThreatCardSize,
        val iconSet: ThreatIconSet,
        val overlapMode: OverlapMode,
        val showMapScale: Boolean,
        val showMediumCities: Boolean,
        val showSmallCities: Boolean,
        val showLargeCities: Boolean,
        val showThreatIdsOnMap: Boolean,
        val alertRegionMode: AlertRegionMode,
        val showBorders: Boolean,
        val showRegionBorders: Boolean,
        val moraleMasterEnabled: Boolean,
        val moraleVoice: MoraleVoice,
        val deathAnimationEnabled: Boolean,
        val flybyAnimationEnabled: Boolean,
        val followBullet: Boolean,
        val highQualityExplosions: Boolean,
        val neutralizedTallyEnabled: Boolean,
        val neutralizedTallyAllUkraine: Boolean,
        val alarmEpisodeTallyEnabled: Boolean,
        val threatIconZoom: Boolean,
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
        val threats: Map<String, NormalizedThreat>,
        val alerts: List<OblastAlert>,
        val slowRedKm: Int,
        val slowYellowKm: Int,
        val fastRedMin: Int,
        val fastYellowMin: Int,
        val userLocation: LatLng?,
        val gpsFixAvailable: Boolean,
        val reveal: RevealRequest?,
        val flourish: FlourishShow?,
        val mapVisible: Boolean,
        val shelterModeActive: Boolean,
        val centerRequest: CenterRequest? = null
    )

    private data class UpdateUi(
        val update: UpdateState,
        val needsInstallPermission: Boolean,
        val latestVersion: String?
    )

    private val liveSnapshot = combine(
        liveFeed,
        zonesFlow,
        LocationTracker.location,
        LocationTracker.lastFixAtMs,
        revealFlow,
        centerFlow,
        flourishFlow,
        mapVisibleFlow,
        shelterModeFlow
    ) { values: Array<Any?> ->
        val (threats, alerts) = values[0] as Pair<Map<String, NormalizedThreat>, List<OblastAlert>>
        val radii = values[1] as ZoneParams
        val location = values[2] as LatLng?
        val lastFix = values[3] as Long?
        val reveal = values[4] as RevealRequest?
        val center = values[5] as CenterRequest?
        val flourish = values[6] as FlourishShow?
        val mapVisible = values[7] as Boolean
        val shelterModeActive = values[8] as Boolean
        LiveSnapshot(
            threats, alerts,
            radii.slowRedKm, radii.slowYellowKm, radii.fastRedMin, radii.fastYellowMin,
            location, lastFix != null, reveal, flourish, mapVisible, shelterModeActive
        ).copy(centerRequest = center)
    }.distinctUntilChanged()

    private fun UserPreferences.toPrefsSnapshot(): PrefsSnapshot {
        return PrefsSnapshot(
            mapEnabled = mapVisibleTypes,
            alertEnabled = alertEnabledTypes,
            language = language,
            disclaimerCollapsed = disclaimerCollapsed,
            disclaimerReadCount = disclaimerReadCount,
            slowRedArmed = slowRedArmed,
            slowYellowArmed = slowYellowArmed,
            fastRedArmed = fastRedArmed,
            fastYellowArmed = fastYellowArmed,
            officialRedAlertsEnabled = officialRedAlertsEnabled,
            officialYellowAlertsEnabled = officialYellowAlertsEnabled,
            officialAlertCityScope = officialAlertCityScope,
            nightOfficialAlertCityScope = nightOfficialAlertCityScope,
            sirenOverride = sirenOverride,
            followMe = followMe,
            criticalOfflineOverride = criticalOfflineOverride,
            criticalOfflineBypassSilent = criticalOfflineBypassSilent,
            pinnedCity = pinnedCity,
            wizardCompleted = wizardCompleted,
            batteryOnboardShown = batteryOnboardShown,
            serviceResurrected = serviceResurrected,
            cardSize = threatCardSize,
            iconSet = threatIconSet,
            overlapMode = overlapMode,
            showMapScale = showMapScale,
            showMediumCities = showMediumCities,
            showSmallCities = showSmallCities,
            showLargeCities = showLargeCities,
            showThreatIdsOnMap = showThreatIdsOnMap,
            alertRegionMode = alertRegionMode,
            showBorders = showBorders,
            showRegionBorders = showRegionBorders,
            moraleMasterEnabled = moraleMasterEnabled,
            moraleVoice = moraleVoice,
            deathAnimationEnabled = deathAnimationEnabled,
            flybyAnimationEnabled = flybyAnimationEnabled,
            followBullet = followBullet,
            highQualityExplosions = highQualityExplosions,
            neutralizedTallyEnabled = neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = neutralizedTallyAllUkraine,
            alarmEpisodeTallyEnabled = alarmEpisodeTallyEnabled,
            threatIconZoom = threatIconZoom,
            fastGroupCollapsed = fastGroupCollapsed,
            slowGroupCollapsed = slowGroupCollapsed,
            sheltersEnabled = sheltersEnabled,
            sheltersWithKids = sheltersWithKidsEnabled,
            periodicGps = periodicGps,
            calmMessagesEnabled = calmMessagesEnabled,
            hapticsEnabled = hapticsEnabled,
            night = NightPrefs(
                window = NightWindowPrefs(
                    enabled = nightEnabled,
                    startMin = nightStartMin,
                    endMin = nightEndMin,
                    useCustomZones = nightUseCustomZones
                ),
                zones = NightZonesPrefs(
                    slowRedKm = nightSlowRedKm,
                    slowYellowKm = nightSlowYellowKm,
                    fastRedMin = nightFastRedMin,
                    fastYellowMin = nightFastYellowMin,
                    slowRedArmed = nightSlowRedArmed,
                    slowYellowArmed = nightSlowYellowArmed,
                    fastRedArmed = nightFastRedArmed,
                    fastYellowArmed = nightFastYellowArmed,
                    zoneSirenOverride = nightZoneSirenOverride,
                    officialSirenOverride = nightOfficialSirenOverride
                )
            )
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
        prefs.preferences.first()
        emit(Unit)
    }.flowOn(Dispatchers.IO)

val uiState: StateFlow<UiState> = combine<Any?, UiState>(
        seedFlow,
        liveSnapshot,
        prefs.preferences,
        updateUiFlow,
        shelterIndexFlow,
        flybyFlow,
        MonitoringStatus.running,
        registry.degraded,
        registry.coveredByFallback
    ) { values ->
        val live = values[1] as LiveSnapshot
        val rawPrefs = values[2] as UserPreferences
        val prefs = rawPrefs.toPrefsSnapshot()
        val updateUi = values[3] as UpdateUi
        val shelterIndex = values[4] as ShelterIndex?
        val flyby = values[5] as AviationFlybyShow?
        val monitoringRunning = values[6] as Boolean
        val bootRestartEnabled = rawPrefs.bootRestartEnabled
        // No 1s wall-clock flow: the model rebuild is event-driven, so stamp the build time
        // here. Per-second visuals (staleness dimming, marker motion) live in MapView's own
        // 1s loop; ghost/night freshness re-arms on the next frame or pref change.
        val now = System.currentTimeMillis()
        val nowMono = Monotonic.now()
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
            threats = live.threats,
            alerts = live.alerts,
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
            nowMono = nowMono,
            reveal = live.reveal,
            flourish = live.flourish,
            officialAlertCityScope = prefs.officialAlertCityScope || (nightActive && prefs.nightOfficialAlertCityScope),
            showBorders = prefs.showBorders,
            showRegionBorders = prefs.showRegionBorders
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
            officialRedAlertsEnabled = prefs.officialRedAlertsEnabled,
            officialYellowAlertsEnabled = prefs.officialYellowAlertsEnabled,
            nightOfficialAlertCityScope = prefs.nightOfficialAlertCityScope,
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
            serviceResurrected = prefs.serviceResurrected,
            threatCardSize = prefs.cardSize,
            iconSet = prefs.iconSet,
            overlapMode = prefs.overlapMode,
            showMapScale = prefs.showMapScale,
            showMediumCities = prefs.showMediumCities,
            showSmallCities = prefs.showSmallCities,
            showLargeCities = prefs.showLargeCities,
            showThreatIdsOnMap = prefs.showThreatIdsOnMap,
            alertRegionMode = prefs.alertRegionMode,
showBorders = prefs.showBorders,
            showRegionBorders = prefs.showRegionBorders,
            moraleMasterEnabled = prefs.moraleMasterEnabled,
            moraleVoice = prefs.moraleVoice,
            deathAnimationEnabled = prefs.deathAnimationEnabled,
            flybyAnimationEnabled = prefs.flybyAnimationEnabled,
            followBullet = prefs.followBullet,
            highQualityExplosions = prefs.highQualityExplosions,
            neutralizedTallyEnabled = prefs.neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = prefs.neutralizedTallyAllUkraine,
            alarmEpisodeTallyEnabled = prefs.alarmEpisodeTallyEnabled,
            threatIconZoom = prefs.threatIconZoom,
            fastGroupCollapsed = prefs.fastGroupCollapsed,
            slowGroupCollapsed = prefs.slowGroupCollapsed,
            sheltersEnabled = prefs.sheltersEnabled,
            sheltersWithKids = prefs.sheltersWithKids,
            periodicGps = prefs.periodicGps,
            calmMessagesEnabled = prefs.moraleMasterEnabled && prefs.calmMessagesEnabled,
            hapticsEnabled = resolveHaptics(prefs.hapticsEnabled),
            shelterIndex = shelterIndex,
            shelterOverlayUp = live.shelterModeActive,
            notificationsDisabledBySystem = !AlertNotificationManager.areNotificationsEnabled(app),
            monitoringRunning = monitoringRunning,
            bootRestartEnabled = bootRestartEnabled,
            protectionState = deriveProtectionState(
                monitoringRunning = monitoringRunning,
                notificationsDisabledBySystem = !AlertNotificationManager.areNotificationsEnabled(app),
                activeSlowRedArmed = activeArmed.slowRed,
                activeFastRedArmed = activeArmed.fastRed,
                activeSlowYellowArmed = activeArmed.slowYellow,
                activeFastYellowArmed = activeArmed.fastYellow,
                officialRedAlertsEnabled = prefs.officialRedAlertsEnabled,
                officialYellowAlertsEnabled = prefs.officialYellowAlertsEnabled,
                criticalOfflineOverride = prefs.criticalOfflineOverride,
                silencedTypesCount = (ThreatType.values().toSet() - prefs.alertEnabled).size,
                neptunOffline = registry.isOffline(nowMono)
            )
        )
        // A fresh INNER AVIATION (bell on) plays one full-size pass across the viewport; the
        // threat card opens when it lands (onFlybyFinished). Only while genuinely foregrounded
        // — a user away from the phone gets the flyby on notification-tap reveal instead.
        val autoFlyby = AviationFlyby.nextShow(
            uiState.threatsInner, flybyPlayedIds,
            live.mapVisible && appForegroundFlow.value,
            prefs.moraleMasterEnabled, prefs.flybyAnimationEnabled,
            flybyTick + 1
        )
        if (autoFlyby != null) {
            flybyTick++
            flybyPlayedIds.add(autoFlyby.threatId)
            // Read the aviation from the SAME sampled snapshot nextShow scanned — never the live
            // mutable feed (which can rotate under us on another dispatcher). If it already
            // vanished from this snapshot, skip the show entirely.
            val threat = uiState.threatsInner.firstOrNull { it.id == autoFlyby.threatId }
            val durationMs = threat?.let { calculateFlybyDuration(it) } ?: AVIATION_FLYBY_DURATION_MS
            flybyFlow.value = AviationFlybyShow(autoFlyby.tick, autoFlyby.threatId, autoFlyby.courseDeg, durationMs)
        }
        uiState.copy(flyby = flyby, centerRequest = live.centerRequest)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UiState()
        )

    val settingsState: StateFlow<SettingsState> = prefs.preferences
        .map { SettingsState(it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SettingsState()
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
     * Popup state, pointer-first: emits immediately on selection change so the threat card
     * renders its shell on frame 0 without waiting for uiState recomputation, then enriches
     * with proximity/zoneTier as background updates arrive.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectionUi: StateFlow<SelectionUi> = selectionInput.flatMapLatest { sel ->
        flow {
            val initialThreat = sel.selected?.let { s -> threatsFlow.value[s.id] ?: s }
            if (initialThreat != null) {
                // Chip state on frame 0 too: read current prefs snapshot without subscribing.
                val shellAlertsOff = initialThreat.type.toThreatType() in uiState.value.silencedTypes
                val isNeutralized = sel.selected != null && sel.selected.id == sel.neutralizedId
                if (isNeutralized) {
                    emit(
                        SelectionUi(
                            selected = null,
                            proximity = null,
                            zoneTier = null,
                            neutralized = initialThreat,
                            fakeNeutralize = sel.fakeNeutralize
                        )
                    )
                } else {
                    emit(
                        SelectionUi(
                            selected = initialThreat,
                            proximity = null,
                            zoneTier = null,
                            neutralized = null,
                            alertsOff = shellAlertsOff,
                            fakeNeutralize = sel.fakeNeutralize
                        )
                    )
                }
            } else if (sel.neutralizedId == null) {
                emit(SelectionUi())
            }

            combine(threatsFlow, uiState) { threats, ui ->
                val animOn = ui.deathAnimationEnabled
                val refreshed = sel.selected?.let { s -> threats[s.id] }
                val nowMs = System.currentTimeMillis()
                val selectedGone = sel.selected != null && (
                    (refreshed?.let { t ->
                        t.status == "resolved" || engine.isGhost(t, engine.propsFor(t.type), nowMs)
                    } ?: true) ||
                        sel.selected.id == sel.neutralizedId
                    )
                val neutralizedThreat =
                    if (FlourishPolicy.showNeutralizedCard(selectedGone, animOn, sel.mapVisible)) sel.selected else null
                val proximity = refreshed?.let { t ->
                    engine.computeProximity(
                        t,
                        ui.focusLocation?.let { loc -> LatLng(loc.lat, loc.lon) },
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
                }
                val zoneTier = if (refreshed != null && proximity?.distToUserKm != null) {
                    val props = engine.propsFor(refreshed.type)
                    engine.zoneTier(props, proximity.distToUserKm, proximity.speedKmh, proximity.params)
                } else null
                val cardLevel = if (refreshed != null) {
                    engine.cardLevel(
                        refreshed,
                        proximity?.distToUserKm,
                        proximity?.etaToUserMin,
                        ui.activeZoneParams,
                        nowMs
                    )
                } else 0.0
                val alertsOff = refreshed?.let { it.type.toThreatType() in ui.silencedTypes } ?: false
                SelectionUi(
                    selected = if (FlourishPolicy.dropSelection(selectedGone, animOn)) null else refreshed,
                    proximity = proximity,
                    zoneTier = zoneTier,
                    cardLevel = cardLevel,
                    alertsOff = alertsOff,
                    neutralized = neutralizedThreat,
                    fakeNeutralize = sel.fakeNeutralize
                )
            }.distinctUntilChanged(::areSelectionUiVisuallyEqual).flowOn(Dispatchers.Default).collect { enriched ->
                emit(enriched)
            }
        }
    }.distinctUntilChanged(::areSelectionUiVisuallyEqual)
    .stateIn(
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
        threats: Map<String, NormalizedThreat>,
        alerts: List<OblastAlert>,
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
        nowMono: Long,
        reveal: RevealRequest?,
        flourish: FlourishShow?,
        officialAlertCityScope: Boolean,
        showBorders: Boolean,
        showRegionBorders: Boolean
    ): UiState {
        val params = effectiveParams
        val gpsFresh = LocationTracker.isFresh(now)
        val focus = resolveFocus(followMe, userLocation, gpsFresh, pinnedCity?.nameUa)
        val focusLocation = focus.location
        val attribution = focus.attribution
        val focusToken = attribution.token
        val focusBannerCity = attribution.bannerCity(language)
            .ifBlank { Strings.get(language).unknownLocation }
        // Distinct oblasts under ANY official alert (whole-oblast or region) — the Logs header count.
        val alertingOblastCount = alerts.mapNotNull { it.canonicalOblastId() }
            .map { if (it == "sevastopol") "krym" else it }
            .toSet().size

        val threatDataStale = registry.isThreatDataStale(nowMono)
        val threatList = if (threatDataStale) emptyList() else threats.values
            .filter { it.type.toThreatType() in mapEnabledTypes }
        val silencedTypeStrings = (ThreatType.values().toSet() - alertedTypes).map { it.toEngineString() }.toSet()
        val engineFocus = focusLocation?.let { LatLng(it.lat, it.lon) }
        val engineParams = ZoneParams(params.slowRedKm, params.slowYellowKm, params.fastRedMin, params.fastYellowMin)
        val evaluation = engine.evaluate(
            threats = threatList,
            focus = engineFocus,
            params = engineParams,
            hiddenTypes = emptySet(),
            silencedTypes = silencedTypeStrings,
            now = now,
            alerts = alerts,
            focusToken = focusToken,
            focusCityUa = attribution.bannerCityUa.takeIf { it.isNotBlank() },
            cityScope = officialAlertCityScope,
            lang = language,
            prevTiers = lastZoneTiers
        ).also { lastZoneTiers = it.zoneThreats }
        val inInner = evaluation.threatsInner
        val inOuter = evaluation.threatsOuter
        val mapThreats = evaluation.mapThreats
        val threatScores = evaluation.threatScores
        val focusOblastAlertActive = evaluation.focusOblastAlertActive
        val focusOblastYellowAlertActive = evaluation.focusOblastYellowAlertActive

        val activeZone: ThreatZone? = evaluation.activeZone
        val alertActive = activeZone != null || focusOblastAlertActive

        // Three-tier connection: green when a WS source delivers its live feed; orange
        // (degraded) the moment it stops (disabled, silent, or down — a data state, immediate);
        // red (offline) only after the degraded episode outlasts the grace with no fallback
        // delivering. Single derivation lives in the registry; the header just mirrors it.
        val degraded = registry.degraded.value
        val neptunDown = registry.isOffline(nowMono)

        return UiState(
            connected = registry.wsHealthy.value,
            neptunDown = neptunDown,
            degraded = degraded,
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
            focusOblastYellowAlertActive = focusOblastYellowAlertActive,
            focusBannerCity = focusBannerCity,
            language = language,
            followMe = followMe,
            pinnedCity = pinnedCity,
            focusLocation = focusLocation,
            gpsFixMissing = focus.gpsFixMissing,
            cityAlerts = evaluation.cityAlerts,
            alertOblastIds = evaluation.fillOblastTokens,
            alertRaionKeys = evaluation.fillRaionKeys,
            alertYellowOblastIds = evaluation.fillYellowOblastTokens,
            alertYellowRaionKeys = evaluation.fillYellowRaionKeys,
            alertingOblastCount = alertingOblastCount,
            threatLevel = evaluation.threatLevel,
            revealRequest = reveal,
            flourish = flourish,
            alertActive = alertActive,
            threatDataStale = threatDataStale,
            showBorders = showBorders,
            showRegionBorders = showRegionBorders
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
            if (armed) { AlertService.start(app) }
            prefs.setSlowRedZoneArmed(armed)
        }
    }

    fun setSlowYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setSlowYellowZoneArmed(armed)
        }
    }

    fun setFastRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setFastRedZoneArmed(armed)
        }
    }

    fun setFastYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setFastYellowZoneArmed(armed)
        }
    }

    /** Master alarm switch: arms or silences all four zone bells together. */
fun setAlertsArmed(armed: Boolean) {
        viewModelScope.launch {
            prefs.setAlertsArmed(armed)
            if (armed) AlertService.start(app)
        }
    }

    fun setOfficialAlertsEnabled(enabled: Boolean) {
        // The master is a derived summary of its two sub-channels: enabling it arms both,
        // disabling it disarms both. No separate stored preference — it can never be ON
        // while red and yellow are both OFF.
        viewModelScope.launch {
            prefs.setOfficialAlertsEnabled(enabled)
        }
    }

    fun setOfficialRedAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfficialRedAlertsEnabled(enabled) }
    }

    fun setOfficialYellowAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setYellowAlertsEnabled(enabled) }
    }

    fun setOfficialAlertCityScope(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfficialAlertCityScope(enabled) }
    }

    fun setSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setSirenOverride(override) }
    }

    fun setFallingDebrisDelaySec(sec: Int) {
        viewModelScope.launch { prefs.setFallingDebrisDelaySec(sec) }
    }

    fun setCriticalOfflineOverride(enabled: Boolean) {
        viewModelScope.launch { prefs.setCriticalOfflineOverride(enabled) }
    }

    fun setCriticalOfflineBypassSilent(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setCriticalOfflineBypassSilent(enabled)
            // Sound attrs freeze at channel creation — re-apply so the flip takes effect now.
            AlertNotificationManager(getApplication()).createChannels()
        }
    }

    fun setBootRestartEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setBootRestartEnabled(enabled) }
    }

    /** Tap on the "Service OFFLINE" banner: bring the foreground monitor back up. */
    fun reactivateMonitoring() {
        AlertService.start(app)
    }

    fun setBatteryOnboardShown(shown: Boolean) {
        viewModelScope.launch { prefs.setBatteryOnboardShown(shown) }
    }

    fun clearServiceResurrected() {
        viewModelScope.launch { prefs.setServiceResurrected(false) }
    }

    /** The welcome shootdown finished playing: drop it so onboarding-adjacent prompts may show. */
    fun consumeWelcomeShootdown() {
        welcomeShootdownFlow.value = null
    }

    /** The map handled the reveal request: drop it so re-entering the map never replays it. */
    fun consumeReveal() {
        revealFlow.value = null
    }

    /** The map handled the center (locate) request: drop it so it never replays. */
    fun consumeCenter() {
        centerFlow.value = null
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
            if (armed) { AlertService.start(app) }
            prefs.setNightSlowRedArmed(armed)
        }
    }

    fun setNightSlowYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setNightSlowYellowArmed(armed)
        }
    }

    fun setNightFastRedArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setNightFastRedArmed(armed)
        }
    }

    fun setNightFastYellowArmed(armed: Boolean) {
        viewModelScope.launch {
            if (armed) { AlertService.start(app) }
            prefs.setNightFastYellowArmed(armed)
        }
    }

    fun setNightZoneSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setNightZoneSirenOverride(override) }
    }

    fun setNightOfficialSirenOverride(override: Boolean) {
        viewModelScope.launch { prefs.setNightOfficialSirenOverride(override) }
    }

    fun setNightOfficialAlertCityScope(enabled: Boolean) {
        viewModelScope.launch { prefs.setNightOfficialAlertCityScope(enabled) }
    }

    fun setNightOfficialRedEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNightOfficialRedEnabled(enabled) }
    }

    fun setNightOfficialYellowEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNightOfficialYellowEnabled(enabled) }
    }

    fun setNightSleep(enabled: Boolean) {
        viewModelScope.launch { prefs.setNightSleep(enabled) }
    }

    fun setSheltersEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSheltersEnabled(enabled) }
    }

    fun setSheltersWithKidsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSheltersWithKidsEnabled(enabled) }
    }

    private fun loadShelters() {
        viewModelScope.launch {
            val json = UpdateManager(app).fetchSheltersJson()
            json?.let { ShelterIndex.fromJson(it) }?.let { shelterIndexFlow.value = it }
        }
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
            prefs.setPinnedCityWithFollow(city?.nameUa)
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
            maybeShowToggleHint(mapToast = false)
        }
    }

    /** Wizard grid: one tap enables/disables a threat type for the map AND alerts together. */
    fun setThreatEnabled(type: ThreatType, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setThreatEnabled(type, enabled)
        }
    }

    fun setGroupThreatMapVisible(types: Set<ThreatType>, visible: Boolean) {
        viewModelScope.launch {
            prefs.setThreatMapVisibleBatch(types, visible)
            maybeShowToggleHint(mapToast = true)
        }
    }

    fun setGroupThreatAlertsEnabled(types: Set<ThreatType>, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setThreatAlertsEnabledBatch(types, enabled)
            maybeShowToggleHint(mapToast = false)
        }
    }

    /** One-time hint (first 3 Map/Alerts toggles ever): a brief toast explaining how they work. */
    private suspend fun maybeShowToggleHint(mapToast: Boolean) {
        val p = prefs.preferences.first()
        val remaining = p.threatToggleHintRemaining
        if (remaining <= 0) return
        prefs.setThreatToggleHintRemaining(remaining - 1)
        val s = Strings.get(p.language)
        val prefix = if (mapToast) s.mapToggleHintPrefix else s.alertToggleHintPrefix
        val rest = if (mapToast) s.mapToggleHintRest else s.alertToggleHintRest
        val message = SpannableString(prefix + rest)
        message.setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        showToast(
            message,
            cardVisible = uiState.value.mapVisible && selectionUi.value.selected != null
        )
    }

    fun setDisclaimerCollapsed(collapsed: Boolean) {
        viewModelScope.launch { prefs.setDisclaimerCollapsed(collapsed) }
    }

    fun onDisclaimerShown() {
        viewModelScope.launch {
            val count = prefs.preferences.first().disclaimerReadCount
            if (count < 3) prefs.setDisclaimerReadCount(count + 1)
        }
    }

    fun setThreatCardSize(size: ThreatCardSize) {
        viewModelScope.launch { prefs.setThreatCardSize(size) }
    }

    fun setThreatIconSet(set: ThreatIconSet) {
        viewModelScope.launch { prefs.setThreatIconSet(set) }
    }

    fun setOverlapMode(mode: OverlapMode) {
        viewModelScope.launch { prefs.setOverlapMode(mode) }
    }

    fun setMoraleVoice(voice: MoraleVoice) {
        viewModelScope.launch { prefs.setMoraleVoice(voice) }
    }

    fun setShowMediumCities(show: Boolean) {
        viewModelScope.launch { prefs.setShowMediumCities(show) }
    }

    fun setShowSmallCities(show: Boolean) {
        viewModelScope.launch { prefs.setShowSmallCities(show) }
    }

    fun setShowLargeCities(show: Boolean) {
        viewModelScope.launch { prefs.setShowLargeCities(show) }
    }

    fun setShowThreatIdsOnMap(show: Boolean) {
        viewModelScope.launch { prefs.setShowThreatIdsOnMap(show) }
    }

    fun setNotifyPolicyEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotifyPolicyEnabled(enabled) }
    }

    fun setZonePolicy(policy: ZonePolicy) {
        viewModelScope.launch { prefs.setZonePolicy(policy) }
    }

    fun setDigestMax(max: Int) {
        viewModelScope.launch { prefs.setDigestMax(max) }
    }

    fun setDigestWindow(window: DigestWindow) {
        viewModelScope.launch { prefs.setDigestWindow(window) }
    }

    fun setDigestPerType(perType: Boolean) {
        viewModelScope.launch { prefs.setDigestPerType(perType) }
    }

    /** What-if retrospective: sounds per preset over the last-24h zone FIRED rows. */
    val policyWhatIf: StateFlow<Map<ZonePolicy, Int>> =
        combine(DebugLog.entries, prefs.preferences) { entries, p ->
            val fired = entries.filter {
                it.kind == DebugLogKind.ZONE_ENTER && it.reason == DebugLogReason.FIRED
            }
            NotifyPlugin.whatIf(fired, NotifyPrefs(p.zonePolicy, p.digestMax, p.digestWindow, p.digestPerType))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setAlertRegionMode(mode: AlertRegionMode) {
        viewModelScope.launch { prefs.setAlertRegionMode(mode) }
    }

    fun setShowBorders(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowBorders(enabled) }
    }

    fun setShowRegionBorders(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowRegionBorders(enabled) }
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

    fun setHighQualityExplosions(enabled: Boolean) {
        viewModelScope.launch { prefs.setHighQualityExplosions(enabled) }
    }

    /** Master "Morale" switch: a global kill-switch for every flourish effect. Enforced inside the
     *  flourish engine (DeathFxController / NeutralizedTally / AviationFlyby gates), so this
     *  only persists the pref — the engine reacts live and ejects any running show. */
    fun setMoraleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setMoraleMasterEnabled(enabled)
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

    fun setAlarmEpisodeTallyEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlarmEpisodeTallyEnabled(enabled) }
    }

    fun setThreatIconZoom(enabled: Boolean) {
        viewModelScope.launch { prefs.setThreatIconZoom(enabled) }
    }

    /** Wizard finished (Done): setup complete. */
    fun completeWizard() {
        viewModelScope.launch {
            prefs.setWizardCompleted(true)
            maybeTriggerWelcomeShootdown()
        }
    }

    /** Tapped "Later" on the first-run wizard — exit all setup chrome for this session:
     *  mark setup complete, skip the battery prompt, and defer the location/notification
     *  permission requests until the next cold start. */
    fun deferWizard() {
        viewModelScope.launch {
            prefs.setWizardDeferred(true)
            maybeTriggerWelcomeShootdown()
        }
    }

    /** Re-open the first-run setup (threat care, location, zones, feature tour + battery
     *  prompt). Only flips the onboarding-completed flags — no setting is reset. Clears only
     *  wizard_completed so a kill mid-replay doesn't resurrect the wizard on every cold start.
     *  Never clears welcome_shootdown_played: the greeting shot is once per install. */
    fun relaunchSetup() {
        viewModelScope.launch {
            prefs.setWizardDeferred(false)
        }
    }

    /** Fire the one-shot fake welcome shootdown after the wizard — once per install, gated
     *  only on the Morale master. The flag is marked even when the gate is closed so a
     *  Morale-off install never replays it later. */
    private suspend fun maybeTriggerWelcomeShootdown() {
        val p = prefs.preferences.first()
        prefs.setWelcomeShootdownPlayed(true)
        if (p.welcomeShootdownPlayed || !p.moraleMasterEnabled) return
        // Pin-first: prefs are transactional (all wizard writes have landed), while
        // uiState.focusLocation can lag a frame or be null with no GPS fix yet.
        val pinned = p.pinnedCity?.let { Cities.byUa[it] }
        val focus = uiState.value.focusLocation
        val (lat, lon) = when {
            !p.followMe && pinned != null -> pinned.lat to pinned.lon
            focus != null -> focus.lat to focus.lon
            pinned != null -> pinned.lat to pinned.lon
            else -> 50.4501 to 30.5234
        }
        welcomeShootdownTick++
        welcomeShootdownFlow.value = WelcomeShootdown(welcomeShootdownTick, lat, lon)
    }

    fun resetAllTips() {
        viewModelScope.launch { prefs.resetAllTips() }
    }

    fun selectThreat(threat: NormalizedThreat?) {
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

    /** Emergency eject from every playful overlay (currently the MiG flyby): clears it without
     *  opening the threat card — the user asked to return to a non-fun, safety-first map. */
    fun ejectAllFun() {
        flybyFlow.value = null
    }

    /** Treat [id] as neutralized so its card self-destructs (map long-press trigger). */
    fun neutralizeThreat(id: String) {
        neutralizedFlow.value = id
        fakeNeutralizeFlow.value = true
    }

    /** Whether the app process is foregrounded — drives the flyby auto-trigger gate and
     *  REST-source polling cadence (foreground → faster polling). */
    fun setAppForeground(foreground: Boolean) {
        appForegroundFlow.value = foreground
        AppSources.setAppForeground(foreground)
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
        if (aviation && id != null && threat != null) {
            val s = uiState.value
            val show = AviationFlyby.tapShow(
                moraleEnabled = s.moraleMasterEnabled,
                flybyEnabled = s.flybyAnimationEnabled,
                tick = flybyTick + 1,
                threatId = id,
                courseDeg = Random.nextDouble(0.0, 360.0),
                durationMs = calculateFlybyDuration(threat)
            )
            if (show != null) {
                flybyPlayedIds.add(id)
                flybyTick++
                flybyFlow.value = show
            } else if (select) {
                selectedThreatFlow.value = threat
            }
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

    /** Locate button: center the map on [t] without selecting/deselecting it (threat-only framing). */
    fun centerOnThreat(t: NormalizedThreat) {
        val now = System.currentTimeMillis()
        val props = engine.propsFor(t.type)
        val speed = engine.speedCache.estimate(t.id, t, props)
        val predicted = speed?.let { engine.predictPosition(t, it, props, now) }
            ?: LatLng(t.lat, t.lon)
        centerTick++
        centerFlow.value = CenterRequest(centerTick, t.id, predicted.lat, predicted.lon)
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
            val s = Strings.get(prefs.preferences.first().language)
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
                        showToast(s.updateUpToDate, cardVisible = false)
                    }
                }
                is UpdateState.Failed -> {
                    updateStateFlow.value = UpdateState.Idle
                    if (notify) {
                        val message = result.message?.let { ": $it" }.orEmpty()
                        showToast(s.updateCheckFailed + message, cardVisible = false)
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

private fun deriveProtectionState(
    monitoringRunning: Boolean,
    notificationsDisabledBySystem: Boolean,
    activeSlowRedArmed: Boolean,
    activeFastRedArmed: Boolean,
    activeSlowYellowArmed: Boolean,
    activeFastYellowArmed: Boolean,
    officialRedAlertsEnabled: Boolean,
    officialYellowAlertsEnabled: Boolean,
    criticalOfflineOverride: Boolean,
    silencedTypesCount: Int,
    neptunOffline: Boolean
): ProtectionState {
    if (!monitoringRunning) return ProtectionState.OFFLINE
    val anyZoneArmed = activeSlowRedArmed || activeFastRedArmed || activeSlowYellowArmed || activeFastYellowArmed
    val allChannelsOff = !anyZoneArmed &&
        !(officialRedAlertsEnabled || officialYellowAlertsEnabled)
    val reduced = notificationsDisabledBySystem ||
        allChannelsOff ||
        silencedTypesCount == ThreatType.values().size ||
        (!criticalOfflineOverride && neptunOffline)
    return if (reduced) ProtectionState.REDUCED else ProtectionState.ACTIVE
}
