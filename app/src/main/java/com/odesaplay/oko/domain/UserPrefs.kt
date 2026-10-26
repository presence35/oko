package com.odesaplay.oko

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.odesaplay.oko.ThreatType

private const val STORE_NAME = "user_prefs"

class UserPrefs(private val context: Context) {

    /** Resolved through [PreferenceStores] so a test can sandbox it. See that file for why. */
    private val store by lazy { PreferenceStores.of(context, STORE_NAME) }

    /**
     * Every stored key/value as text, for diagnostics export. Reads the DataStore directly so a
     * newly added pref is covered without touching this class — the alternative, a hand-kept
     * mirror of every key, silently rots.
     */
    suspend fun snapshot(): Map<String, String> = store.data.first().asMap()
        .map { (key, value) -> key.name to value.toString() }
        .toMap()

    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, Preferences.Key<Boolean>>()
    private fun cachedBooleanKey(name: String): Preferences.Key<Boolean> =
        keyCache.getOrPut(name) { booleanPreferencesKey(name) }

    private val wizardCompletedKey = booleanPreferencesKey("wizard_completed")
    private val slowRedKmKey = intPreferencesKey("slow_red_km")
    private val slowYellowKmKey = intPreferencesKey("slow_yellow_km")
    private val fastRedMinKey = intPreferencesKey("fast_red_min")
    private val fastYellowMinKey = intPreferencesKey("fast_yellow_min")
    private val slowRedArmedKey = booleanPreferencesKey("slow_red_armed")
    private val slowYellowArmedKey = booleanPreferencesKey("slow_yellow_armed")
    private val fastRedArmedKey = booleanPreferencesKey("fast_red_armed")
    private val fastYellowArmedKey = booleanPreferencesKey("fast_yellow_armed")
    private val officialRedAlertsKey = booleanPreferencesKey("official_red_alerts_enabled")
    private val officialYellowAlertsKey = booleanPreferencesKey("yellow_alerts_enabled")
    private val sirenOverrideKey = booleanPreferencesKey("siren_override")
    private val fallingDebrisDelaySecKey = intPreferencesKey("falling_debris_delay_sec")
    private val autoDismissAllClearKey = booleanPreferencesKey("auto_dismiss_all_clear")
    private val disclaimerCollapsedKey = booleanPreferencesKey("disclaimer_collapsed")
    private val disclaimerReadCountKey = intPreferencesKey("disclaimer_read_count")
    private val followMeKey = booleanPreferencesKey("follow_me")
    private val pinnedCityKey = stringPreferencesKey("pinned_city")
    private val criticalOfflineOverrideKey = booleanPreferencesKey("critical_offline_override")
    private val criticalOfflineBypassSilentKey = booleanPreferencesKey("critical_offline_bypass_silent")
    private val settingsHintRemainingKey = intPreferencesKey("settings_hint_remaining")
    private val threatToggleHintRemainingKey = intPreferencesKey("threat_toggle_hint_remaining")
    private val shelterTipRemainingKey = intPreferencesKey("shelter_tip_remaining")
    private val threatCardSizeKey = stringPreferencesKey("threat_card_size")
    private val threatIconSetKey = stringPreferencesKey("threat_icon_set")
    private val threatIconGradesKey = stringPreferencesKey("threat_icon_grades")
    private val overlapModeKey = stringPreferencesKey("threat_overlap_mode")
    private val moralePersonaKey = stringPreferencesKey("morale_persona")
    private val showMapScaleKey = booleanPreferencesKey("show_map_scale")
    private val showMediumCitiesKey = booleanPreferencesKey("show_medium_cities")
    private val showSmallCitiesKey = booleanPreferencesKey("show_small_cities")
    private val deathAnimationEnabledKey = booleanPreferencesKey("death_animation_enabled")
    private val deathAnimationStyleKey = stringPreferencesKey("death_animation_style")
    private val followBulletKey = booleanPreferencesKey("follow_bullet")
    private val highQualityExplosionsKey = booleanPreferencesKey("high_quality_explosions")
    private val neutralizedTallyEnabledKey = booleanPreferencesKey("neutralized_tally_enabled")
    private val neutralizedTallyAllUkraineKey = booleanPreferencesKey("neutralized_tally_all_ukraine")
    private val alarmEpisodeTallyEnabledKey = booleanPreferencesKey("alarm_episode_tally_enabled")
    private val legacyCacheCleanedKey = booleanPreferencesKey("legacy_osmdroid_cleaned")
        private val batteryOnboardShownKey = booleanPreferencesKey("battery_onboard_shown")
    private val serviceResurrectedKey = booleanPreferencesKey("service_resurrected")
    private val permissionPromptDeferredKey = booleanPreferencesKey("permission_prompt_deferred")
    private val nightEnabledKey = booleanPreferencesKey("night_enabled")
    private val nightStartMinKey = intPreferencesKey("night_start_min")
    private val nightEndMinKey = intPreferencesKey("night_end_min")
    private val nightUseCustomZonesKey = booleanPreferencesKey("night_use_custom_zones")
    private val nightOwnAlertPolicyKey = booleanPreferencesKey("night_own_alert_policy")
    private val nightSlowRedKmKey = intPreferencesKey("night_slow_red_km")
    private val nightSlowYellowKmKey = intPreferencesKey("night_slow_yellow_km")
    private val nightFastRedMinKey = intPreferencesKey("night_fast_red_min")
    private val nightFastYellowMinKey = intPreferencesKey("night_fast_yellow_min")
    private val nightSlowRedArmedKey = booleanPreferencesKey("night_slow_red_armed")
    private val nightSlowYellowArmedKey = booleanPreferencesKey("night_slow_yellow_armed")
    private val nightFastRedArmedKey = booleanPreferencesKey("night_fast_red_armed")
    private val nightFastYellowArmedKey = booleanPreferencesKey("night_fast_yellow_armed")
    private val nightZoneSirenOverrideKey = booleanPreferencesKey("night_zone_siren_override")
    private val nightOfficialSirenOverrideKey = booleanPreferencesKey("night_official_siren_override")
    private val nightOfficialAlertCityScopeKey = booleanPreferencesKey("night_official_alert_city_scope")
    private val nightOfficialRedEnabledKey = booleanPreferencesKey("night_official_red_enabled")
    private val nightOfficialYellowEnabledKey = booleanPreferencesKey("night_official_yellow_enabled")
    private val nightSleepRestoreKey = stringPreferencesKey("night_sleep_restore")
    private val flybyAnimationEnabledKey = booleanPreferencesKey("flyby_animation_enabled")
    private val threatIconZoomKey = booleanPreferencesKey("threat_icon_zoom")
    private val sheltersEnabledKey = booleanPreferencesKey("shelters_enabled")
    private val sheltersWithKidsEnabledKey = booleanPreferencesKey("shelters_with_kids_enabled")
    private val periodicGpsKey = booleanPreferencesKey("periodic_gps_enabled")
    private val calmMessagesEnabledKey = booleanPreferencesKey("calm_messages_enabled")
    private val hapticsEnabledKey = booleanPreferencesKey("haptics_enabled")
    private val officialAlertCityScopeKey = booleanPreferencesKey("official_alert_city_scope")
    private val moraleMasterEnabledKey = booleanPreferencesKey("morale_master_enabled")
    private val welcomeShootdownPlayedKey = booleanPreferencesKey("welcome_shootdown_played")
    private val bootRestartEnabledKey = booleanPreferencesKey("boot_restart_enabled")
    private val sleepNoticeEnabledKey = booleanPreferencesKey("sleep_notice_enabled")
    private val fillAlertRegionsKey = booleanPreferencesKey("fill_alert_regions")
    private val alertRegionModeKey = stringPreferencesKey("alert_region_mode")
    private val showBordersKey = booleanPreferencesKey("show_borders")
    private val showRegionBordersKey = booleanPreferencesKey("show_region_borders")
    private val showLargeCitiesKey = booleanPreferencesKey("show_large_cities")
    private val showThreatIdsOnMapKey = booleanPreferencesKey("show_threat_ids_on_map")
    private val showFeedbackButtonKey = booleanPreferencesKey("show_feedback_button")
    private val notifyPolicyEnabledKey = booleanPreferencesKey("notify_policy_enabled")
    private val zonePolicyKey = stringPreferencesKey("zone_policy")
    private val digestMaxKey = intPreferencesKey("digest_max")
    private val digestWindowKey = stringPreferencesKey("digest_window")
    private val digestPerTypeKey = booleanPreferencesKey("digest_per_type")
    private val languageKey = stringPreferencesKey("language")

    val preferences: Flow<UserPreferences> = store.data.map { it.toUserPreferences() }.distinctUntilChanged()

    private fun Preferences.toUserPreferences(): UserPreferences {
        // No stored override: the language follows the phone locale until the user picks one.
        val lang = this[languageKey]?.let { stored ->
            AppLanguage.values().firstOrNull { it.name == stored }
        } ?: systemLanguage()
        val d = UserPreferences.DEFAULT
        val cardSize = this[threatCardSizeKey]?.let { stored ->
            ThreatCardSize.values().firstOrNull { it.name == stored }
        } ?: d.threatCardSize
        val iconSet = this[threatIconSetKey]?.let { stored ->
            ThreatIconSet.values().firstOrNull { it.name == stored }
        } ?: d.threatIconSet
        val iconGrades = parseIconGrades(this[threatIconGradesKey])
        val overlap = this[overlapModeKey]?.let { stored ->
            OverlapMode.values().firstOrNull { it.name == stored }
        } ?: d.overlapMode
        val moralePersona = this[moralePersonaKey]?.let { stored ->
            MoralePersona.values().firstOrNull { it.name == stored }
        } ?: d.moralePersona
        val deathAnimationStyle = this[deathAnimationStyleKey]?.let { stored ->
            DeathAnimationStyle.values().firstOrNull { it.name == stored }
        } ?: d.deathAnimationStyle
        val zonePolicy = this[zonePolicyKey]?.let { stored ->
            ZonePolicy.values().firstOrNull { it.name == stored }
        } ?: d.zonePolicy
        val digestWindow = this[digestWindowKey]?.let { stored ->
            DigestWindow.values().firstOrNull { it.name == stored }
        } ?: d.digestWindow
        val alertRegionMode = when (val stored = this[alertRegionModeKey]) {
            null -> this[fillAlertRegionsKey]?.let { if (it) AlertRegionMode.FILL else AlertRegionMode.CITY_LABELS }
                ?: d.alertRegionMode
            else -> AlertRegionMode.values().firstOrNull { it.name == stored } ?: d.alertRegionMode
        }
        val mapVisible = ThreatType.values().filter { type ->
            this[cachedBooleanKey("threat_map_${type.name}")] ?: d.mapVisibleTypes.contains(type)
        }.toSet()
        val alertEnabled = ThreatType.values().filter { type ->
            this[cachedBooleanKey("threat_alert_${type.name}")] ?: d.alertEnabledTypes.contains(type)
        }.toSet()

        return UserPreferences(
            language = lang,
            wizardCompleted = this[wizardCompletedKey] ?: d.wizardCompleted,
            welcomeShootdownPlayed = this[welcomeShootdownPlayedKey] ?: d.welcomeShootdownPlayed,
            slowRedKm = this[slowRedKmKey] ?: d.slowRedKm,
            slowYellowKm = this[slowYellowKmKey] ?: d.slowYellowKm,
            fastRedMin = this[fastRedMinKey] ?: d.fastRedMin,
            fastYellowMin = this[fastYellowMinKey] ?: d.fastYellowMin,
            slowRedArmed = this[slowRedArmedKey] ?: d.slowRedArmed,
            slowYellowArmed = this[slowYellowArmedKey] ?: d.slowYellowArmed,
            fastRedArmed = this[fastRedArmedKey] ?: d.fastRedArmed,
            fastYellowArmed = this[fastYellowArmedKey] ?: d.fastYellowArmed,
            notifyPolicyEnabled = this[notifyPolicyEnabledKey] ?: d.notifyPolicyEnabled,
            zonePolicy = zonePolicy,
            digestMax = (this[digestMaxKey] ?: d.digestMax).coerceIn(1, 10),
            digestWindow = digestWindow,
            digestPerType = this[digestPerTypeKey] ?: d.digestPerType,
            officialRedAlertsEnabled = this[officialRedAlertsKey] ?: d.officialRedAlertsEnabled,
            officialYellowAlertsEnabled = this[officialYellowAlertsKey] ?: d.officialYellowAlertsEnabled,
            sirenOverride = this[sirenOverrideKey] ?: d.sirenOverride,
            fallingDebrisDelaySec = this[fallingDebrisDelaySecKey] ?: d.fallingDebrisDelaySec,
            autoDismissAllClear = this[autoDismissAllClearKey] ?: d.autoDismissAllClear,
            disclaimerCollapsed = this[disclaimerCollapsedKey] ?: d.disclaimerCollapsed,
            disclaimerReadCount = this[disclaimerReadCountKey] ?: d.disclaimerReadCount,
            followMe = this[followMeKey] ?: d.followMe,
            pinnedCity = this[pinnedCityKey],
            criticalOfflineOverride = this[criticalOfflineOverrideKey] ?: d.criticalOfflineOverride,
            criticalOfflineBypassSilent = this[criticalOfflineBypassSilentKey] ?: d.criticalOfflineBypassSilent,
            threatCardSize = cardSize,
            threatIconSet = iconSet,
            threatIconGrades = iconGrades,
            overlapMode = overlap,
            moralePersona = moralePersona,
            showMapScale = this[showMapScaleKey] ?: d.showMapScale,
            showMediumCities = this[showMediumCitiesKey] ?: d.showMediumCities,
            showSmallCities = this[showSmallCitiesKey] ?: d.showSmallCities,
            showLargeCities = this[showLargeCitiesKey] ?: d.showLargeCities,
            showThreatIdsOnMap = this[showThreatIdsOnMapKey] ?: d.showThreatIdsOnMap,
            showFeedbackButton = this[showFeedbackButtonKey] ?: d.showFeedbackButton,
            highQualityExplosions = this[highQualityExplosionsKey] ?: d.highQualityExplosions,
            deathAnimationEnabled = this[deathAnimationEnabledKey] ?: d.deathAnimationEnabled,
            deathAnimationStyle = deathAnimationStyle,
            followBullet = this[followBulletKey] ?: d.followBullet,
            neutralizedTallyEnabled = this[neutralizedTallyEnabledKey] ?: d.neutralizedTallyEnabled,
            neutralizedTallyAllUkraine = this[neutralizedTallyAllUkraineKey] ?: d.neutralizedTallyAllUkraine,
            alarmEpisodeTallyEnabled = this[alarmEpisodeTallyEnabledKey] ?: d.alarmEpisodeTallyEnabled,
            legacyCacheCleaned = this[legacyCacheCleanedKey] ?: d.legacyCacheCleaned,
                        batteryOnboardShown = this[batteryOnboardShownKey] ?: d.batteryOnboardShown,
            serviceResurrected = this[serviceResurrectedKey] ?: d.serviceResurrected,
            permissionPromptDeferred = this[permissionPromptDeferredKey] ?: d.permissionPromptDeferred,
            nightEnabled = this[nightEnabledKey] ?: d.nightEnabled,
            nightStartMin = this[nightStartMinKey] ?: d.nightStartMin,
            nightEndMin = this[nightEndMinKey] ?: d.nightEndMin,
            nightUseCustomZones = this[nightUseCustomZonesKey] ?: d.nightUseCustomZones,
            nightOwnAlertPolicy = this[nightOwnAlertPolicyKey] ?: d.nightOwnAlertPolicy,
            nightSlowRedKm = this[nightSlowRedKmKey] ?: d.nightSlowRedKm,
            nightSlowYellowKm = this[nightSlowYellowKmKey] ?: d.nightSlowYellowKm,
            nightFastRedMin = this[nightFastRedMinKey] ?: d.nightFastRedMin,
            nightFastYellowMin = this[nightFastYellowMinKey] ?: d.nightFastYellowMin,
            nightSlowRedArmed = this[nightSlowRedArmedKey] ?: d.nightSlowRedArmed,
            nightSlowYellowArmed = this[nightSlowYellowArmedKey] ?: d.nightSlowYellowArmed,
            nightFastRedArmed = this[nightFastRedArmedKey] ?: d.nightFastRedArmed,
            nightFastYellowArmed = this[nightFastYellowArmedKey] ?: d.nightFastYellowArmed,
            // Policy keys are only consulted while the night's own policy switch is on; these are
            // the values it uses then, and the day values are what apply otherwise.
            nightZoneSirenOverride = this[nightZoneSirenOverrideKey] ?: d.nightZoneSirenOverride,
            nightOfficialSirenOverride = this[nightOfficialSirenOverrideKey] ?: d.nightOfficialSirenOverride,
            nightOfficialAlertCityScope = this[nightOfficialAlertCityScopeKey] ?: d.nightOfficialAlertCityScope,
            nightOfficialRedEnabled = this[nightOfficialRedEnabledKey] ?: d.nightOfficialRedEnabled,
            nightOfficialYellowEnabled = this[nightOfficialYellowEnabledKey] ?: d.nightOfficialYellowEnabled,
            nightSleepRestore = this[nightSleepRestoreKey] ?: d.nightSleepRestore,
            flybyAnimationEnabled = this[flybyAnimationEnabledKey] ?: d.flybyAnimationEnabled,
            threatIconZoom = this[threatIconZoomKey] ?: d.threatIconZoom,
            sheltersEnabled = this[sheltersEnabledKey] ?: d.sheltersEnabled,
            sheltersWithKidsEnabled = this[sheltersWithKidsEnabledKey] ?: d.sheltersWithKidsEnabled,
            periodicGps = this[periodicGpsKey] ?: d.periodicGps,
            calmMessagesEnabled = this[calmMessagesEnabledKey] ?: d.calmMessagesEnabled,
            hapticsEnabled = this[hapticsEnabledKey] ?: d.hapticsEnabled,
            officialAlertCityScope = this[officialAlertCityScopeKey] ?: d.officialAlertCityScope,
            moraleMasterEnabled = this[moraleMasterEnabledKey] ?: d.moraleMasterEnabled,
            bootRestartEnabled = this[bootRestartEnabledKey] ?: d.bootRestartEnabled,
            sleepNoticeEnabled = this[sleepNoticeEnabledKey] ?: d.sleepNoticeEnabled,
            alertRegionMode = alertRegionMode,
            showBorders = this[showBordersKey] ?: d.showBorders,
            showRegionBorders = this[showRegionBordersKey] ?: d.showRegionBorders,
            settingsHintRemaining = this[settingsHintRemainingKey] ?: d.settingsHintRemaining,
            threatToggleHintRemaining = this[threatToggleHintRemainingKey] ?: d.threatToggleHintRemaining,
            shelterTipStage = (this[shelterTipRemainingKey] ?: d.shelterTipStage).coerceIn(0, 6),
            mapVisibleTypes = mapVisible,
            alertEnabledTypes = alertEnabled
        )
    }

    /**
     * Write a whole [AppIntensity] in one transaction. A single `edit` means one emission: a
     * dozen sequential setters would let collectors observe (and render) a half-applied setup.
     *
     * Map visibility is untouched on purpose — a preset decides what rings, never what is drawn.
     *
     * When the patch claims the night window, it drops the "Just let me sleep!" snapshot, exactly
     * like [editNightBell] does for a hand edit: the user has just said what the night should do,
     * so a restore they no longer want must not survive to be re-applied by the next tap.
     */
    suspend fun applyIntensity(patch: IntensityPatch) {
        store.edit { p ->
            p[sirenOverrideKey] = patch.sirenOverride
            p[criticalOfflineOverrideKey] = patch.criticalOfflineOverride
            p[criticalOfflineBypassSilentKey] = patch.criticalOfflineBypassSilent
            p[bootRestartEnabledKey] = patch.bootRestartEnabled
            p[periodicGpsKey] = patch.periodicGps
            p[batteryOnboardShownKey] = !patch.batteryAskEnabled
            p[notifyPolicyEnabledKey] = patch.notifyPolicyEnabled
            p[zonePolicyKey] = patch.zonePolicy.name
            p[digestWindowKey] = patch.digestWindow.name
            p[digestPerTypeKey] = patch.digestPerType
            p[digestMaxKey] = patch.digestMax.coerceIn(1, 10)
            p[fastRedMinKey] = patch.fastRedMin
            p[fastYellowMinKey] = patch.fastYellowMin
            p[slowRedArmedKey] = patch.slowRedArmed
            p[slowYellowArmedKey] = patch.slowYellowArmed
            p[fastRedArmedKey] = patch.fastRedArmed
            p[fastYellowArmedKey] = patch.fastYellowArmed
            p[followBulletKey] = patch.followBullet
            p[highQualityExplosionsKey] = patch.highQualityExplosions
            p[deathAnimationStyleKey] = patch.deathAnimationStyle.name
            p[sheltersEnabledKey] = patch.sheltersEnabled
            for (type in ThreatType.values()) {
                p[cachedBooleanKey("threat_alert_${type.name}")] = patch.alertTypes.contains(type)
            }
            patch.night?.let { n ->
                p.remove(nightSleepRestoreKey)
                p[nightZoneSirenOverrideKey] = n.zoneSirenOverride
                p[nightOfficialSirenOverrideKey] = n.officialSirenOverride
                p[nightOfficialRedEnabledKey] = n.officialRed
                p[nightOfficialYellowEnabledKey] = n.officialYellow
                p[nightSlowRedArmedKey] = n.slowRedArmed
                p[nightSlowYellowArmedKey] = n.slowYellowArmed
                p[nightFastRedArmedKey] = n.fastRedArmed
                p[nightFastYellowArmedKey] = n.fastYellowArmed
            }
        }
    }

    /**
     * Write a whole [AlertPersona] in one transaction, for the same reason as [applyIntensity]: a
     * dozen setter calls would let collectors render a half-applied persona.
     *
     * Claiming the night window drops the "Just let me sleep!" snapshot, exactly like [editNightBell]
     * does for a hand edit — the tap has just said what the night should do.
     */
    suspend fun applyAlertPersona(patch: AlertPersonaPatch) {
        store.edit { p ->
            p[officialRedAlertsKey] = patch.officialRed
            p[officialYellowAlertsKey] = patch.officialYellow
            p[officialAlertCityScopeKey] = patch.officialCityScope
            p[sirenOverrideKey] = patch.sirenOverride
            p[criticalOfflineOverrideKey] = patch.criticalOfflineOverride
            p[criticalOfflineBypassSilentKey] = patch.criticalOfflineBypassSilent
            p[bootRestartEnabledKey] = patch.bootRestartEnabled
            p[notifyPolicyEnabledKey] = patch.notifyPolicyEnabled
            p[zonePolicyKey] = patch.zonePolicy.name
            p[digestWindowKey] = patch.digestWindow.name
            p[digestPerTypeKey] = patch.digestPerType
            p[digestMaxKey] = patch.digestMax.coerceIn(1, 10)
            p[autoDismissAllClearKey] = patch.autoDismissAllClear
            p[fallingDebrisDelaySecKey] = patch.fallingDebrisDelaySec.coerceIn(0, 600)
            p[fastRedMinKey] = patch.fastRedMin
            p[fastYellowMinKey] = patch.fastYellowMin
            p[slowRedArmedKey] = patch.slowRedArmed
            p[slowYellowArmedKey] = patch.slowYellowArmed
            p[fastRedArmedKey] = patch.fastRedArmed
            p[fastYellowArmedKey] = patch.fastYellowArmed
            for (type in ThreatType.values()) {
                p[cachedBooleanKey("threat_alert_${type.name}")] = patch.alertTypes.contains(type)
            }
            p.remove(nightSleepRestoreKey)
            p[nightZoneSirenOverrideKey] = patch.night.zoneSirenOverride
            p[nightOfficialSirenOverrideKey] = patch.night.officialSirenOverride
            p[nightOfficialRedEnabledKey] = patch.night.officialRed
            p[nightOfficialYellowEnabledKey] = patch.night.officialYellow
            p[nightSlowRedArmedKey] = patch.night.slowRedArmed
            p[nightSlowYellowArmedKey] = patch.night.slowYellowArmed
            p[nightFastRedArmedKey] = patch.night.fastRedArmed
            p[nightFastYellowArmedKey] = patch.night.fastYellowArmed
        }
    }

    /**
     * The alerting state exactly as it stands now, shaped like the patch a persona writes. This is
     * what "Mine" restores, captured once just before the first preset tap on the page.
     */
    suspend fun alertPersonaSnapshot(): AlertPersonaPatch = preferences.first().alertPersonaSnapshot()

    /**
     * The intensity-owned state as it stands now — the "Mine" snapshot for Settings → Reliability.
     *
     * Night is deliberately null: the window is another page's state, and claiming it here would
     * drop a "Just let me sleep!" snapshot the user never asked this undo to touch.
     */
    suspend fun intensitySnapshot(): IntensityPatch = preferences.first().intensitySnapshot()

    suspend fun setSlowRedKm(km: Int) {
        store.edit { prefs ->
            prefs[slowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[slowRedKmKey] ?: UserPreferences.DEFAULT.slowRedKm
            val yellow = prefs[slowYellowKmKey] ?: UserPreferences.DEFAULT.slowYellowKm
            prefs[slowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    suspend fun setSlowYellowKm(km: Int) {
        store.edit { prefs ->
            val red = prefs[slowRedKmKey] ?: UserPreferences.DEFAULT.slowRedKm
            prefs[slowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    suspend fun setFastRedMin(min: Int) {
        store.edit { prefs ->
            prefs[fastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[fastRedMinKey] ?: UserPreferences.DEFAULT.fastRedMin
            val yellow = prefs[fastYellowMinKey] ?: UserPreferences.DEFAULT.fastYellowMin
            prefs[fastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    suspend fun setFastYellowMin(min: Int) {
        store.edit { prefs ->
            val red = prefs[fastRedMinKey] ?: UserPreferences.DEFAULT.fastRedMin
            prefs[fastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    suspend fun setSlowRedZoneArmed(armed: Boolean) {
        store.edit { it[slowRedArmedKey] = armed }
    }

    suspend fun setSlowYellowZoneArmed(armed: Boolean) {
        store.edit { it[slowYellowArmedKey] = armed }
    }

    suspend fun setFastRedZoneArmed(armed: Boolean) {
        store.edit { it[fastRedArmedKey] = armed }
    }

    suspend fun setFastYellowZoneArmed(armed: Boolean) {
        store.edit { it[fastYellowArmedKey] = armed }
    }

    suspend fun setAlertsArmed(armed: Boolean) {
        store.edit {
            it[slowRedArmedKey] = armed
            it[slowYellowArmedKey] = armed
            it[fastRedArmedKey] = armed
            it[fastYellowArmedKey] = armed
        }
    }

    suspend fun setOfficialRedAlertsEnabled(enabled: Boolean) {
        store.edit { it[officialRedAlertsKey] = enabled }
    }

    suspend fun setOfficialYellowAlertsEnabled(enabled: Boolean) {
        store.edit { it[officialYellowAlertsKey] = enabled }
    }

    suspend fun setOfficialAlertsEnabled(enabled: Boolean) {
        store.edit {
            it[officialRedAlertsKey] = enabled
            it[officialYellowAlertsKey] = enabled
        }
    }

    suspend fun setSirenOverride(override: Boolean) {
        store.edit { it[sirenOverrideKey] = override }
    }

    suspend fun setFallingDebrisDelaySec(sec: Int) {
        store.edit { it[fallingDebrisDelaySecKey] = sec.coerceIn(0, 600) }
    }

    suspend fun setAutoDismissAllClear(enabled: Boolean) {
        store.edit { it[autoDismissAllClearKey] = enabled }
    }

    suspend fun setThreatMapVisible(type: ThreatType, visible: Boolean) {
        store.edit { it[cachedBooleanKey("threat_map_${type.name}")] = visible }
    }

    suspend fun setThreatAlertsEnabled(type: ThreatType, enabled: Boolean) {
        store.edit {
            it[cachedBooleanKey("threat_alert_${type.name}")] = enabled
            if (enabled) it[cachedBooleanKey("threat_map_${type.name}")] = true
        }
    }

    suspend fun setThreatMapVisibleBatch(types: Set<ThreatType>, visible: Boolean) {
        store.edit { prefs ->
            for (type in types) prefs[cachedBooleanKey("threat_map_${type.name}")] = visible
        }
    }


    fun explainerSeen(id: String): Flow<Boolean> {
        val key = cachedBooleanKey("explainer_seen_$id")
        return store.data.map { prefs -> prefs[key] ?: false }
    }

    suspend fun setExplainerSeen(id: String, seen: Boolean) {
        store.edit { it[cachedBooleanKey("explainer_seen_$id")] = seen }
    }

    suspend fun resetAllTips() {
        store.edit { prefs ->
            prefs[settingsHintRemainingKey] = 3
            prefs[threatToggleHintRemainingKey] = 3
            prefs[shelterTipRemainingKey] = 0
            listOf("followMe", "nightMode", "officialAlerts", "sirenOverride", "threatToggles", "cardSize")
                .forEach { id -> prefs.remove(booleanPreferencesKey("explainer_seen_$id")) }
        }
    }

    suspend fun setDisclaimerCollapsed(collapsed: Boolean) {
        store.edit { it[disclaimerCollapsedKey] = collapsed }
    }

    suspend fun setDisclaimerReadCount(count: Int) {
        store.edit { it[disclaimerReadCountKey] = count }
    }

    suspend fun setFollowMe(follow: Boolean) {
        store.edit {
            it[followMeKey] = follow
            // Follow-me replaces the pin: leaving a stale pin behind resurfaced the old
            // city (and its latched official episode) after a later switch back.
            if (follow) it.remove(pinnedCityKey)
        }
    }

    suspend fun setPinnedCity(nameUa: String?) {
        store.edit {
            if (nameUa == null) it.remove(pinnedCityKey) else it[pinnedCityKey] = nameUa
        }
    }

    suspend fun setPinnedCityWithFollow(nameUa: String?) {
        store.edit {
            if (nameUa == null) it.remove(pinnedCityKey) else it[pinnedCityKey] = nameUa
            if (nameUa != null) it[followMeKey] = false
        }
    }

    suspend fun setCriticalOfflineOverride(enabled: Boolean) {
        store.edit { it[criticalOfflineOverrideKey] = enabled }
    }

    suspend fun setCriticalOfflineBypassSilent(enabled: Boolean) {
        store.edit { it[criticalOfflineBypassSilentKey] = enabled }
    }

    suspend fun setWizardCompleted(done: Boolean) {
        store.edit { it[wizardCompletedKey] = done }
    }

    suspend fun setSettingsHintRemaining(remaining: Int) {
        store.edit { it[settingsHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    suspend fun setThreatToggleHintRemaining(remaining: Int) {
        store.edit { it[threatToggleHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    suspend fun setShelterTipStage(stage: Int) {
        store.edit { it[shelterTipRemainingKey] = stage.coerceIn(0, 6) }
    }

    suspend fun setThreatCardSize(size: ThreatCardSize) {
        store.edit { it[threatCardSizeKey] = size.name }
    }

    suspend fun setThreatIconSet(set: ThreatIconSet) {
        store.edit { it[threatIconSetKey] = set.name }
    }

    suspend fun setThreatIconGrade(set: ThreatIconSet, grade: ThreatIconGrade) {
        store.edit { prefs ->
            val grades = parseIconGrades(prefs[threatIconGradesKey]).toMutableMap()
            if (grade == ThreatIconGrade.DEFAULT) grades.remove(set) else grades[set] = grade
            prefs[threatIconGradesKey] = grades.entries.joinToString(",") { "${it.key.name}:${it.value.name}" }
        }
    }

    /** "PHOTO:CYAN,ARMY:IR" → map. Unparseable entries are dropped, never fatal. */
    private fun parseIconGrades(raw: String?): Map<ThreatIconSet, ThreatIconGrade> =
        raw?.split(',')?.mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val set = ThreatIconSet.values().firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val grade = ThreatIconGrade.values().firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            set to grade
        }?.toMap() ?: emptyMap()

    suspend fun setOverlapMode(mode: OverlapMode) {
        store.edit { it[overlapModeKey] = mode.name }
    }

    suspend fun setMoralePersona(persona: MoralePersona) {
        store.edit { it[moralePersonaKey] = persona.name }
    }

    suspend fun setShowMapScale(show: Boolean) {
        store.edit { it[showMapScaleKey] = show }
    }

    suspend fun setShowMediumCities(show: Boolean) {
        store.edit { it[showMediumCitiesKey] = show }
    }

    suspend fun setShowSmallCities(show: Boolean) {
        store.edit { it[showSmallCitiesKey] = show }
    }

    suspend fun setSheltersEnabled(enabled: Boolean) {
        store.edit { it[sheltersEnabledKey] = enabled }
    }

    suspend fun setSheltersWithKidsEnabled(enabled: Boolean) {
        store.edit { it[sheltersWithKidsEnabledKey] = enabled }
    }

    suspend fun setPeriodicGps(enabled: Boolean) {
        store.edit { it[periodicGpsKey] = enabled }
    }

    suspend fun setCalmMessagesEnabled(enabled: Boolean) {
        store.edit { it[calmMessagesEnabledKey] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        store.edit { it[hapticsEnabledKey] = enabled }
    }

    suspend fun setOfficialAlertCityScope(enabled: Boolean) {
        store.edit { it[officialAlertCityScopeKey] = enabled }
    }

    suspend fun setMoraleMasterEnabled(enabled: Boolean) {
        store.edit { it[moraleMasterEnabledKey] = enabled }
    }

    suspend fun setWelcomeShootdownPlayed(played: Boolean) {
        store.edit { it[welcomeShootdownPlayedKey] = played }
    }

    suspend fun setBootRestartEnabled(enabled: Boolean) {
        store.edit { it[bootRestartEnabledKey] = enabled }
    }

    suspend fun setSleepNoticeEnabled(enabled: Boolean) {
        store.edit { it[sleepNoticeEnabledKey] = enabled }
    }

    suspend fun setAlertRegionMode(mode: AlertRegionMode) {
        store.edit { it[alertRegionModeKey] = mode.name }
    }

    suspend fun setShowBorders(enabled: Boolean) {
        store.edit { it[showBordersKey] = enabled }
    }

    suspend fun setShowRegionBorders(enabled: Boolean) {
        store.edit { it[showRegionBordersKey] = enabled }
    }

    suspend fun setShowLargeCities(show: Boolean) {
        store.edit { it[showLargeCitiesKey] = show }
    }

    suspend fun setNotifyPolicyEnabled(enabled: Boolean) {
        store.edit { it[notifyPolicyEnabledKey] = enabled }
    }

    suspend fun setZonePolicy(policy: ZonePolicy) {
        store.edit { it[zonePolicyKey] = policy.name }
    }

    suspend fun setDigestMax(max: Int) {
        store.edit { it[digestMaxKey] = max.coerceIn(1, 10) }
    }

    suspend fun setDigestWindow(window: DigestWindow) {
        store.edit { it[digestWindowKey] = window.name }
    }

    suspend fun setDigestPerType(perType: Boolean) {
        store.edit { it[digestPerTypeKey] = perType }
    }

    suspend fun setShowThreatIdsOnMap(show: Boolean) {
        store.edit { it[showThreatIdsOnMapKey] = show }
    }

    suspend fun setShowFeedbackButton(show: Boolean) {
        store.edit { it[showFeedbackButtonKey] = show }
    }

    suspend fun setHighQualityExplosions(enabled: Boolean) {
        store.edit { it[highQualityExplosionsKey] = enabled }
    }

    suspend fun setDeathAnimationEnabled(enabled: Boolean) {
        store.edit { it[deathAnimationEnabledKey] = enabled }
    }

    suspend fun setDeathAnimationStyle(style: DeathAnimationStyle) {
        store.edit { it[deathAnimationStyleKey] = style.name }
    }

    suspend fun setFlybyAnimationEnabled(enabled: Boolean) {
        store.edit { it[flybyAnimationEnabledKey] = enabled }
    }

    suspend fun setFollowBullet(enabled: Boolean) {
        store.edit { it[followBulletKey] = enabled }
    }

    suspend fun setThreatIconZoom(enabled: Boolean) {
        store.edit { it[threatIconZoomKey] = enabled }
    }

    suspend fun setNeutralizedTallyEnabled(enabled: Boolean) {
        store.edit { it[neutralizedTallyEnabledKey] = enabled }
    }

    suspend fun setNeutralizedTallyAllUkraine(enabled: Boolean) {
        store.edit { it[neutralizedTallyAllUkraineKey] = enabled }
    }

    suspend fun setAlarmEpisodeTallyEnabled(enabled: Boolean) {
        store.edit { it[alarmEpisodeTallyEnabledKey] = enabled }
    }

    suspend fun setLegacyCacheCleaned(cleaned: Boolean) {
        store.edit { it[legacyCacheCleanedKey] = cleaned }
    }

        suspend fun setBatteryOnboardShown(shown: Boolean) {
        store.edit { it[batteryOnboardShownKey] = shown }
    }

    suspend fun setServiceResurrected(resurrected: Boolean) {
        store.edit { it[serviceResurrectedKey] = resurrected }
    }

    suspend fun setPermissionPromptDeferred(deferred: Boolean) {
        store.edit { it[permissionPromptDeferredKey] = deferred }
    }

    suspend fun setWizardDeferred(deferred: Boolean) {
        store.edit {
            it[wizardCompletedKey] = deferred
            it[batteryOnboardShownKey] = deferred
            it[serviceResurrectedKey] = false
            it[permissionPromptDeferredKey] = deferred
        }
    }

    suspend fun setLanguage(lang: AppLanguage) {
        store.edit { it[languageKey] = lang.name }
    }

    suspend fun setNightEnabled(enabled: Boolean) {
        store.edit { it[nightEnabledKey] = enabled }
    }

    suspend fun setNightStartMin(min: Int) {
        store.edit { it[nightStartMinKey] = min.coerceIn(0, 1439) }
    }

    suspend fun setNightEndMin(min: Int) {
        store.edit { it[nightEndMinKey] = min.coerceIn(0, 1439) }
    }

    suspend fun setNightUseCustomZones(use: Boolean) {
        // Part of the preset: switching custom night zones off hands the night bells back to
        // the day ones, so it ends the sleep snapshot like any other night-alert switch.
        editNightBell { it[nightUseCustomZonesKey] = use }
    }

    suspend fun setNightSlowRedKm(km: Int) {
        store.edit { prefs ->
            prefs[nightSlowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[nightSlowRedKmKey] ?: UserPreferences.DEFAULT.nightSlowRedKm
            val yellow = prefs[nightSlowYellowKmKey] ?: UserPreferences.DEFAULT.nightSlowYellowKm
            prefs[nightSlowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    suspend fun setNightSlowYellowKm(km: Int) {
        store.edit { prefs ->
            val red = prefs[nightSlowRedKmKey] ?: UserPreferences.DEFAULT.nightSlowRedKm
            prefs[nightSlowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    suspend fun setNightFastRedMin(min: Int) {
        store.edit { prefs ->
            prefs[nightFastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[nightFastRedMinKey] ?: UserPreferences.DEFAULT.nightFastRedMin
            val yellow = prefs[nightFastYellowMinKey] ?: UserPreferences.DEFAULT.nightFastYellowMin
            prefs[nightFastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    suspend fun setNightFastYellowMin(min: Int) {
        store.edit { prefs ->
            val red = prefs[nightFastRedMinKey] ?: UserPreferences.DEFAULT.nightFastRedMin
            prefs[nightFastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    suspend fun setNightSlowRedArmed(armed: Boolean) {
        editNightBell { it[nightSlowRedArmedKey] = armed }
    }
    suspend fun setNightSlowRedZoneArmed(armed: Boolean) = setNightSlowRedArmed(armed)

    suspend fun setNightSlowYellowArmed(armed: Boolean) {
        editNightBell { it[nightSlowYellowArmedKey] = armed }
    }
    suspend fun setNightSlowYellowZoneArmed(armed: Boolean) = setNightSlowYellowArmed(armed)

    suspend fun setNightFastRedArmed(armed: Boolean) {
        editNightBell { it[nightFastRedArmedKey] = armed }
    }
    suspend fun setNightFastRedZoneArmed(armed: Boolean) = setNightFastRedArmed(armed)

    suspend fun setNightFastYellowArmed(armed: Boolean) {
        editNightBell { it[nightFastYellowArmedKey] = armed }
    }
    suspend fun setNightFastYellowZoneArmed(armed: Boolean) = setNightFastYellowArmed(armed)

    suspend fun setNightOwnAlertPolicy(own: Boolean) {
        // Not a preset key: it decides whether the preset's night policy bits apply at all, and
        // editing it must not throw away a restore the user may still want.
        store.edit { it[nightOwnAlertPolicyKey] = own }
    }

    suspend fun setNightZoneSirenOverride(override: Boolean) {
        editNightBell { it[nightZoneSirenOverrideKey] = override }
    }

    suspend fun setNightOfficialSirenOverride(override: Boolean) {
        editNightBell { it[nightOfficialSirenOverrideKey] = override }
    }

    suspend fun setNightOfficialAlertCityScope(enabled: Boolean) {
        store.edit { it[nightOfficialAlertCityScopeKey] = enabled }
    }

    suspend fun setNightOfficialRedEnabled(enabled: Boolean) {
        editNightBell { it[nightOfficialRedEnabledKey] = enabled }
    }

    suspend fun setNightOfficialYellowEnabled(enabled: Boolean) {
        editNightBell { it[nightOfficialYellowEnabledKey] = enabled }
    }

    /**
     * Any direct edit of a night alert switch ends the "Just let me sleep!" preset by
     * dropping its snapshot. The user is managing these bells themselves now, so the restore
     * they no longer want must not survive to be re-applied by the next tap — the old
     * behaviour overwrote the snapshot with the already-edited values and then "restored"
     * them, which silently lost the night settings the preset had captured.
     */
    private suspend fun editNightBell(edit: (MutablePreferences) -> Unit) {
        store.edit { p ->
            p.remove(nightSleepRestoreKey)
            edit(p)
        }
    }

    /**
     * "Just let me sleep!" — mute every night alert, remembering exactly what the night
     * settings were so the next tap puts them back. One atomic edit: the snapshot and the
     * muted values either both land or neither does.
     *
     * Turning it on also turns the night window on: the preset only mutes NIGHT alerts, so with
     * the window off the row would flip, say "Sleep well" and change nothing the user can see.
     * Off does not touch the window — the undo must not silently disable a mode the user set up.
     */
    suspend fun setNightSleep(enabled: Boolean) {
        store.edit { p ->
            if (enabled) {
                p[nightSleepRestoreKey] = nightSleepPresetOf(p).encode()
                applyNightSleep(p, NightSleepPreset.MUTED)
                p[nightEnabledKey] = true
            } else {
                applyNightSleep(p, NightSleepPreset.decode(p[nightSleepRestoreKey]) ?: NORMAL_NIGHT)
                p.remove(nightSleepRestoreKey)
            }
        }
    }

    /** The night settings currently stored, as the preset the sleep button snapshots. */
    private fun nightSleepPresetOf(p: Preferences): NightSleepPreset {
        val d = UserPreferences.DEFAULT
        return NightSleepPreset(
            useCustomZones = p[nightUseCustomZonesKey] ?: d.nightUseCustomZones,
            slowRedArmed = p[nightSlowRedArmedKey] ?: d.nightSlowRedArmed,
            slowYellowArmed = p[nightSlowYellowArmedKey] ?: d.nightSlowYellowArmed,
            fastRedArmed = p[nightFastRedArmedKey] ?: d.nightFastRedArmed,
            fastYellowArmed = p[nightFastYellowArmedKey] ?: d.nightFastYellowArmed,
            zoneSirenOverride = p[nightZoneSirenOverrideKey] ?: d.nightZoneSirenOverride,
            officialSirenOverride = p[nightOfficialSirenOverrideKey] ?: d.nightOfficialSirenOverride,
            officialRed = p[nightOfficialRedEnabledKey] ?: d.nightOfficialRedEnabled,
            officialYellow = p[nightOfficialYellowEnabledKey] ?: d.nightOfficialYellowEnabled,
            ownAlertPolicy = p[nightOwnAlertPolicyKey] ?: d.nightOwnAlertPolicy
        )
    }

    /** Write all ten night-sleep keys at once. */
    private fun applyNightSleep(p: MutablePreferences, preset: NightSleepPreset) {
        p[nightUseCustomZonesKey] = preset.useCustomZones
        p[nightSlowRedArmedKey] = preset.slowRedArmed
        p[nightSlowYellowArmedKey] = preset.slowYellowArmed
        p[nightFastRedArmedKey] = preset.fastRedArmed
        p[nightFastYellowArmedKey] = preset.fastYellowArmed
        p[nightZoneSirenOverrideKey] = preset.zoneSirenOverride
        p[nightOfficialSirenOverrideKey] = preset.officialSirenOverride
        p[nightOfficialRedEnabledKey] = preset.officialRed
        p[nightOfficialYellowEnabledKey] = preset.officialYellow
        p[nightOwnAlertPolicyKey] = preset.ownAlertPolicy
    }

    private companion object {
        /** Normal night, used when the silent state was reached without the button (no snapshot). */
        val NORMAL_NIGHT = NightSleepPreset(
            useCustomZones = false,
            slowRedArmed = true, slowYellowArmed = true,
            fastRedArmed = true, fastYellowArmed = false,
            zoneSirenOverride = false, officialSirenOverride = false,
            officialRed = true, officialYellow = true,
            ownAlertPolicy = false
        )
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }
}

/*
 * The two "what do the switches say right now" readers, as pure functions on the value:
 * the ViewModel already holds a [UserPreferences] in its combine, and deriving the selected setup
 * from it must not suspend. The UserPrefs members are the one-shot conveniences on top.
 */

/** Intensity-owned state, shaped like the patch a setup writes. Night stays null (see [IntensityPatch]). */
fun UserPreferences.intensitySnapshot(): IntensityPatch = IntensityPatch(
    sirenOverride = sirenOverride,
    criticalOfflineOverride = criticalOfflineOverride,
    criticalOfflineBypassSilent = criticalOfflineBypassSilent,
    bootRestartEnabled = bootRestartEnabled,
    periodicGps = periodicGps,
    batteryAskEnabled = !batteryOnboardShown,
    notifyPolicyEnabled = notifyPolicyEnabled,
    zonePolicy = zonePolicy,
    digestWindow = digestWindow,
    digestPerType = digestPerType,
    digestMax = digestMax,
    fastRedMin = fastRedMin,
    fastYellowMin = fastYellowMin,
    slowRedArmed = slowRedArmed,
    slowYellowArmed = slowYellowArmed,
    fastRedArmed = fastRedArmed,
    fastYellowArmed = fastYellowArmed,
    alertTypes = alertEnabledTypes,
    followBullet = followBullet,
    highQualityExplosions = highQualityExplosions,
    deathAnimationStyle = deathAnimationStyle,
    sheltersEnabled = sheltersEnabled
)

/** Alerting state, shaped like the patch a persona writes, night window included. */
fun UserPreferences.alertPersonaSnapshot(): AlertPersonaPatch = AlertPersonaPatch(
    officialRed = officialRedAlertsEnabled,
    officialYellow = officialYellowAlertsEnabled,
    officialCityScope = officialAlertCityScope,
    sirenOverride = sirenOverride,
    criticalOfflineOverride = criticalOfflineOverride,
    criticalOfflineBypassSilent = criticalOfflineBypassSilent,
    bootRestartEnabled = bootRestartEnabled,
    notifyPolicyEnabled = notifyPolicyEnabled,
    zonePolicy = zonePolicy,
    digestWindow = digestWindow,
    digestPerType = digestPerType,
    digestMax = digestMax,
    autoDismissAllClear = autoDismissAllClear,
    fallingDebrisDelaySec = fallingDebrisDelaySec,
    fastRedMin = fastRedMin,
    fastYellowMin = fastYellowMin,
    slowRedArmed = slowRedArmed,
    slowYellowArmed = slowYellowArmed,
    fastRedArmed = fastRedArmed,
    fastYellowArmed = fastYellowArmed,
    alertTypes = alertEnabledTypes,
    night = IntensityNight(
        zoneSirenOverride = nightZoneSirenOverride,
        officialSirenOverride = nightOfficialSirenOverride,
        officialRed = nightOfficialRedEnabled,
        officialYellow = nightOfficialYellowEnabled,
        slowRedArmed = nightSlowRedArmed,
        slowYellowArmed = nightSlowYellowArmed,
        fastRedArmed = nightFastRedArmed,
        fastYellowArmed = nightFastYellowArmed
    )
)
