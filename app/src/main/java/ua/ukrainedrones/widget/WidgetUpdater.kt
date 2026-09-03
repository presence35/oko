package ua.ukrainedrones

import android.content.ComponentName
import android.content.Context
import android.appwidget.AppWidgetManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.connection.ConnectionState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.ZoneParams
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat

/**
 * Background bridge between the live app state and the home-screen widget. Runs inside
 * [AlertService] (the already-always-on monitor), so it costs no extra battery. Recomputes a
 * [WidgetSnapshot] whenever NEPTUN, GPS, prefs or a 30s clock tick changes, persists it to the
 * widget snapshot store, then triggers a Glance re-render only when a widget is actually placed.
 *
 * The widget itself is passive — it only reads the snapshot. All decision logic stays here via
 * [computeWidgetSnapshot], which calls the shared domain functions (mirror rule: see
 * ARCHITECTURE.md).
 */
object WidgetUpdater {

    private const val UPDATE_INTERVAL_MS = 30_000L

    private val Context.widgetSnapshotStore by preferencesDataStore(name = "widget_snapshot")

    object Keys {
        val threatCount = intPreferencesKey("threat_count")
        val typeCounts = stringPreferencesKey("type_counts")
        val activeZone = stringPreferencesKey("active_zone")
        val nearestKm = intPreferencesKey("nearest_km")
        val officialAlert = booleanPreferencesKey("official_alert")
        val sourceOnline = booleanPreferencesKey("source_online")
        val sourceDegraded = booleanPreferencesKey("source_degraded")
        val primaryId = stringPreferencesKey("primary_id")
        val primaryLat = stringPreferencesKey("primary_lat")
        val primaryLon = stringPreferencesKey("primary_lon")
        val primaryType = stringPreferencesKey("primary_type")
        val updatedAtMs = longPreferencesKey("updated_at_ms")
        val lang = stringPreferencesKey("lang")
        val iconSet = stringPreferencesKey("icon_set")
    }

    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            if (!hasPlacedWidgets(context)) return@launch
            val prefs = UserPrefs(context)
            val clock = MutableStateFlow(System.currentTimeMillis())
            launch {
                while (true) {
                    delay(UPDATE_INTERVAL_MS)
                    clock.value = System.currentTimeMillis()
                }
            }
            combine(
                combine(
                    combine(
                        ConnectionHolder.getClient(context).connectionState,
                        ConnectionHolder.getClient(context).threats,
                        ConnectionHolder.getClient(context).alerts
                    ) { cs, threats, alerts -> Triple(cs, threats, alerts) },
                    LocationTracker.location,
                    clock
                ) { core, gps, now -> Triple(core, gps, now) },
                combine(
                    prefs.slowRedKm(), prefs.slowYellowKm(),
                    prefs.fastRedMin(), prefs.fastYellowMin()
                ) { sr, sy, fr, fy -> ZoneParams(sr, sy, fr, fy) },
                combine(
                    prefs.followMe(), prefs.pinnedCity(), prefs.language(),
                    prefs.threatIconSet(), threatMapFlow(prefs)
                ) { follow, pinned, lang, iconSet, mapEnabled ->
                    Tail(follow, pinned, lang, iconSet, mapEnabled)
                }
            ) { core, params, tail ->
                val (cs, threats, alerts) = core.first as Triple<ConnectionState, Map<String, NormalizedThreat>, List<OblastAlert>>
                val gps = core.second
                val now = core.third
                val pinnedCity = tail.pinned?.let { name -> Cities.byUa[name] }
                val focus = if (tail.followMe) gps
                    else pinnedCity?.let { LatLng(it.lat, it.lon) } ?: gps
                val attribution = focusAttribution(tail.followMe, gps, pinnedCity)
                computeWidgetSnapshot(
                    cs = cs,
                    threats = threats,
                    alerts = alerts,
                    focus = focus,
                    token = attribution.token,
                    params = params,
                    mapEnabled = tail.mapEnabled,
                    now = now
                ) to Pair(tail.lang, tail.iconSet)
            }.collect { (snapshot, tail) ->
                persist(context, snapshot, tail.first, tail.second)
                if (hasPlacedWidgets(context)) {
                    ThreatWidget().updateAll(context)
                }
            }
        }
    }

    private fun hasPlacedWidgets(context: Context): Boolean {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ThreatWidgetReceiver::class.java))
        return ids.isNotEmpty()
    }

    private suspend fun persist(context: Context, snapshot: WidgetSnapshot, lang: AppLanguage, iconSet: ThreatIconSet) {
        context.widgetSnapshotStore.edit { prefs ->
            prefs[Keys.threatCount] = snapshot.threatCount
            prefs[Keys.typeCounts] = snapshot.typeCounts.entries.joinToString(",") { (type, n) -> "${type.name}=$n" }
            prefs[Keys.activeZone] = snapshot.activeZone?.name.orEmpty()
            prefs[Keys.nearestKm] = snapshot.nearestKm?.toInt() ?: -1
            prefs[Keys.officialAlert] = snapshot.officialAlert
            prefs[Keys.sourceOnline] = snapshot.sourceOnline
            prefs[Keys.sourceDegraded] = snapshot.sourceDegraded
            val pt = snapshot.primaryThreat
            if (pt != null) {
                prefs[Keys.primaryId] = pt.id
                prefs[Keys.primaryLat] = pt.lat.toString()
                prefs[Keys.primaryLon] = pt.lon.toString()
                prefs[Keys.primaryType] = pt.type.name
            } else {
                prefs.remove(Keys.primaryId)
                prefs.remove(Keys.primaryLat)
                prefs.remove(Keys.primaryLon)
                prefs.remove(Keys.primaryType)
            }
            prefs[Keys.updatedAtMs] = snapshot.updatedAtMs
            prefs[Keys.lang] = lang.name
            prefs[Keys.iconSet] = iconSet.name
        }
    }

    private data class Tail(
        val followMe: Boolean,
        val pinned: String?,
        val lang: AppLanguage,
        val iconSet: ThreatIconSet,
        val mapEnabled: Set<ThreatType>
    )

    suspend fun readSnapshot(context: Context): WidgetSnapshot {
        val prefs = context.widgetSnapshotStore.data.first()
        return WidgetSnapshot(
            threatCount = prefs[Keys.threatCount] ?: 0,
            typeCounts = parseTypeCounts(prefs[Keys.typeCounts].orEmpty()),
            activeZone = prefs[Keys.activeZone]?.let { zone ->
                ThreatZone.values().firstOrNull { it.name == zone }
            },
            nearestKm = prefs[Keys.nearestKm]?.takeIf { it >= 0 }?.toDouble(),
            officialAlert = prefs[Keys.officialAlert] ?: false,
            sourceOnline = prefs[Keys.sourceOnline] ?: false,
            sourceDegraded = prefs[Keys.sourceDegraded] ?: false,
            primaryThreat = prefs[Keys.primaryId]?.let { id ->
                val lat = prefs[Keys.primaryLat]?.toDoubleOrNull()
                val lon = prefs[Keys.primaryLon]?.toDoubleOrNull()
                val type = prefs[Keys.primaryType]?.let { t ->
                    ThreatType.values().firstOrNull { it.name == t }
                }
                if (lat != null && lon != null && type != null) {
                    WidgetThreat(id, lat, lon, type)
                } else null
            },
            updatedAtMs = prefs[Keys.updatedAtMs] ?: 0L
        )
    }

    suspend fun readLang(context: Context): AppLanguage {
        val prefs = context.widgetSnapshotStore.data.first()
        return prefs[Keys.lang]?.let { lang -> AppLanguage.values().firstOrNull { it.name == lang } }
            ?: AppLanguage.EN
    }

    suspend fun readIconSet(context: Context): ThreatIconSet {
        val prefs = context.widgetSnapshotStore.data.first()
        return prefs[Keys.iconSet]?.let { set -> ThreatIconSet.values().firstOrNull { it.name == set } }
            ?: ThreatIconSet.PHOTO
    }
}

private fun parseTypeCounts(serialized: String): Map<ThreatType, Int> {
    if (serialized.isBlank()) return emptyMap()
    val out = LinkedHashMap<ThreatType, Int>()
    for (part in serialized.split(',')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val type = ThreatType.values().firstOrNull { it.name == part.substring(0, eq) } ?: continue
        val n = part.substring(eq + 1).toIntOrNull() ?: continue
        if (n > 0) out[type] = n
    }
    return out
}
