package ua.ukrainedrones

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

enum class AppLanguage { UA, EN }

enum class ThreatCardSize { SMALL, LARGE }

enum class ThreatIconSet { PHOTO, ARMY, COMIC, RUSSIAN }

/** How same-coordinate threats render on the map. */
enum class OverlapMode { DEFAULT, GRID, SPREAD, COUNT }

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
    val officialAlertsEnabled: Boolean = true,
    val officialRedAlertsEnabled: Boolean = true,
    val yellowAlertsEnabled: Boolean = true,
    val sirenOverride: Boolean = false,
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
    val showSmallCities: Boolean = true,
    val showLargeCities: Boolean = true,
    val deathAnimationEnabled: Boolean = true,
    val followBullet: Boolean = true,
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
    val flybyAnimationEnabled: Boolean = true,
    val threatIconZoom: Boolean = true,
    val sheltersEnabled: Boolean = true,
    val sheltersWithKidsEnabled: Boolean = true,
    val periodicGps: Boolean = false,
    val calmMessagesEnabled: Boolean = true,
    val hapticsEnabled: Boolean? = null,
    val officialAlertCityScope: Boolean = false,
    val justFunMasterEnabled: Boolean = false,
    val bootRestartEnabled: Boolean = true,
    val fillAlertRegions: Boolean = false,
    val showBorders: Boolean = true,
    val showRegionBorders: Boolean = false,
    val settingsHintRemaining: Int = 3,
    val threatToggleHintRemaining: Int = 3,
    val flourishEjectHintRemaining: Int = 3,
    val shelterTipStage: Int = 0,
    val mapVisibleTypes: Set<ThreatType> = ThreatType.values().toSet(),
    val alertEnabledTypes: Set<ThreatType> = ThreatType.values().toSet()
) {
    val officialYellowAlertsEnabled: Boolean get() = yellowAlertsEnabled
    val iconSet: ThreatIconSet get() = threatIconSet
    val sheltersWithKids: Boolean get() = sheltersWithKidsEnabled
    val cardSize: ThreatCardSize get() = threatCardSize
}

class UserPrefs(private val context: Context) {

    private val keyCache = mutableMapOf<String, Preferences.Key<Boolean>>()
    private fun cachedBooleanKey(name: String): Preferences.Key<Boolean> =
        keyCache.getOrPut(name) { booleanPreferencesKey(name) }

    private val languageKey = stringPreferencesKey("app_language")
    private val languageChosenKey = booleanPreferencesKey("language_chosen")
    private val wizardCompletedKey = booleanPreferencesKey("wizard_completed")
    private val slowRedKmKey = intPreferencesKey("slow_red_km")
    private val slowYellowKmKey = intPreferencesKey("slow_yellow_km")
    private val fastRedMinKey = intPreferencesKey("fast_red_min")
    private val fastYellowMinKey = intPreferencesKey("fast_yellow_min")
    private val slowRedArmedKey = booleanPreferencesKey("slow_red_armed")
    private val slowYellowArmedKey = booleanPreferencesKey("slow_yellow_armed")
    private val fastRedArmedKey = booleanPreferencesKey("fast_red_armed")
    private val fastYellowArmedKey = booleanPreferencesKey("fast_yellow_armed")
    private val officialAlertsKey = booleanPreferencesKey("official_alerts_enabled")
    private val officialRedAlertsKey = booleanPreferencesKey("official_red_alerts_enabled")
    private val yellowAlertsKey = booleanPreferencesKey("yellow_alerts_enabled")
    private val sirenOverrideKey = booleanPreferencesKey("siren_override")
    private val disclaimerCollapsedKey = booleanPreferencesKey("disclaimer_collapsed")
    private val disclaimerReadCountKey = intPreferencesKey("disclaimer_read_count")
    private val followMeKey = booleanPreferencesKey("follow_me")
    private val pinnedCityKey = stringPreferencesKey("pinned_city")
    private val criticalOfflineOverrideKey = booleanPreferencesKey("critical_offline_override")
    private val criticalOfflineBypassSilentKey = booleanPreferencesKey("critical_offline_bypass_silent")
    private val settingsHintRemainingKey = intPreferencesKey("settings_hint_remaining")
    private val threatToggleHintRemainingKey = intPreferencesKey("threat_toggle_hint_remaining")
    private val flourishEjectHintRemainingKey = intPreferencesKey("flourish_eject_hint_remaining")
    private val shelterTipRemainingKey = intPreferencesKey("shelter_tip_remaining")
    private val threatCardSizeKey = stringPreferencesKey("threat_card_size")
    private val threatIconSetKey = stringPreferencesKey("threat_icon_set")
    private val overlapModeKey = stringPreferencesKey("threat_overlap_mode")
    private val showMapScaleKey = booleanPreferencesKey("show_map_scale")
    private val showMediumCitiesKey = booleanPreferencesKey("show_medium_cities")
    private val showSmallCitiesKey = booleanPreferencesKey("show_small_cities")
    private val deathAnimationEnabledKey = booleanPreferencesKey("death_animation_enabled")
    private val followBulletKey = booleanPreferencesKey("follow_bullet")
    private val neutralizedTallyEnabledKey = booleanPreferencesKey("neutralized_tally_enabled")
    private val neutralizedTallyAllUkraineKey = booleanPreferencesKey("neutralized_tally_all_ukraine")
    private val legacyCacheCleanedKey = booleanPreferencesKey("legacy_osmdroid_cleaned")
    private val fastGroupCollapsedKey = booleanPreferencesKey("fast_group_collapsed")
    private val slowGroupCollapsedKey = booleanPreferencesKey("slow_group_collapsed")
    private val batteryOnboardShownKey = booleanPreferencesKey("battery_onboard_shown")
    private val permissionPromptDeferredKey = booleanPreferencesKey("permission_prompt_deferred")
    private val nightEnabledKey = booleanPreferencesKey("night_enabled")
    private val nightStartMinKey = intPreferencesKey("night_start_min")
    private val nightEndMinKey = intPreferencesKey("night_end_min")
    private val nightUseCustomZonesKey = booleanPreferencesKey("night_use_custom_zones")
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
    private val flybyAnimationEnabledKey = booleanPreferencesKey("flyby_animation_enabled")
    private val threatIconZoomKey = booleanPreferencesKey("threat_icon_zoom")
    private val sheltersEnabledKey = booleanPreferencesKey("shelters_enabled")
    private val sheltersWithKidsEnabledKey = booleanPreferencesKey("shelters_with_kids_enabled")
    private val periodicGpsKey = booleanPreferencesKey("periodic_gps_enabled")
    private val calmMessagesEnabledKey = booleanPreferencesKey("calm_messages_enabled")
    private val hapticsEnabledKey = booleanPreferencesKey("haptics_enabled")
    private val officialAlertCityScopeKey = booleanPreferencesKey("official_alert_city_scope")
    private val justFunMasterEnabledKey = booleanPreferencesKey("just_fun_master_enabled")
    private val bootRestartEnabledKey = booleanPreferencesKey("boot_restart_enabled")
    private val fillAlertRegionsKey = booleanPreferencesKey("fill_alert_regions")
    private val showBordersKey = booleanPreferencesKey("show_borders")
    private val showRegionBordersKey = booleanPreferencesKey("show_region_borders")
    private val showLargeCitiesKey = booleanPreferencesKey("show_large_cities")

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { it.toUserPreferences() }.distinctUntilChanged()

    private fun Preferences.toUserPreferences(): UserPreferences {
        val lang = when (this[languageKey]) {
            "EN" -> AppLanguage.EN
            "UA" -> AppLanguage.UA
            else -> if (java.util.Locale.getDefault().language == "uk") AppLanguage.UA else AppLanguage.EN
        }
        val cardSize = this[threatCardSizeKey]?.let { stored ->
            ThreatCardSize.values().firstOrNull { it.name == stored }
        } ?: ThreatCardSize.LARGE
        val iconSet = this[threatIconSetKey]?.let { stored ->
            ThreatIconSet.values().firstOrNull { it.name == stored }
        } ?: ThreatIconSet.PHOTO
        val overlap = this[overlapModeKey]?.let { stored ->
            OverlapMode.values().firstOrNull { it.name == stored }
        } ?: OverlapMode.DEFAULT
        val mapVisible = ThreatType.values().filter { type ->
            this[cachedBooleanKey("threat_map_${type.name}")] ?: true
        }.toSet()
        val alertEnabled = ThreatType.values().filter { type ->
            this[cachedBooleanKey("threat_alert_${type.name}")] ?: true
        }.toSet()

        return UserPreferences(
            language = lang,
            languageChosen = this[languageChosenKey] ?: false,
            wizardCompleted = this[wizardCompletedKey] ?: (this[languageChosenKey] ?: false),
            slowRedKm = this[slowRedKmKey] ?: 20,
            slowYellowKm = this[slowYellowKmKey] ?: 50,
            fastRedMin = this[fastRedMinKey] ?: 5,
            fastYellowMin = this[fastYellowMinKey] ?: 20,
            slowRedArmed = this[slowRedArmedKey] ?: true,
            slowYellowArmed = this[slowYellowArmedKey] ?: true,
            fastRedArmed = this[fastRedArmedKey] ?: true,
            fastYellowArmed = this[fastYellowArmedKey] ?: true,
            officialAlertsEnabled = this[officialAlertsKey] ?: true,
            officialRedAlertsEnabled = this[officialRedAlertsKey] ?: true,
            yellowAlertsEnabled = this[yellowAlertsKey] ?: true,
            sirenOverride = this[sirenOverrideKey] ?: false,
            disclaimerCollapsed = this[disclaimerCollapsedKey] ?: false,
            disclaimerReadCount = this[disclaimerReadCountKey] ?: 0,
            followMe = this[followMeKey] ?: true,
            pinnedCity = this[pinnedCityKey],
            criticalOfflineOverride = this[criticalOfflineOverrideKey] ?: true,
            criticalOfflineBypassSilent = this[criticalOfflineBypassSilentKey] ?: false,
            threatCardSize = cardSize,
            threatIconSet = iconSet,
            overlapMode = overlap,
            showMapScale = this[showMapScaleKey] ?: true,
            showMediumCities = this[showMediumCitiesKey] ?: true,
            showSmallCities = this[showSmallCitiesKey] ?: true,
            showLargeCities = this[showLargeCitiesKey] ?: true,
            deathAnimationEnabled = this[deathAnimationEnabledKey] ?: true,
            followBullet = this[followBulletKey] ?: true,
            neutralizedTallyEnabled = this[neutralizedTallyEnabledKey] ?: true,
            neutralizedTallyAllUkraine = this[neutralizedTallyAllUkraineKey] ?: false,
            legacyCacheCleaned = this[legacyCacheCleanedKey] ?: false,
            fastGroupCollapsed = this[fastGroupCollapsedKey] ?: false,
            slowGroupCollapsed = this[slowGroupCollapsedKey] ?: false,
            batteryOnboardShown = this[batteryOnboardShownKey] ?: false,
            permissionPromptDeferred = this[permissionPromptDeferredKey] ?: false,
            nightEnabled = this[nightEnabledKey] ?: true,
            nightStartMin = this[nightStartMinKey] ?: (22 * 60),
            nightEndMin = this[nightEndMinKey] ?: (7 * 60),
            nightUseCustomZones = this[nightUseCustomZonesKey] ?: false,
            nightSlowRedKm = this[nightSlowRedKmKey] ?: 20,
            nightSlowYellowKm = this[nightSlowYellowKmKey] ?: 50,
            nightFastRedMin = this[nightFastRedMinKey] ?: 5,
            nightFastYellowMin = this[nightFastYellowMinKey] ?: 20,
            nightSlowRedArmed = this[nightSlowRedArmedKey] ?: true,
            nightSlowYellowArmed = this[nightSlowYellowArmedKey] ?: true,
            nightFastRedArmed = this[nightFastRedArmedKey] ?: true,
            nightFastYellowArmed = this[nightFastYellowArmedKey] ?: true,
            nightZoneSirenOverride = this[nightZoneSirenOverrideKey] ?: false,
            nightOfficialSirenOverride = this[nightOfficialSirenOverrideKey] ?: false,
            flybyAnimationEnabled = this[flybyAnimationEnabledKey] ?: true,
            threatIconZoom = this[threatIconZoomKey] ?: true,
            sheltersEnabled = this[sheltersEnabledKey] ?: true,
            sheltersWithKidsEnabled = this[sheltersWithKidsEnabledKey] ?: true,
            periodicGps = this[periodicGpsKey] ?: false,
            calmMessagesEnabled = this[calmMessagesEnabledKey] ?: true,
            hapticsEnabled = this[hapticsEnabledKey],
            officialAlertCityScope = this[officialAlertCityScopeKey] ?: false,
            justFunMasterEnabled = this[justFunMasterEnabledKey] ?: false,
            bootRestartEnabled = this[bootRestartEnabledKey] ?: true,
            fillAlertRegions = this[fillAlertRegionsKey] ?: false,
            showBorders = this[showBordersKey] ?: true,
            showRegionBorders = this[showRegionBordersKey] ?: false,
            settingsHintRemaining = this[settingsHintRemainingKey] ?: 3,
            threatToggleHintRemaining = this[threatToggleHintRemainingKey] ?: 3,
            flourishEjectHintRemaining = this[flourishEjectHintRemainingKey] ?: 3,
            shelterTipStage = (this[shelterTipRemainingKey] ?: 0).coerceIn(0, 6),
            mapVisibleTypes = mapVisible,
            alertEnabledTypes = alertEnabled
        )
    }

    suspend fun setSlowRedKm(km: Int) {
        context.dataStore.edit { prefs ->
            prefs[slowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[slowRedKmKey] ?: 20
            val yellow = prefs[slowYellowKmKey] ?: 50
            prefs[slowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    suspend fun setSlowYellowKm(km: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[slowRedKmKey] ?: 20
            prefs[slowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    suspend fun setFastRedMin(min: Int) {
        context.dataStore.edit { prefs ->
            prefs[fastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[fastRedMinKey] ?: 5
            val yellow = prefs[fastYellowMinKey] ?: 20
            prefs[fastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    suspend fun setFastYellowMin(min: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[fastRedMinKey] ?: 5
            prefs[fastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    suspend fun setSlowRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[slowRedArmedKey] = armed }
    }

    suspend fun setSlowYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[slowYellowArmedKey] = armed }
    }

    suspend fun setFastRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[fastRedArmedKey] = armed }
    }

    suspend fun setFastYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[fastYellowArmedKey] = armed }
    }

    suspend fun setOfficialAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[officialAlertsKey] = enabled }
    }

    suspend fun setOfficialRedAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[officialRedAlertsKey] = enabled }
    }

    suspend fun setYellowAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[yellowAlertsKey] = enabled }
    }

    suspend fun setSirenOverride(override: Boolean) {
        context.dataStore.edit { it[sirenOverrideKey] = override }
    }

    suspend fun setThreatMapVisible(type: ThreatType, visible: Boolean) {
        context.dataStore.edit { it[cachedBooleanKey("threat_map_${type.name}")] = visible }
    }

    suspend fun setThreatAlertsEnabled(type: ThreatType, enabled: Boolean) {
        context.dataStore.edit { it[cachedBooleanKey("threat_alert_${type.name}")] = enabled }
    }

    suspend fun setThreatMapVisibleBatch(types: Set<ThreatType>, visible: Boolean) {
        context.dataStore.edit { prefs ->
            for (type in types) prefs[cachedBooleanKey("threat_map_${type.name}")] = visible
        }
    }

    suspend fun setThreatAlertsEnabledBatch(types: Set<ThreatType>, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            for (type in types) {
                prefs[cachedBooleanKey("threat_alert_${type.name}")] = enabled
                if (enabled) prefs[cachedBooleanKey("threat_map_${type.name}")] = true
            }
        }
    }

    fun explainerSeen(id: String): Flow<Boolean> {
        val key = cachedBooleanKey("explainer_seen_$id")
        return context.dataStore.data.map { prefs -> prefs[key] ?: false }
    }

    suspend fun setExplainerSeen(id: String, seen: Boolean) {
        context.dataStore.edit { it[cachedBooleanKey("explainer_seen_$id")] = seen }
    }

    suspend fun resetAllTips() {
        context.dataStore.edit { prefs ->
            prefs[settingsHintRemainingKey] = 3
            prefs[threatToggleHintRemainingKey] = 3
            prefs[flourishEjectHintRemainingKey] = 3
            prefs[shelterTipRemainingKey] = 0
            listOf("followMe", "nightMode", "officialAlerts", "sirenOverride", "threatToggles", "cardSize")
                .forEach { id -> prefs.remove(booleanPreferencesKey("explainer_seen_$id")) }
        }
    }

    suspend fun setDisclaimerCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[disclaimerCollapsedKey] = collapsed }
    }

    suspend fun setDisclaimerReadCount(count: Int) {
        context.dataStore.edit { it[disclaimerReadCountKey] = count }
    }

    suspend fun setFollowMe(follow: Boolean) {
        context.dataStore.edit { it[followMeKey] = follow }
    }

    suspend fun setPinnedCity(nameUa: String?) {
        context.dataStore.edit {
            if (nameUa == null) it.remove(pinnedCityKey) else it[pinnedCityKey] = nameUa
        }
    }

    suspend fun setCriticalOfflineOverride(enabled: Boolean) {
        context.dataStore.edit { it[criticalOfflineOverrideKey] = enabled }
    }

    suspend fun setCriticalOfflineBypassSilent(enabled: Boolean) {
        context.dataStore.edit { it[criticalOfflineBypassSilentKey] = enabled }
    }

    suspend fun setLanguage(lang: AppLanguage) {
        context.dataStore.edit {
            it[languageKey] = lang.name
        }
    }

    suspend fun setLanguageChosen(chosen: Boolean) {
        context.dataStore.edit { it[languageChosenKey] = chosen }
    }

    suspend fun setWizardCompleted(done: Boolean) {
        context.dataStore.edit { it[wizardCompletedKey] = done }
    }

    suspend fun setSettingsHintRemaining(remaining: Int) {
        context.dataStore.edit { it[settingsHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    suspend fun setThreatToggleHintRemaining(remaining: Int) {
        context.dataStore.edit { it[threatToggleHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    suspend fun setFlourishEjectHintRemaining(remaining: Int) {
        context.dataStore.edit { it[flourishEjectHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    suspend fun setShelterTipStage(stage: Int) {
        context.dataStore.edit { it[shelterTipRemainingKey] = stage.coerceIn(0, 6) }
    }

    suspend fun setThreatCardSize(size: ThreatCardSize) {
        context.dataStore.edit { it[threatCardSizeKey] = size.name }
    }

    suspend fun setThreatIconSet(set: ThreatIconSet) {
        context.dataStore.edit { it[threatIconSetKey] = set.name }
    }

    suspend fun setOverlapMode(mode: OverlapMode) {
        context.dataStore.edit { it[overlapModeKey] = mode.name }
    }

    suspend fun setShowMapScale(show: Boolean) {
        context.dataStore.edit { it[showMapScaleKey] = show }
    }

    suspend fun setShowMediumCities(show: Boolean) {
        context.dataStore.edit { it[showMediumCitiesKey] = show }
    }

    suspend fun setShowSmallCities(show: Boolean) {
        context.dataStore.edit { it[showSmallCitiesKey] = show }
    }

    suspend fun setSheltersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[sheltersEnabledKey] = enabled }
    }

    suspend fun setSheltersWithKidsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[sheltersWithKidsEnabledKey] = enabled }
    }

    suspend fun setPeriodicGps(enabled: Boolean) {
        context.dataStore.edit { it[periodicGpsKey] = enabled }
    }

    suspend fun setCalmMessagesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[calmMessagesEnabledKey] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[hapticsEnabledKey] = enabled }
    }

    suspend fun setOfficialAlertCityScope(enabled: Boolean) {
        context.dataStore.edit { it[officialAlertCityScopeKey] = enabled }
    }

    suspend fun setJustFunMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[justFunMasterEnabledKey] = enabled }
    }

    suspend fun setBootRestartEnabled(enabled: Boolean) {
        context.dataStore.edit { it[bootRestartEnabledKey] = enabled }
    }

    suspend fun setFillAlertRegions(enabled: Boolean) {
        context.dataStore.edit { it[fillAlertRegionsKey] = enabled }
    }

    suspend fun setShowBorders(enabled: Boolean) {
        context.dataStore.edit { it[showBordersKey] = enabled }
    }

    suspend fun setShowRegionBorders(enabled: Boolean) {
        context.dataStore.edit { it[showRegionBordersKey] = enabled }
    }

    suspend fun setShowLargeCities(show: Boolean) {
        context.dataStore.edit { it[showLargeCitiesKey] = show }
    }

    suspend fun setDeathAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[deathAnimationEnabledKey] = enabled }
    }

    suspend fun setFlybyAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[flybyAnimationEnabledKey] = enabled }
    }

    suspend fun setFollowBullet(enabled: Boolean) {
        context.dataStore.edit { it[followBulletKey] = enabled }
    }

    suspend fun setThreatIconZoom(enabled: Boolean) {
        context.dataStore.edit { it[threatIconZoomKey] = enabled }
    }

    suspend fun setNeutralizedTallyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[neutralizedTallyEnabledKey] = enabled }
    }

    suspend fun setNeutralizedTallyAllUkraine(enabled: Boolean) {
        context.dataStore.edit { it[neutralizedTallyAllUkraineKey] = enabled }
    }

    suspend fun setLegacyCacheCleaned(cleaned: Boolean) {
        context.dataStore.edit { it[legacyCacheCleanedKey] = cleaned }
    }

    suspend fun setFastGroupCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[fastGroupCollapsedKey] = collapsed }
    }

    suspend fun setSlowGroupCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[slowGroupCollapsedKey] = collapsed }
    }

    suspend fun setBatteryOnboardShown(shown: Boolean) {
        context.dataStore.edit { it[batteryOnboardShownKey] = shown }
    }

    suspend fun setPermissionPromptDeferred(deferred: Boolean) {
        context.dataStore.edit { it[permissionPromptDeferredKey] = deferred }
    }

    suspend fun setNightEnabled(enabled: Boolean) {
        context.dataStore.edit { it[nightEnabledKey] = enabled }
    }

    suspend fun setNightStartMin(min: Int) {
        context.dataStore.edit { it[nightStartMinKey] = min.coerceIn(0, 1439) }
    }

    suspend fun setNightEndMin(min: Int) {
        context.dataStore.edit { it[nightEndMinKey] = min.coerceIn(0, 1439) }
    }

    suspend fun setNightUseCustomZones(use: Boolean) {
        context.dataStore.edit { it[nightUseCustomZonesKey] = use }
    }

    suspend fun setNightSlowRedKm(km: Int) {
        context.dataStore.edit { prefs ->
            prefs[nightSlowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[nightSlowRedKmKey] ?: 20
            val yellow = prefs[nightSlowYellowKmKey] ?: 50
            prefs[nightSlowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    suspend fun setNightSlowYellowKm(km: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[nightSlowRedKmKey] ?: 20
            prefs[nightSlowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    suspend fun setNightFastRedMin(min: Int) {
        context.dataStore.edit { prefs ->
            prefs[nightFastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[nightFastRedMinKey] ?: 5
            val yellow = prefs[nightFastYellowMinKey] ?: 20
            prefs[nightFastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    suspend fun setNightFastYellowMin(min: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[nightFastRedMinKey] ?: 5
            prefs[nightFastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    suspend fun setNightSlowRedArmed(armed: Boolean) {
        context.dataStore.edit { it[nightSlowRedArmedKey] = armed }
    }
    suspend fun setNightSlowRedZoneArmed(armed: Boolean) = setNightSlowRedArmed(armed)

    suspend fun setNightSlowYellowArmed(armed: Boolean) {
        context.dataStore.edit { it[nightSlowYellowArmedKey] = armed }
    }
    suspend fun setNightSlowYellowZoneArmed(armed: Boolean) = setNightSlowYellowArmed(armed)

    suspend fun setNightFastRedArmed(armed: Boolean) {
        context.dataStore.edit { it[nightFastRedArmedKey] = armed }
    }
    suspend fun setNightFastRedZoneArmed(armed: Boolean) = setNightFastRedArmed(armed)

    suspend fun setNightFastYellowArmed(armed: Boolean) {
        context.dataStore.edit { it[nightFastYellowArmedKey] = armed }
    }
    suspend fun setNightFastYellowZoneArmed(armed: Boolean) = setNightFastYellowArmed(armed)

    suspend fun setNightZoneSirenOverride(override: Boolean) {
        context.dataStore.edit { it[nightZoneSirenOverrideKey] = override }
    }

    suspend fun setNightOfficialSirenOverride(override: Boolean) {
        context.dataStore.edit { it[nightOfficialSirenOverrideKey] = override }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
