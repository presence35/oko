package com.odesaplay.oko

import androidx.compose.runtime.Immutable
import com.odesaplay.oko.ThreatType

enum class AppLanguage { UA, EN, RU }

/**
 * The single sanctioned language branch. Content selection anywhere in the app goes
 * through this — never `==`/`when` on [AppLanguage] directly, so adding a language
 * touches the lang tables, not the call sites.
 */
fun <T> AppLanguage.pick(ua: T, en: T, ru: T): T = when (this) {
    AppLanguage.UA -> ua
    AppLanguage.EN -> en
    AppLanguage.RU -> ru
}

/**
 * Phone locale → app language. The default when no override is stored
 * (see `UserPrefs.languageKey`); unknown locales fall back to EN.
 */
fun systemLanguage(): AppLanguage = when (java.util.Locale.getDefault().language) {
    "uk" -> AppLanguage.UA
    "ru" -> AppLanguage.RU
    else -> AppLanguage.EN
}

enum class ThreatCardSize { SMALL, LARGE }

enum class ThreatIconSet { PHOTO, ARMY, COMIC, RUSSIAN }

/** Death-animation engine: cheap rings, the original look, or the fuller rebuild,
 *  plus three candidate elites (GPU shader, baked film, canvas-with-shader-upgrade) whose
 *  look is still being picked between. Each is a self-contained [DeathAnimation] plugin. */
enum class DeathAnimationStyle { SIMPLE, CLASSIC, ADVANCED, ELITE_SHADER, ELITE_BAKED, ELITE_HYBRID }

/** How same-coordinate threats render on the map. */
enum class OverlapMode { DEFAULT, COUNT }

/** How official-alert regions are visualized on the map. */
enum class AlertRegionMode { CITY_LABELS, FILL, BORDER }

/** Personality voice for the Morale section copy. RANDOM resolves to a deterministic
 *  voice-of-day (pure function of date — no stored state, no timers). */
enum class MoraleVoice { RANDOM, PLAIN, WARM, SPICY, SLANG, VIYSKO, BABUSIA }

@Immutable
data class UserPreferences(
    val language: AppLanguage = AppLanguage.EN,
    val wizardCompleted: Boolean = false,
    val welcomeShootdownPlayed: Boolean = false,
    val slowRedKm: Int = 20,
    val slowYellowKm: Int = 50,
    val fastRedMin: Int = 5,
    val fastYellowMin: Int = 20,
    val slowRedArmed: Boolean = true,
    val slowYellowArmed: Boolean = true,
    val fastRedArmed: Boolean = true,
    val fastYellowArmed: Boolean = false,
    /**
     * "Limit repeat sounds". ON, not off: with the master off, [NotifyPrefs.from] ignores
     * [zonePolicy] and sounds EVERY_CHANGE — so the stored default of ONCE_PER_THREAT was a
     * claim the app never kept. One bell per threat (re-belling when it reaches the red ring,
     * plus every escalation) is what this pref has always said it does.
     */
    val notifyPolicyEnabled: Boolean = true,
    val zonePolicy: ZonePolicy = ZonePolicy.ONCE_PER_THREAT,
    val digestMax: Int = 10,
    val digestWindow: DigestWindow = DigestWindow.EPISODE,
    val digestPerType: Boolean = false,
    val officialRedAlertsEnabled: Boolean = true,
    val officialYellowAlertsEnabled: Boolean = true,
    val sirenOverride: Boolean = false,
    val fallingDebrisDelaySec: Int = 0,
    val autoDismissAllClear: Boolean = true,
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
    val deathAnimationStyle: DeathAnimationStyle = DeathAnimationStyle.ADVANCED,
    val followBullet: Boolean = true,
    val highQualityExplosions: Boolean = true,
    val neutralizedTallyEnabled: Boolean = true,
    val neutralizedTallyAllUkraine: Boolean = false,
    val alarmEpisodeTallyEnabled: Boolean = true,
    val legacyCacheCleaned: Boolean = false,
        val batteryOnboardShown: Boolean = false,
    val serviceResurrected: Boolean = false,
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
    val nightFastYellowArmed: Boolean = false,
    val nightZoneSirenOverride: Boolean = false,
    val nightOfficialSirenOverride: Boolean = false,
    val nightOfficialAlertCityScope: Boolean = false,
    val nightOfficialRedEnabled: Boolean = true,
    val nightOfficialYellowEnabled: Boolean = true,
    val nightSleepRestore: String? = null,
    val flybyAnimationEnabled: Boolean = true,
    val threatIconZoom: Boolean = true,
    val sheltersEnabled: Boolean = true,
    val sheltersWithKidsEnabled: Boolean = true,
    val periodicGps: Boolean = false,
    val calmMessagesEnabled: Boolean = true,
    val hapticsEnabled: Boolean? = true,
    val officialAlertCityScope: Boolean = false,
    val moraleMasterEnabled: Boolean = true,
    val moraleVoice: MoraleVoice = MoraleVoice.RANDOM,
    val bootRestartEnabled: Boolean = true,
    /** Off by default: the notice asks for a battery exemption, so it is opt-in rather than
     *  something every existing user starts receiving after an update. */
    val sleepNoticeEnabled: Boolean = false,
    val alertRegionMode: AlertRegionMode = AlertRegionMode.CITY_LABELS,
    val showBorders: Boolean = true,
    val showRegionBorders: Boolean = false,
    val settingsHintRemaining: Int = 3,
    val threatToggleHintRemaining: Int = 3,
    val shelterTipStage: Int = 0,
    val mapVisibleTypes: Set<ThreatType> = ThreatType.values().toSet(),
    val alertEnabledTypes: Set<ThreatType> = ThreatType.values().toSet()
) {
    val iconSet: ThreatIconSet get() = threatIconSet
    val sheltersWithKids: Boolean get() = sheltersWithKidsEnabled
    val cardSize: ThreatCardSize get() = threatCardSize

    /** The night settings the "Just let me sleep!" button owns. */
    val nightSleepPreset: NightSleepPreset
        get() = NightSleepPreset(
            useCustomZones = nightUseCustomZones,
            slowRedArmed = nightSlowRedArmed,
            slowYellowArmed = nightSlowYellowArmed,
            fastRedArmed = nightFastRedArmed,
            fastYellowArmed = nightFastYellowArmed,
            zoneSirenOverride = nightZoneSirenOverride,
            officialSirenOverride = nightOfficialSirenOverride,
            officialRed = nightOfficialRedEnabled,
            officialYellow = nightOfficialYellowEnabled
        )

    /**
     * True while "Just let me sleep!" is in effect — the night settings ARE the muted preset.
     *
     * Deliberately NOT gated on [nightEnabled]: switching the night window off leaves every
     * night bell switched off, and a hidden fact that all your alerts are off is exactly the
     * state a user must never have to guess about.
     */
    val nightSleepActive: Boolean get() = nightSleepPreset.isMuted

    companion object {
        val DEFAULT = UserPreferences()
    }
}
