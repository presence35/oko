package ua.ukrainedrones

import androidx.compose.runtime.Immutable
import ua.ukrainedrones.ThreatType

enum class AppLanguage { UA, EN }

enum class ThreatCardSize { SMALL, LARGE }

enum class ThreatIconSet { PHOTO, ARMY, COMIC, RUSSIAN }

/** How same-coordinate threats render on the map. */
enum class OverlapMode { DEFAULT, GRID, SPREAD, COUNT }

/** How official-alert regions are visualized on the map. */
enum class AlertRegionMode { CITY_LABELS, FILL, BORDER }

@Immutable
data class UserPreferences(
    val language: AppLanguage = AppLanguage.UA,
    val languageChosen: Boolean = false,
    val wizardCompleted: Boolean = false,
    val slowRedKm: Int = 20,
    val slowYellowKm: Int = 50,
    val fastRedMin: Int = 5,
    val fastYellowMin: Int = 20,
    val slowRedArmed: Boolean = true,
    val slowYellowArmed: Boolean = true,
    val fastRedArmed: Boolean = true,
    val fastYellowArmed: Boolean = true,
    val officialRedAlertsEnabled: Boolean = true,
    val yellowAlertsEnabled: Boolean = true,
    val sirenOverride: Boolean = false,
    val fallingDebrisDelaySec: Int = 0,
    val disclaimerCollapsed: Boolean = false,
    val disclaimerReadCount: Int = 0,
    val followMe: Boolean = true,
    val pinnedCity: String? = null,
    val criticalOfflineOverride: Boolean = true,
    val criticalOfflineBypassSilent: Boolean = false,
    val threatCardSize: ThreatCardSize = ThreatCardSize.LARGE,
    val threatIconSet: ThreatIconSet = ThreatIconSet.PHOTO,
    val overlapMode: OverlapMode = OverlapMode.DEFAULT,
    val showMapScale: Boolean = true,
    val showMediumCities: Boolean = true,
    val showSmallCities: Boolean = false,
    val showLargeCities: Boolean = true,
    val showThreatIdsOnMap: Boolean = false,
    val deathAnimationEnabled: Boolean = true,
    val followBullet: Boolean = true,
    val highQualityExplosions: Boolean = true,
    val neutralizedTallyEnabled: Boolean = true,
    val neutralizedTallyAllUkraine: Boolean = false,
    val legacyCacheCleaned: Boolean = false,
    val fastGroupCollapsed: Boolean = false,
    val slowGroupCollapsed: Boolean = false,
    val batteryOnboardShown: Boolean = false,
    val permissionPromptDeferred: Boolean = false,
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
    val nightOfficialAlertCityScope: Boolean = false,
    val flybyAnimationEnabled: Boolean = true,
    val threatIconZoom: Boolean = true,
    val sheltersEnabled: Boolean = true,
    val sheltersWithKidsEnabled: Boolean = true,
    val periodicGps: Boolean = false,
    val calmMessagesEnabled: Boolean = true,
    val hapticsEnabled: Boolean? = true,
    val officialAlertCityScope: Boolean = false,
    val moraleMasterEnabled: Boolean = false,
    val bootRestartEnabled: Boolean = true,
    val alertRegionMode: AlertRegionMode = AlertRegionMode.CITY_LABELS,
    val showBorders: Boolean = true,
    val showRegionBorders: Boolean = false,
    val settingsHintRemaining: Int = 3,
    val threatToggleHintRemaining: Int = 3,
    val flourishEjectHintRemaining: Int = 3,
    val shelterTipStage: Int = 0,
    val mapVisibleTypes: Set<ThreatType> = ThreatType.values().toSet(),
    val alertEnabledTypes: Set<ThreatType> = ThreatType.values().toSet()
) {
    val justFunMasterEnabled: Boolean get() = moraleMasterEnabled
    val officialYellowAlertsEnabled: Boolean get() = yellowAlertsEnabled
    val iconSet: ThreatIconSet get() = threatIconSet
    val sheltersWithKids: Boolean get() = sheltersWithKidsEnabled
    val cardSize: ThreatCardSize get() = threatCardSize
}
