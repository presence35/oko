package ua.ukrainedrones.service

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "service_state")

class ServiceState(private val context: Context) {

    private val keyCache = mutableMapOf<String, Preferences.Key<*>>()

    private val connLogKey = stringPreferencesKey("conn_log")
    private val connLogPendingSinceKey = longPreferencesKey("conn_log_pending_since")
    private val connLogPendingStatusKey = stringPreferencesKey("conn_log_pending_status")
    private val offlinePendingSinceKey = longPreferencesKey("offline_pending_since")
    private val ignoreRetryUntilKey = longPreferencesKey("ignore_retry_until")
    private val reconnectStartMillisKey = longPreferencesKey("reconnect_start_millis")
    private val officialAnnouncedTokenKey = stringPreferencesKey("official_announced_token")
    private val officialAnnouncedSinceKey = stringPreferencesKey("official_announced_since")
    private val officialAnnouncedReasonIdKey = stringPreferencesKey("official_announced_reason_id")
    private val activeZoneAlertsKey = stringPreferencesKey("active_zone_alerts")
    private val debugLogKey = stringPreferencesKey("debug_log")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val lastNotifiedUpdateCodeKey = longPreferencesKey("last_notified_update_code")
    private val lastSdkManifestHashKey = stringPreferencesKey("last_sdk_manifest_hash")
    private val systemLogKey = stringPreferencesKey("system_log")

    fun connLog(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[connLogKey] ?: "" }

    suspend fun setConnLog(serialized: String) {
        context.dataStore.edit { it[connLogKey] = serialized }
    }

    fun connLogPendingSince(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[connLogPendingSinceKey] ?: 0L }

    suspend fun setConnLogPendingSince(ts: Long) {
        context.dataStore.edit { it[connLogPendingSinceKey] = ts }
    }

    fun connLogPendingStatus(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[connLogPendingStatusKey] ?: "" }

    suspend fun setConnLogPendingStatus(status: String) {
        context.dataStore.edit { it[connLogPendingStatusKey] = status }
    }

    fun offlinePendingSince(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[offlinePendingSinceKey] ?: 0L }

    suspend fun setOfflinePendingSince(ts: Long) {
        context.dataStore.edit { it[offlinePendingSinceKey] = ts }
    }

    fun ignoreRetryUntil(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[ignoreRetryUntilKey] ?: 0L }

    suspend fun setIgnoreRetryUntil(ts: Long) {
        context.dataStore.edit { it[ignoreRetryUntilKey] = ts }
    }

    fun reconnectStartMillis(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[reconnectStartMillisKey] ?: 0L }

    suspend fun setReconnectStartMillis(ms: Long) {
        context.dataStore.edit { it[reconnectStartMillisKey] = ms }
    }

    fun officialAnnouncedToken(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[officialAnnouncedTokenKey] ?: "" }

    fun officialAnnouncedSince(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[officialAnnouncedSinceKey] ?: "" }

    fun officialAnnouncedReasonId(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[officialAnnouncedReasonIdKey] ?: "" }

    suspend fun setOfficialAnnounced(token: String?, since: String?, reasonId: String?) {
        context.dataStore.edit {
            it[officialAnnouncedTokenKey] = token ?: ""
            it[officialAnnouncedSinceKey] = since ?: ""
            it[officialAnnouncedReasonIdKey] = reasonId ?: ""
        }
    }

    fun activeZoneAlerts(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[activeZoneAlertsKey] ?: "" }

    suspend fun setActiveZoneAlerts(serialized: String) {
        context.dataStore.edit { it[activeZoneAlertsKey] = serialized }
    }

    fun debugLog(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[debugLogKey] ?: "" }

    suspend fun setDebugLog(serialized: String) {
        context.dataStore.edit { it[debugLogKey] = serialized }
    }

    fun lastUpdateCheck(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[lastUpdateCheckKey] ?: 0L }

    suspend fun setLastUpdateCheck(ts: Long) {
        context.dataStore.edit { it[lastUpdateCheckKey] = ts }
    }

    fun lastNotifiedUpdateCode(): Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[lastNotifiedUpdateCodeKey] ?: 0L }

    suspend fun setLastNotifiedUpdateCode(code: Long) {
        context.dataStore.edit { it[lastNotifiedUpdateCodeKey] = code }
    }

    fun lastSdkManifestHash(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[lastSdkManifestHashKey] ?: "" }

    suspend fun setLastSdkManifestHash(hash: String) {
        context.dataStore.edit { it[lastSdkManifestHashKey] = hash }
    }

    fun systemLog(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[systemLogKey] ?: "" }

    suspend fun setSystemLog(serialized: String) {
        context.dataStore.edit { it[systemLogKey] = serialized }
    }
}
