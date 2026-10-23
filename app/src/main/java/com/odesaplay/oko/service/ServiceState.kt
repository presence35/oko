package com.odesaplay.oko.service

import com.odesaplay.oko.PreferenceStores
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val STORE_NAME = "service_state"

class ServiceState(private val context: Context) {

    /** Resolved through [PreferenceStores] so a test can sandbox it. See that file for why. */
    private val store by lazy { PreferenceStores.of(context, STORE_NAME) }

    private val keyCache = mutableMapOf<String, Preferences.Key<*>>()

    private val connLogKey = stringPreferencesKey("conn_log")
    private val gpsLogKey = stringPreferencesKey("gps_log")
    private val offlinePendingSinceKey = longPreferencesKey("offline_pending_since")
    private val ignoreRetryUntilKey = longPreferencesKey("ignore_retry_until")
    private val reconnectStartMillisKey = longPreferencesKey("reconnect_start_millis")
    private val officialAnnouncedTokenKey = stringPreferencesKey("official_announced_token")
    private val officialAnnouncedSinceKey = stringPreferencesKey("official_announced_since")
    private val officialAnnouncedReasonIdKey = stringPreferencesKey("official_announced_reason_id")
    private val officialAnnouncedCityKey = stringPreferencesKey("official_announced_city")
    private val activeZoneAlertsKey = stringPreferencesKey("active_zone_alerts")
    private val raidMuteDeadlineKey = stringPreferencesKey("raid_mute_deadline")
    private val officialAnnouncedRowKey = stringPreferencesKey("official_announced_row")
    private val debugLogKey = stringPreferencesKey("debug_log")
    private val monitorEventsKey = stringPreferencesKey("monitor_events")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val lastNotifiedUpdateCodeKey = longPreferencesKey("last_notified_update_code")
    private val lastSdkManifestHashKey = stringPreferencesKey("last_sdk_manifest_hash")
    private val systemLogKey = stringPreferencesKey("system_log")
    private val channelSchemaVersionKey = intPreferencesKey("channel_schema_version")
    private val criticalChannelBypassAppliedKey = booleanPreferencesKey("critical_channel_bypass_applied")

    fun connLog(): Flow<String> =
        store.data.map { prefs -> prefs[connLogKey] ?: "" }

    suspend fun setConnLog(serialized: String) {
        store.edit { it[connLogKey] = serialized }
    }

    fun gpsLog(): Flow<String> =
        store.data.map { prefs -> prefs[gpsLogKey] ?: "" }

    suspend fun setGpsLog(serialized: String) {
        store.edit { it[gpsLogKey] = serialized }
    }

    /** The full announcement row (`LEVEL|token|since|city[|cityUa|scoped]`), written as one value so
     *  the gate an episode was announced through survives a restart. The four separate keys are the
     *  older shape and are still read when this one is absent. */
    fun officialAnnouncedRow(): Flow<String> =
        store.data.map { prefs -> prefs[officialAnnouncedRowKey] ?: "" }

    suspend fun setOfficialAnnouncedRow(row: String?) {
        store.edit {
            if (row.isNullOrBlank()) it.remove(officialAnnouncedRowKey) else it[officialAnnouncedRowKey] = row
        }
    }

    /** Wall-clock deadline of the current timed raid mute, or "" when none. Persisted because a
     *  mute the OS recycles away silently is indistinguishable from a mute that never worked. */
    fun raidMuteDeadline(): Flow<String> =
        store.data.map { prefs -> prefs[raidMuteDeadlineKey] ?: "" }

    suspend fun setRaidMuteDeadline(row: String?) {
        store.edit {
            if (row.isNullOrBlank()) it.remove(raidMuteDeadlineKey) else it[raidMuteDeadlineKey] = row
        }
    }

    fun offlinePendingSince(): Flow<Long> =
        store.data.map { prefs -> prefs[offlinePendingSinceKey] ?: 0L }

    suspend fun setOfflinePendingSince(ts: Long) {
        store.edit { it[offlinePendingSinceKey] = ts }
    }

    fun ignoreRetryUntil(): Flow<Long> =
        store.data.map { prefs -> prefs[ignoreRetryUntilKey] ?: 0L }

    suspend fun setIgnoreRetryUntil(ts: Long) {
        store.edit { it[ignoreRetryUntilKey] = ts }
    }

    fun reconnectStartMillis(): Flow<Long> =
        store.data.map { prefs -> prefs[reconnectStartMillisKey] ?: 0L }

    suspend fun setReconnectStartMillis(ms: Long) {
        store.edit { it[reconnectStartMillisKey] = ms }
    }

    fun officialAnnouncedToken(): Flow<String> =
        store.data.map { prefs -> prefs[officialAnnouncedTokenKey] ?: "" }

    fun officialAnnouncedSince(): Flow<String> =
        store.data.map { prefs -> prefs[officialAnnouncedSinceKey] ?: "" }

    fun officialAnnouncedReasonId(): Flow<String> =
        store.data.map { prefs -> prefs[officialAnnouncedReasonIdKey] ?: "" }

    fun officialAnnouncedCity(): Flow<String> =
        store.data.map { prefs -> prefs[officialAnnouncedCityKey] ?: "" }

    suspend fun setOfficialAnnounced(token: String?, since: String?, reasonId: String?, city: String? = null) {
        store.edit {
            it[officialAnnouncedTokenKey] = token ?: ""
            it[officialAnnouncedSinceKey] = since ?: ""
            it[officialAnnouncedReasonIdKey] = reasonId ?: ""
            it[officialAnnouncedCityKey] = city ?: ""
        }
    }

    fun activeZoneAlerts(): Flow<String> =
        store.data.map { prefs -> prefs[activeZoneAlertsKey] ?: "" }

    suspend fun setActiveZoneAlerts(serialized: String) {
        store.edit { it[activeZoneAlertsKey] = serialized }
    }

    fun debugLog(): Flow<String> =
        store.data.map { prefs -> prefs[debugLogKey] ?: "" }

    suspend fun setDebugLog(serialized: String) {
        store.edit { it[debugLogKey] = serialized }
    }

    /** Serialized `MonitorLifecycle` rows. One value: it is appended and read as a whole. */
    fun monitorEvents(): Flow<String> =
        store.data.map { prefs -> prefs[monitorEventsKey] ?: "" }

    suspend fun setMonitorEvents(serialized: String) {
        store.edit { it[monitorEventsKey] = serialized }
    }

    fun lastUpdateCheck(): Flow<Long> =
        store.data.map { prefs -> prefs[lastUpdateCheckKey] ?: 0L }

    suspend fun setLastUpdateCheck(ts: Long) {
        store.edit { it[lastUpdateCheckKey] = ts }
    }

    fun lastNotifiedUpdateCode(): Flow<Long> =
        store.data.map { prefs -> prefs[lastNotifiedUpdateCodeKey] ?: 0L }

    suspend fun setLastNotifiedUpdateCode(code: Long) {
        store.edit { it[lastNotifiedUpdateCodeKey] = code }
    }

    fun lastSdkManifestHash(): Flow<String> =
        store.data.map { prefs -> prefs[lastSdkManifestHashKey] ?: "" }

    suspend fun setLastSdkManifestHash(hash: String) {
        store.edit { it[lastSdkManifestHashKey] = hash }
    }

    fun systemLog(): Flow<String> =
        store.data.map { prefs -> prefs[systemLogKey] ?: "" }

    suspend fun setSystemLog(serialized: String) {
        store.edit { it[systemLogKey] = serialized }
    }

    fun channelSchemaVersion(): Flow<Int> =
        store.data.map { prefs -> prefs[channelSchemaVersionKey] ?: 0 }

    suspend fun setChannelSchemaVersion(version: Int) {
        store.edit { it[channelSchemaVersionKey] = version }
    }

    fun criticalChannelBypassApplied(): Flow<Boolean?> =
        store.data.map { prefs -> prefs[criticalChannelBypassAppliedKey] }

    suspend fun setCriticalChannelBypassApplied(applied: Boolean) {
        store.edit { it[criticalChannelBypassAppliedKey] = applied }
    }

    /** Wipes every stored key, returning the store to its documented defaults. */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }
}
