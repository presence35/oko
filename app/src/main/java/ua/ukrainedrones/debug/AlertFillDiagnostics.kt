package ua.ukrainedrones.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import ua.ukrainedrones.Cities
import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.isOblastWide
import ua.ukrainedrones.engine.raionName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-contained diagnostic instrument for the alert region rendering pipeline.
 *
 * Captures and exposes runtime data across all 4 potential failure boundaries:
 * 1. Source alerts received (raw count, keys, names, oblasts)
 * 2. ThreatEngine token resolution (canonical boundary lookup vs dropped alerts)
 * 3. GeoJSON generation (feature count, byte length, empty sentinels)
 * 4. MapLibre Native GPU state (source registration, layer presence, style attachment)
 *
 * Completely isolated in this single file so it can be cleanly excised when diagnostics complete.
 */
object AlertFillDiagnostics {

    private const val TAG = "AlertFillDiag"

    data class State(
        val timestamp: Long = 0L,
        val fillAlertRegions: Boolean = false,
        val rawAlertsCount: Int = 0,
        val rawAlertsSummary: List<String> = emptyList(),
        val droppedAlerts: List<String> = emptyList(),
        val redOblastIds: Set<String> = emptySet(),
        val redRaions: Set<Pair<String, String>> = emptySet(),
        val yellowOblastIds: Set<String> = emptySet(),
        val yellowRaions: Set<Pair<String, String>> = emptySet(),
        val redFeatureCount: Int = 0,
        val redGeoJsonBytes: Int = 0,
        val yellowFeatureCount: Int = 0,
        val yellowGeoJsonBytes: Int = 0,
        val redSrcFound: Boolean = false,
        val yellowSrcFound: Boolean = false,
        val redLayerFound: Boolean = false,
        val yellowLayerFound: Boolean = false,
        val styleLoaded: Boolean = false,
        val recentLogs: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val logBuffer = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Inspects incoming raw alerts against canonical boundaries to catch dropped or unmapped alerts.
     */
    fun recordEngineAudit(
        alerts: List<OblastAlert>,
        fillEnabled: Boolean,
        redOblastIds: Set<String>,
        redRaions: Set<Pair<String, String>>
    ) {
        val stems = Cities.cityOblast.values
        val dropped = mutableListOf<String>()
        val summary = mutableListOf<String>()

        for (a in alerts) {
            val isWide = a.isOblastWide()
            summary.add("${if (isWide) "WIDE" else "RAION"}:${a.key}|${a.name}|${a.oblast}")
            if (isWide) {
                val stem = stems.firstOrNull { a.inOblast(it) }
                val id = stem?.let { CompactOblastBoundaries.canonicalId(it) }
                if (id == null) {
                    dropped.add("Wide alert unmatched: key='${a.key}', name='${a.name}', oblast='${a.oblast}', stem=$stem")
                }
            } else {
                val raion = a.raionName()
                val stem = stems.firstOrNull { a.inOblast(it) }
                val id = stem?.let { CompactOblastBoundaries.canonicalId(it) }
                val poly = if (id != null && raion != null) CompactRaionBoundaries.forKey(id, raion) else null
                if (poly == null) {
                    dropped.add("Raion alert unmapped: key='${a.key}', raionName='$raion', id=$id")
                }
            }
        }

        _state.value = _state.value.copy(
            rawAlertsCount = alerts.size,
            rawAlertsSummary = summary,
            droppedAlerts = dropped
        )
    }

    /**
     * Records the exact state and GeoJSON payloads delivered to MapLibre Native.
     */
    fun recordMapLibreUpdate(
        style: Style?,
        fillAlertRegions: Boolean,
        redOblastIds: Set<String>,
        redRaions: Set<Pair<String, String>>,
        filteredYellowOblastIds: Set<String>,
        filteredYellowRaions: Set<Pair<String, String>>,
        redGeoJson: String,
        yellowGeoJson: String
    ) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))

        val redSrcFound = style?.getSourceAs<GeoJsonSource>("src_alert_red") != null
        val yellowSrcFound = style?.getSourceAs<GeoJsonSource>("src_alert_yellow") != null
        val redLayerFound = style?.getLayer("lyr_alert_red_fill") != null
        val yellowLayerFound = style?.getLayer("lyr_alert_yellow") != null

        val redFeatures = countFeatures(redGeoJson)
        val yellowFeatures = countFeatures(yellowGeoJson)

        val logEntry = "$timeStr | fill=$fillAlertRegions red=${redOblastIds.size}o/${redRaions.size}r ($redFeatures f, ${redGeoJson.length}b) yell=${filteredYellowOblastIds.size}o/${filteredYellowRaions.size}r ($yellowFeatures f) gpu=[src:R$redSrcFound/Y$yellowSrcFound, lyr:R$redLayerFound/Y$yellowLayerFound]"
        Log.d(TAG, logEntry)

        synchronized(logBuffer) {
            logBuffer.add(logEntry)
            if (logBuffer.size > 50) logBuffer.removeAt(0)
        }

        _state.value = _state.value.copy(
            timestamp = now,
            fillAlertRegions = fillAlertRegions,
            redOblastIds = redOblastIds,
            redRaions = redRaions,
            yellowOblastIds = filteredYellowOblastIds,
            yellowRaions = filteredYellowRaions,
            redFeatureCount = redFeatures,
            redGeoJsonBytes = redGeoJson.length,
            yellowFeatureCount = yellowFeatures,
            yellowGeoJsonBytes = yellowGeoJson.length,
            redSrcFound = redSrcFound,
            yellowSrcFound = yellowSrcFound,
            redLayerFound = redLayerFound,
            yellowLayerFound = yellowLayerFound,
            styleLoaded = style != null,
            recentLogs = synchronized(logBuffer) { logBuffer.toList() }
        )
    }

    private fun countFeatures(geoJson: String): Int {
        val needle = "\"type\":\"Feature\""
        var count = 0
        var idx = 0
        while (true) {
            idx = geoJson.indexOf(needle, idx)
            if (idx == -1) break
            count++
            idx += needle.length
        }
        return count
    }

    fun buildFullDiagnosticReport(): String {
        val s = _state.value
        return buildString {
            appendLine("=== OKO ALERT FILL DIAGNOSTIC REPORT ===")
            appendLine("Report Time: ${timeFormat.format(Date(System.currentTimeMillis()))}")
            appendLine("Fill Regions Setting: ${if (s.fillAlertRegions) "ENABLED" else "DISABLED"}")
            appendLine("MapLibre Style Attached: ${s.styleLoaded}")
            appendLine("GPU Sources: src_alert_red=${s.redSrcFound}, src_alert_yellow=${s.yellowSrcFound}")
            appendLine("GPU Layers:  lyr_alert_red_fill=${s.redLayerFound}, lyr_alert_yellow=${s.yellowLayerFound}")
            appendLine("\n--- ALERT TOKENS & GEOJSON ---")
            appendLine("Red Oblasts (${s.redOblastIds.size}): ${s.redOblastIds}")
            appendLine("Red Raions  (${s.redRaions.size}): ${s.redRaions}")
            appendLine("Red GeoJSON Output: ${s.redFeatureCount} features, ${s.redGeoJsonBytes} bytes")
            appendLine("Yellow Oblasts (${s.yellowOblastIds.size}): ${s.yellowOblastIds}")
            appendLine("Yellow Raions  (${s.yellowRaions.size}): ${s.yellowRaions}")
            appendLine("Yellow GeoJSON Output: ${s.yellowFeatureCount} features, ${s.yellowGeoJsonBytes} bytes")
            appendLine("\n--- RAW ALERTS INGESTED (${s.rawAlertsCount}) ---")
            if (s.rawAlertsSummary.isEmpty()) {
                appendLine("(No active alerts reported by source)")
            } else {
                s.rawAlertsSummary.forEach { appendLine(" • $it") }
            }
            if (s.droppedAlerts.isNotEmpty()) {
                appendLine("\n--- ⚠️ UNMAPPED / DROPPED ALERTS (${s.droppedAlerts.size}) ---")
                s.droppedAlerts.forEach { appendLine(" ⚠️ $it") }
            }
            appendLine("\n--- PIPELINE EVENT LOGS (Last 25) ---")
            s.recentLogs.takeLast(25).forEach { appendLine(it) }
            appendLine("========================================")
        }
    }

    /**
     * Map HUD Composable (Approach 2)
     */
    @Composable
    fun Hud(modifier: Modifier = Modifier) {
        val diagState by state.collectAsState()
        val context = LocalContext.current
        var expanded by remember { mutableStateOf(false) }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xEE1A202C),
                border = BorderStroke(
                    1.dp,
                    when {
                        diagState.droppedAlerts.isNotEmpty() -> Color(0xFFEF4444)
                        diagState.redFeatureCount > 0 -> Color(0xFFF59E0B)
                        else -> Color(0x664B5563)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "DIAG",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Fill:${if (diagState.fillAlertRegions) "ON" else "OFF"}",
                                fontSize = 11.sp,
                                color = if (diagState.fillAlertRegions) Color(0xFF10B981) else Color(0xFF9CA3AF),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "R:[${diagState.redOblastIds.size}o,${diagState.redRaions.size}r->${diagState.redFeatureCount}f]",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (diagState.redFeatureCount > 0) Color(0xFFEF4444) else Color(0xFFD1D5DB),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Y:[${diagState.yellowOblastIds.size}o,${diagState.yellowRaions.size}r]",
                                fontSize = 11.sp,
                                color = Color(0xFFFBBF24),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "GPU: src[R:${if (diagState.redSrcFound) "✓" else "✗"},Y:${if (diagState.yellowSrcFound) "✓" else "✗"}] lyr[R:${if (diagState.redLayerFound) "✓" else "✗"},Y:${if (diagState.yellowLayerFound) "✓" else "✗"}] | ${diagState.redGeoJsonBytes}b",
                            fontSize = 10.sp,
                            color = Color(0xFF9CA3AF),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    IconButton(
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("Oko Alert Diagnostics", buildFullDiagnosticReport()))
                            Toast.makeText(context, "Copied diagnostic report to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy Diagnostics",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xF50D1117),
                    border = BorderStroke(1.dp, Color(0x664B5563)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = buildFullDiagnosticReport(),
                            fontSize = 10.sp,
                            color = Color(0xFFE5E7EB),
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }

    /**
     * In-App Logs Card Composable (Approach 1)
     */
    @Composable
    fun LogsCard(modifier: Modifier = Modifier) {
        val diagState by state.collectAsState()
        val context = LocalContext.current

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color(0x44888888)),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Alert Region Pipeline Diagnostics",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("Oko Alert Diagnostics", buildFullDiagnosticReport()))
                            Toast.makeText(context, "Copied diagnostic report", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy Diagnostics",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Fill: ${if (diagState.fillAlertRegions) "ENABLED" else "DISABLED"} | Alerts: ${diagState.rawAlertsCount} | Red: ${diagState.redOblastIds.size}o, ${diagState.redRaions.size}r (${diagState.redFeatureCount} features, ${diagState.redGeoJsonBytes}b) | Yellow: ${diagState.yellowOblastIds.size}o, ${diagState.yellowRaions.size}r",
                    fontSize = 11.sp,
                    color = Color(0xFFD1D5DB),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "MapLibre GPU: src[R:${diagState.redSrcFound}, Y:${diagState.yellowSrcFound}] lyr[R:${diagState.redLayerFound}, Y:${diagState.yellowLayerFound}]",
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF),
                    fontFamily = FontFamily.Monospace
                )
                if (diagState.droppedAlerts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠️ Unmapped alerts: ${diagState.droppedAlerts.joinToString("; ")}",
                        fontSize = 10.sp,
                        color = Color(0xFFEF4444),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
