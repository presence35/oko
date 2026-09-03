package ua.ukrainedrones

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

enum class AppLanguage { UA, EN }

enum class ThreatCardSize { SMALL, LARGE }

enum class ThreatIconSet { PHOTO, ARMY, COMIC, RUSSIAN }

class UserPrefs(private val context: Context) {

    private val keyCache = mutableMapOf<String, Preferences.Key<*>>()
    private fun cachedBooleanKey(name: String): Preferences.Key<Boolean> =
        keyCache.getOrPut(name) { booleanPreferencesKey(name) } as Preferences.Key<Boolean>

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
    private val sheltersEnabledKey = booleanPreferencesKey("shelters_enabled")
    private val sheltersWithKidsEnabledKey = booleanPreferencesKey("shelters_with_kids_enabled")
    private val periodicGpsKey = booleanPreferencesKey("periodic_gps_enabled")
    private val calmMessagesEnabledKey = booleanPreferencesKey("calm_messages_enabled")
    private val hapticsEnabledKey = booleanPreferencesKey("haptics_enabled")
    private val officialAlertCityScopeKey = booleanPreferencesKey("official_alert_city_scope")
    private val justFunMasterEnabledKey = booleanPreferencesKey("just_fun_master_enabled")
    private val monitoringEnabledKey = booleanPreferencesKey("monitoring_enabled")

    fun slowRedKm(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[slowRedKmKey] ?: 20 }

    suspend fun setSlowRedKm(km: Int) {
        context.dataStore.edit { prefs ->
            prefs[slowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[slowRedKmKey] ?: 20
            val yellow = prefs[slowYellowKmKey] ?: 50
            prefs[slowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    fun slowYellowKm(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[slowYellowKmKey] ?: 50 }

    suspend fun setSlowYellowKm(km: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[slowRedKmKey] ?: 20
            prefs[slowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    fun fastRedMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[fastRedMinKey] ?: 5 }

    suspend fun setFastRedMin(min: Int) {
        context.dataStore.edit { prefs ->
            prefs[fastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[fastRedMinKey] ?: 5
            val yellow = prefs[fastYellowMinKey] ?: 20
            prefs[fastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    fun fastYellowMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[fastYellowMinKey] ?: 20 }

    suspend fun setFastYellowMin(min: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[fastRedMinKey] ?: 5
            prefs[fastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    fun slowRedZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[slowRedArmedKey] ?: true }

    suspend fun setSlowRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[slowRedArmedKey] = armed }
    }

    fun slowYellowZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[slowYellowArmedKey] ?: true }

    suspend fun setSlowYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[slowYellowArmedKey] = armed }
    }

    fun fastRedZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[fastRedArmedKey] ?: true }

    suspend fun setFastRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[fastRedArmedKey] = armed }
    }

    fun fastYellowZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[fastYellowArmedKey] ?: true }

    suspend fun setFastYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[fastYellowArmedKey] = armed }
    }

    fun officialAlertsEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[officialAlertsKey] ?: true }

    suspend fun setOfficialAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[officialAlertsKey] = enabled }
    }

    fun sirenOverride(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[sirenOverrideKey] ?: false }

    suspend fun setSirenOverride(override: Boolean) {
        context.dataStore.edit { it[sirenOverrideKey] = override }
    }

    fun threatMapVisible(type: ThreatType): Flow<Boolean> {
        val key = cachedBooleanKey("threat_map_${type.name}")
        return context.dataStore.data.map { prefs -> prefs[key] ?: true }
    }

    suspend fun setThreatMapVisible(type: ThreatType, visible: Boolean) {
        context.dataStore.edit { it[cachedBooleanKey("threat_map_${type.name}")] = visible }
    }

    fun threatAlertsEnabled(type: ThreatType): Flow<Boolean> {
        val key = cachedBooleanKey("threat_alert_${type.name}")
        return context.dataStore.data.map { prefs -> prefs[key] ?: true }
    }

    suspend fun setThreatAlertsEnabled(type: ThreatType, enabled: Boolean) {
        context.dataStore.edit { it[cachedBooleanKey("threat_alert_${type.name}")] = enabled }
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

    fun disclaimerCollapsed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[disclaimerCollapsedKey] ?: false }

    suspend fun setDisclaimerCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[disclaimerCollapsedKey] = collapsed }
    }

    fun disclaimerReadCount(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[disclaimerReadCountKey] ?: 0 }

    suspend fun setDisclaimerReadCount(count: Int) {
        context.dataStore.edit { it[disclaimerReadCountKey] = count }
    }

    fun followMe(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[followMeKey] ?: true }

    suspend fun setFollowMe(follow: Boolean) {
        context.dataStore.edit { it[followMeKey] = follow }
    }

    fun pinnedCity(): Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[pinnedCityKey] }

    suspend fun setPinnedCity(nameUa: String?) {
        context.dataStore.edit {
            if (nameUa == null) it.remove(pinnedCityKey) else it[pinnedCityKey] = nameUa
        }
    }

    fun criticalOfflineOverride(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[criticalOfflineOverrideKey] ?: true }

    suspend fun setCriticalOfflineOverride(enabled: Boolean) {
        context.dataStore.edit { it[criticalOfflineOverrideKey] = enabled }
    }

    fun criticalOfflineBypassSilent(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[criticalOfflineBypassSilentKey] ?: false }

    suspend fun setCriticalOfflineBypassSilent(enabled: Boolean) {
        context.dataStore.edit { it[criticalOfflineBypassSilentKey] = enabled }
    }

    fun language(): Flow<AppLanguage> =
        context.dataStore.data.map { prefs ->
            when (prefs[languageKey]) {
                "EN" -> AppLanguage.EN
                "UA" -> AppLanguage.UA
                else -> if (java.util.Locale.getDefault().language == "uk") AppLanguage.UA else AppLanguage.EN
            }
        }

    suspend fun setLanguage(lang: AppLanguage) {
        context.dataStore.edit {
            it[languageKey] = lang.name
        }
    }

    fun languageChosen(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[languageChosenKey] ?: false }

    suspend fun setLanguageChosen(chosen: Boolean) {
        context.dataStore.edit { it[languageChosenKey] = chosen }
    }

    fun wizardCompleted(): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[wizardCompletedKey] ?: (prefs[languageChosenKey] ?: false)
        }

    suspend fun setWizardCompleted(done: Boolean) {
        context.dataStore.edit { it[wizardCompletedKey] = done }
    }

    fun settingsHintRemaining(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[settingsHintRemainingKey] ?: 3 }

    suspend fun setSettingsHintRemaining(remaining: Int) {
        context.dataStore.edit { it[settingsHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    fun threatToggleHintRemaining(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[threatToggleHintRemainingKey] ?: 3 }

    suspend fun setThreatToggleHintRemaining(remaining: Int) {
        context.dataStore.edit { it[threatToggleHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    fun flourishEjectHintRemaining(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[flourishEjectHintRemainingKey] ?: 3 }

    suspend fun setFlourishEjectHintRemaining(remaining: Int) {
        context.dataStore.edit { it[flourishEjectHintRemainingKey] = remaining.coerceAtLeast(0) }
    }

    fun shelterTipStage(): Flow<Int> =
        context.dataStore.data.map { prefs -> (prefs[shelterTipRemainingKey] ?: 0).coerceIn(0, 6) }

    suspend fun setShelterTipStage(stage: Int) {
        context.dataStore.edit { it[shelterTipRemainingKey] = stage.coerceIn(0, 6) }
    }

    fun threatCardSize(): Flow<ThreatCardSize> =
        context.dataStore.data.map { prefs ->
            prefs[threatCardSizeKey]?.let { stored ->
                ThreatCardSize.values().firstOrNull { it.name == stored }
            } ?: ThreatCardSize.LARGE
        }

    suspend fun setThreatCardSize(size: ThreatCardSize) {
        context.dataStore.edit { it[threatCardSizeKey] = size.name }
    }

    fun threatIconSet(): Flow<ThreatIconSet> =
        context.dataStore.data.map { prefs ->
            prefs[threatIconSetKey]?.let { stored ->
                ThreatIconSet.values().firstOrNull { it.name == stored }
            } ?: ThreatIconSet.PHOTO
        }

    suspend fun setThreatIconSet(set: ThreatIconSet) {
        context.dataStore.edit { it[threatIconSetKey] = set.name }
    }

    fun showMapScale(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[showMapScaleKey] ?: true }

    suspend fun setShowMapScale(show: Boolean) {
        context.dataStore.edit { it[showMapScaleKey] = show }
    }

    fun showMediumCities(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[showMediumCitiesKey] ?: true }

    suspend fun setShowMediumCities(show: Boolean) {
        context.dataStore.edit { it[showMediumCitiesKey] = show }
    }

    fun showSmallCities(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[showSmallCitiesKey] ?: true }

    suspend fun setShowSmallCities(show: Boolean) {
        context.dataStore.edit { it[showSmallCitiesKey] = show }
    }

    fun sheltersEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[sheltersEnabledKey] ?: true }

    suspend fun setSheltersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[sheltersEnabledKey] = enabled }
    }

    fun sheltersWithKidsEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[sheltersWithKidsEnabledKey] ?: true }

    suspend fun setSheltersWithKidsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[sheltersWithKidsEnabledKey] = enabled }
    }

    fun periodicGps(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[periodicGpsKey] ?: false }

    suspend fun setPeriodicGps(enabled: Boolean) {
        context.dataStore.edit { it[periodicGpsKey] = enabled }
    }

    fun calmMessagesEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[calmMessagesEnabledKey] ?: true }

    suspend fun setCalmMessagesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[calmMessagesEnabledKey] = enabled }
    }

    fun hapticsEnabled(): Flow<Boolean?> =
        context.dataStore.data.map { prefs -> prefs[hapticsEnabledKey] }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[hapticsEnabledKey] = enabled }
    }

    fun officialAlertCityScope(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[officialAlertCityScopeKey] ?: false }

    suspend fun setOfficialAlertCityScope(enabled: Boolean) {
        context.dataStore.edit { it[officialAlertCityScopeKey] = enabled }
    }

    fun justFunMasterEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[justFunMasterEnabledKey] ?: false }

    suspend fun setJustFunMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[justFunMasterEnabledKey] = enabled }
    }

    /** Whether monitoring is enabled — set false by "Stop Monitoring & Exit", checked by BootReceiver. */
    fun monitoringEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[monitoringEnabledKey] ?: true }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[monitoringEnabledKey] = enabled }
    }

    fun deathAnimationEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[deathAnimationEnabledKey] ?: true }

    suspend fun setDeathAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[deathAnimationEnabledKey] = enabled }
    }

    fun flybyAnimationEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[flybyAnimationEnabledKey] ?: true }

    suspend fun setFlybyAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[flybyAnimationEnabledKey] = enabled }
    }

    fun followBullet(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[followBulletKey] ?: true }

    suspend fun setFollowBullet(enabled: Boolean) {
        context.dataStore.edit { it[followBulletKey] = enabled }
    }

    fun neutralizedTallyEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[neutralizedTallyEnabledKey] ?: true }

    suspend fun setNeutralizedTallyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[neutralizedTallyEnabledKey] = enabled }
    }

    fun neutralizedTallyAllUkraine(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[neutralizedTallyAllUkraineKey] ?: false }

    suspend fun setNeutralizedTallyAllUkraine(enabled: Boolean) {
        context.dataStore.edit { it[neutralizedTallyAllUkraineKey] = enabled }
    }

    fun legacyCacheCleaned(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[legacyCacheCleanedKey] ?: false }

    suspend fun setLegacyCacheCleaned(cleaned: Boolean) {
        context.dataStore.edit { it[legacyCacheCleanedKey] = cleaned }
    }

    fun fastGroupCollapsed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[fastGroupCollapsedKey] ?: false }

    suspend fun setFastGroupCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[fastGroupCollapsedKey] = collapsed }
    }

    fun slowGroupCollapsed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[slowGroupCollapsedKey] ?: false }

    suspend fun setSlowGroupCollapsed(collapsed: Boolean) {
        context.dataStore.edit { it[slowGroupCollapsedKey] = collapsed }
    }

    fun batteryOnboardShown(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[batteryOnboardShownKey] ?: false }

    suspend fun setBatteryOnboardShown(shown: Boolean) {
        context.dataStore.edit { it[batteryOnboardShownKey] = shown }
    }

    fun permissionPromptDeferred(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[permissionPromptDeferredKey] ?: false }

    suspend fun setPermissionPromptDeferred(deferred: Boolean) {
        context.dataStore.edit { it[permissionPromptDeferredKey] = deferred }
    }

    fun nightEnabled(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightEnabledKey] ?: true }

    suspend fun setNightEnabled(enabled: Boolean) {
        context.dataStore.edit { it[nightEnabledKey] = enabled }
    }

    fun nightStartMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightStartMinKey] ?: 22 * 60 }

    suspend fun setNightStartMin(min: Int) {
        context.dataStore.edit { it[nightStartMinKey] = min.coerceIn(0, 1439) }
    }

    fun nightEndMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightEndMinKey] ?: 7 * 60 }

    suspend fun setNightEndMin(min: Int) {
        context.dataStore.edit { it[nightEndMinKey] = min.coerceIn(0, 1439) }
    }

    fun nightUseCustomZones(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightUseCustomZonesKey] ?: false }

    suspend fun setNightUseCustomZones(use: Boolean) {
        context.dataStore.edit { it[nightUseCustomZonesKey] = use }
    }

    fun nightSlowRedKm(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightSlowRedKmKey] ?: 20 }

    suspend fun setNightSlowRedKm(km: Int) {
        context.dataStore.edit { prefs ->
            prefs[nightSlowRedKmKey] = km.coerceIn(1, 20)
            val red = prefs[nightSlowRedKmKey] ?: 20
            val yellow = prefs[nightSlowYellowKmKey] ?: 50
            prefs[nightSlowYellowKmKey] = yellow.coerceIn(red + 2, 50)
        }
    }

    fun nightSlowYellowKm(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightSlowYellowKmKey] ?: 50 }

    suspend fun setNightSlowYellowKm(km: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[nightSlowRedKmKey] ?: 20
            prefs[nightSlowYellowKmKey] = km.coerceIn(red + 2, 50)
        }
    }

    fun nightFastRedMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightFastRedMinKey] ?: 5 }

    suspend fun setNightFastRedMin(min: Int) {
        context.dataStore.edit { prefs ->
            prefs[nightFastRedMinKey] = min.coerceIn(1, 5)
            val red = prefs[nightFastRedMinKey] ?: 5
            val yellow = prefs[nightFastYellowMinKey] ?: 20
            prefs[nightFastYellowMinKey] = yellow.coerceIn(red + 2, 20)
        }
    }

    fun nightFastYellowMin(): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[nightFastYellowMinKey] ?: 20 }

    suspend fun setNightFastYellowMin(min: Int) {
        context.dataStore.edit { prefs ->
            val red = prefs[nightFastRedMinKey] ?: 5
            prefs[nightFastYellowMinKey] = min.coerceIn(red + 2, 20)
        }
    }

    fun nightSlowRedZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightSlowRedArmedKey] ?: true }

    suspend fun setNightSlowRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[nightSlowRedArmedKey] = armed }
    }

    fun nightSlowYellowZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightSlowYellowArmedKey] ?: true }

    suspend fun setNightSlowYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[nightSlowYellowArmedKey] = armed }
    }

    fun nightFastRedZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightFastRedArmedKey] ?: true }

    suspend fun setNightFastRedZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[nightFastRedArmedKey] = armed }
    }

    fun nightFastYellowZoneArmed(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightFastYellowArmedKey] ?: true }

    suspend fun setNightFastYellowZoneArmed(armed: Boolean) {
        context.dataStore.edit { it[nightFastYellowArmedKey] = armed }
    }

    fun nightZoneSirenOverride(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightZoneSirenOverrideKey] ?: false }

    suspend fun setNightZoneSirenOverride(override: Boolean) {
        context.dataStore.edit { it[nightZoneSirenOverrideKey] = override }
    }

    fun nightOfficialSirenOverride(): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[nightOfficialSirenOverrideKey] ?: false }

    suspend fun setNightOfficialSirenOverride(override: Boolean) {
        context.dataStore.edit { it[nightOfficialSirenOverrideKey] = override }
    }
}

fun threatMapFlow(prefs: UserPrefs): Flow<Set<ThreatType>> {
    return combine(ThreatType.values().map { prefs.threatMapVisible(it) }) { visible ->
        ThreatType.values().filterIndexed { i, _ -> visible[i] }.toSet()
    }
}

fun threatAlertFlow(prefs: UserPrefs): Flow<Set<ThreatType>> {
    return combine(ThreatType.values().map { prefs.threatAlertsEnabled(it) }) { enabled ->
        ThreatType.values().filterIndexed { i, _ -> enabled[i] }.toSet()
    }
}
