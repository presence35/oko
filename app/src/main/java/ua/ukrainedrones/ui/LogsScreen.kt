package ua.ukrainedrones

import android.os.Build
import ua.ukrainedrones.engine.ThreatZone
import android.content.Intent
import android.net.Uri
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.connection.ConnEvent
import ua.ukrainedrones.connection.ConnRetryState
import ua.ukrainedrones.connection.NeptunConnectionClient
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.BatteryOptimization
import androidx.compose.ui.platform.LocalContext

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DebugRed = Color(0xFFE57373)
private val DebugAmber = Color(0xFFF9A825)
private val DebugGreen = Color(0xFF4CAF50)
private val DebugBlue = Color(0xFF64B5F6)

/** Rows shown at once; the double-arrow button reveals [VISIBLE_STEP] more. */
private const val VISIBLE_INITIAL = 25
private const val VISIBLE_STEP = 50

/** Which data source to show. */
private enum class LogsFilter { DECISIONS, CONNECTIONS, SYSTEM, TESTS }

/** How to group decision rows. */
private enum class GroupBy { TIMELINE, PROXIMITY, TYPE }

/** Sort within Proximity groups. */
private enum class ProximitySort { DISTANCE, AGE }

/** Accent for a group header. */
private enum class GroupAccent { OFFICIAL, RED, YELLOW, OBLAST, LEFT }

/** Rows of a single threat type inside a proximity group. */
private data class TypeSubGroup(
    val type: ThreatType?,
    val entries: List<DebugLogEntry>
)

/** One rendered group of decision rows. [title] null = timeline list, no header. */
private data class LogGroupSpec(
    val id: String,
    val title: String?,
    val accent: GroupAccent?,
    val headerType: ThreatType?,
    val entries: List<DebugLogEntry>,
    /** Threat-type sub-headers inside the group (proximity mode only). */
    val subTypes: Boolean
)

/** A unified row for the log list — any of the data sources. */
private sealed interface LogRow {
    val atMillis: Long
}

private data class DecisionRow(val entry: DebugLogEntry) : LogRow {
    override val atMillis: Long get() = entry.atMillis
}

private data class ConnectionRow(val entry: ConnLogEntry) : LogRow {
    override val atMillis: Long get() = entry.atMillis
}

private data class SystemRow(val entry: SystemEntry) : LogRow {
    override val atMillis: Long get() = entry.atMillis
}

/**
 * Logs drop-down sheet: a top sheet that slides DOWN from the top bar (mirroring
 * how the alert zones sheet slides UP from the bottom).
 */
@Composable
fun LogsDropDownSheet(
    s: Strings.StringSet,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    neptunDown: Boolean,
    degraded: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by DebugLog.entries.collectAsState()
    val connEntries by ConnectionLog.entries.collectAsState()
    val systemEntries by ApiMonitor.entries.collectAsState()
    val context = LocalContext.current
    val connRetry by ConnectionHolder.getSupervisor(context).retryState.collectAsState()
    val connEvents by ConnectionHolder.getSupervisor(context).connEvents.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var filter by rememberSaveable { mutableStateOf(LogsFilter.DECISIONS) }
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.PROXIMITY) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var proximitySort by rememberSaveable { mutableStateOf(ProximitySort.DISTANCE) }
    var shownOnly by rememberSaveable { mutableStateOf(true) }
    var showFlourish by rememberSaveable { mutableStateOf(false) }
    var legendExpanded by rememberSaveable { mutableStateOf(false) }
    var visibleCount by remember { mutableIntStateOf(VISIBLE_INITIAL) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val window = entries.filter { now - it.atMillis < DebugLog.AUTO_CLEAR_AGE_MS }
    val isDecisions = filter == LogsFilter.DECISIONS
    val isSystem = filter == LogsFilter.SYSTEM
    val isTests = filter == LogsFilter.TESTS
    val rows: List<LogRow> = buildRows(window, connEntries, systemEntries, now, isDecisions, isSystem, isTests, newestFirst, shownOnly, showFlourish)
    val visible = rows.take(visibleCount)
    val hasMore = visibleCount < rows.size
    val groups = if (isDecisions) buildGroups(visible.filterIsInstance<DecisionRow>().map { it.entry }, groupBy, showFlourish, proximitySort, newestFirst) else emptyList()
    val subtitle = if (isDecisions) String.format(s.logsSubtitleFormat, rows.size) else null

    val connColor = when {
        neptunDown -> Color(0xFFE57373)
        degraded -> Color(0xFFFB8C00)
        else -> Color(0xFF4CAF50)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .background(Color(0xFF1E1E1E))
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(connColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                s.logsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Image(
                painter = painterResource(R.drawable.neptun),
                contentDescription = s.attributionText,
                modifier = Modifier.height(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                NeptunConnectionClient.NEPTUN_DOMAIN,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF90CAF9),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(NeptunConnectionClient.NEPTUN_SITE_URL)
                        )
                    )
                }
            )
            val closeInteraction = remember { MutableInteractionSource() }
            IconButton(
                onClick = onClose,
                interactionSource = closeInteraction,
                modifier = Modifier.pressTick(closeInteraction)
            ) {
                Icon(Icons.Filled.Close, contentDescription = s.closeButton, tint = Color.White)
            }
        }

        // Tabs
        val tabFilters = listOf(LogsFilter.DECISIONS, LogsFilter.CONNECTIONS, LogsFilter.SYSTEM, LogsFilter.TESTS)
        val tabLabels = listOf(s.logsFilterDecisions, s.logsFilterConnections, s.logsFilterSystem, s.logsFilterTests)
        ScrollableTabRow(
            selectedTabIndex = tabFilters.indexOf(filter),
            containerColor = Color(0xFF252525),
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp
        ) {
            tabFilters.forEachIndexed { index, f ->
                Tab(
                    selected = filter == f,
                    onClick = { filter = f; visibleCount = VISIBLE_INITIAL },
                    text = { Text(tabLabels[index]) }
                )
            }
        }

        if (isDecisions) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                LegendRow(s, legendExpanded) { legendExpanded = !legendExpanded }
            }
        }

        // Scrollable Log Content
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isDecisions) {
                item(key = "viewopts") {
                    ViewOptionsRow(
                        groupBy = groupBy,
                        newestFirst = newestFirst,
                        proximitySort = proximitySort,
                        shownOnly = shownOnly,
                        showFlourish = showFlourish,
                        s = s,
                        onGroupBy = {
                            groupBy = it
                            visibleCount = VISIBLE_INITIAL
                        },
                        onSortToggle = { newestFirst = !newestFirst },
                        onProximitySortChange = {
                            proximitySort = it
                            visibleCount = VISIBLE_INITIAL
                        },
                        onShownOnlyChange = {
                            shownOnly = it
                            visibleCount = VISIBLE_INITIAL
                        },
                        onShowFlourishChange = {
                            showFlourish = it
                            visibleCount = VISIBLE_INITIAL
                        }
                    )
                }
                if (subtitle != null) {
                    item(key = "subtitle") {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                        )
                    }
                }
            }
            if (filter == LogsFilter.TESTS) {
                item(key = "testoem") {
                    OemSimButton(context, s)
                }
            }
            if (filter == LogsFilter.CONNECTIONS && connEvents.isNotEmpty()) {
                item(key = "retrylog") {
                    RetryLogCard(connEvents, connRetry, s, now) { ConnectionHolder.getSupervisor(context).dismissLogCard() }
                }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        when (filter) {
                            LogsFilter.CONNECTIONS -> s.logsEmptyConnections
                            LogsFilter.SYSTEM -> s.apiSystemEmpty
                            LogsFilter.TESTS -> s.logsEmptyConnections
                            else -> s.debugLogEmpty
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp)
                    )
                }
            } else if (groups.isNotEmpty()) {
                groups.forEach { group ->
                    if (group.title != null) {
                        item(key = "header-${group.id}") {
                            GroupHeader(group, s)
                        }
                    }
                    if (group.subTypes) {
                        group.entries.groupBy { it.threatType ?: ThreatType.UNKNOWN }
                            .entries
                            .sortedBy { it.key.ordinal }
                            .forEach { (type, subEntries) ->
                                item(key = "sub-${group.id}-$type") {
                                    TypeSubHeader(TypeSubGroup(type, subEntries), lang, iconSet)
                                }
                                items(subEntries, key = { "row-${it.atMillis}-${it.threatId}" }) { entry ->
                                    DecisionCard(entry, s, lang, now, iconSet)
                                }
                            }
                    } else {
                        items(group.entries, key = { "row-${it.atMillis}-${it.threatId}" }) { entry ->
                            DecisionCard(entry, s, lang, now, iconSet)
                        }
                    }
                }
            } else {
                items(visible, key = { "flat-${it.atMillis}-${it::class.simpleName}" }) { row ->
                    LogRowCard(row, s, lang, now, iconSet)
                }
            }
            if (visible.isNotEmpty()) {
                if (hasMore) {
                    item(key = "more") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ShowMoreButton(s) { visibleCount += VISIBLE_STEP }
                        }
                    }
                }
                if (isDecisions || isSystem) {
                    item(key = "clear") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { if (isSystem) ApiMonitor.clear() else DebugLog.clear() } }) {
                                Text(s.debugLogClear)
                            }
                        }
                    }
                }
            }
        }

        // Swipe-up drag handle to dismiss
        val density = LocalDensity.current
        val dismissThresholdPx = with(density) { 60.dp.toPx() }
        var dragAccum by remember { mutableFloatStateOf(0f) }
        val dismissInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pressTick(dismissInteraction)
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = ripple(bounded = true),
                    onClick = onClose
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragAccum < -dismissThresholdPx) onClose()
                            dragAccum = 0f
                        },
                        onDragCancel = { dragAccum = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            SheetDragHandle()
        }
    }
}

/**
 * Full-screen logs wrapper fallback if accessed directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    s: Strings.StringSet,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    onBack: () -> Unit
) {
    LogsDropDownSheet(
        s = s,
        lang = lang,
        iconSet = iconSet,
        neptunDown = false,
        degraded = false,
        onClose = onBack,
        modifier = Modifier.fillMaxHeight(1f)
    )
}

/**
 * Assemble the row list for the active filter, ordered per [newestFirst]. The Decisions view
 * drops rows whose notification was not shown when [shownOnly] and hides shoot-down
 * flourish entries when [showFlourish] is false; connection rows include the live in-progress episode.
 */
private fun buildRows(
    decisions: List<DebugLogEntry>,
    connEntries: List<ConnLogEntry>,
    systemEntries: List<SystemEntry>,
    now: Long,
    isDecisions: Boolean,
    isSystem: Boolean,
    isTests: Boolean,
    newestFirst: Boolean,
    shownOnly: Boolean,
    showFlourish: Boolean
): List<LogRow> {
    if (isTests) return emptyList()
    if (isSystem) {
        val sysRows = systemEntries.map { SystemRow(it) }
        return if (newestFirst) sysRows.sortedByDescending { it.atMillis } else sysRows.sortedBy { it.atMillis }
    }
    if (!isDecisions) {
        val connRows = (ConnectionLog.currentEpisode(now)?.let { listOf(ConnectionRow(it)) }
            ?: emptyList()) + connEntries.map { ConnectionRow(it) }
        return if (newestFirst) connRows.sortedByDescending { it.atMillis } else connRows.sortedBy { it.atMillis }
    }
    var filtered: List<DebugLogEntry> = decisions
    if (!showFlourish) filtered = filtered.filter { it.kind != DebugLogKind.FLOURISH }
    if (shownOnly) filtered = filtered.filter { it.notified }
    val rows = filtered.map { DecisionRow(it) }
    return if (newestFirst) rows.sortedByDescending { it.atMillis } else rows.sortedBy { it.atMillis }
}

/**
 * Build the ordered group specs from sorted decision rows. Canonical group order regardless of
 * sort direction: proximity = official / red / yellow / oblast / left; type = official / types /
 * left. Timeline returns a single header-less spec. Flourish group only included when [showFlourish].
 * In Proximity, entries inside each bucket are sorted by distance (closest first) or age per [proximitySort].
 */
private fun buildGroups(
    rows: List<DebugLogEntry>,
    groupBy: GroupBy,
    showFlourish: Boolean,
    proximitySort: ProximitySort = ProximitySort.DISTANCE,
    newestFirst: Boolean = true
): List<LogGroupSpec> {
    if (rows.isEmpty()) return emptyList()
    fun sortProximity(list: List<DebugLogEntry>): List<DebugLogEntry> = when (proximitySort) {
        ProximitySort.DISTANCE -> list.sortedWith(compareBy<DebugLogEntry> { it.distanceKm ?: Double.MAX_VALUE }.thenByDescending { it.atMillis })
        ProximitySort.AGE -> if (newestFirst) list.sortedByDescending { it.atMillis } else list.sortedBy { it.atMillis }
    }
    return when (groupBy) {
        GroupBy.TIMELINE -> listOf(LogGroupSpec("timeline", null, null, null, rows, subTypes = false))
        GroupBy.PROXIMITY -> {
            val official = sortProximity(rows.filter { it.kind == DebugLogKind.OFFICIAL_ON || it.kind == DebugLogKind.OFFICIAL_OFF })
            val flourish = if (showFlourish) sortProximity(rows.filter { it.kind == DebugLogKind.FLOURISH }) else emptyList()
            val threat = rows.filter {
                it.kind == DebugLogKind.ZONE_ENTER ||
                    it.kind == DebugLogKind.ZONE_EXIT ||
                    it.kind == DebugLogKind.REGION_THREAT
            }
            val left = sortProximity(threat.filter { it.kind == DebugLogKind.ZONE_EXIT || it.distanceKm == null })
            val rest = threat.filter { it.kind != DebugLogKind.ZONE_EXIT && it.distanceKm != null }
            val red = sortProximity(rest.filter { it.tier == ThreatZone.INNER })
            val yellow = sortProximity(rest.filter { it.tier == ThreatZone.OUTER })
            val oblast = sortProximity(rest.filter { it.tier == null })
            buildList {
                if (official.isNotEmpty()) add(LogGroupSpec("official", "official", GroupAccent.OFFICIAL, null, official, subTypes = false))
                if (flourish.isNotEmpty()) add(LogGroupSpec("flourish", "flourish", GroupAccent.LEFT, null, flourish, subTypes = false))
                if (red.isNotEmpty()) add(LogGroupSpec("red", "red", GroupAccent.RED, null, red, subTypes = true))
                if (yellow.isNotEmpty()) add(LogGroupSpec("yellow", "yellow", GroupAccent.YELLOW, null, yellow, subTypes = true))
                if (oblast.isNotEmpty()) add(LogGroupSpec("oblast", "oblast", GroupAccent.OBLAST, null, oblast, subTypes = true))
                if (left.isNotEmpty()) add(LogGroupSpec("left", "left", GroupAccent.LEFT, null, left, subTypes = false))
            }
        }
        GroupBy.TYPE -> {
            val official = rows.filter { it.kind == DebugLogKind.OFFICIAL_ON || it.kind == DebugLogKind.OFFICIAL_OFF }
            val flourish = if (showFlourish) rows.filter { it.kind == DebugLogKind.FLOURISH } else emptyList()
            val exits = rows.filter {
                it.kind == DebugLogKind.ZONE_EXIT &&
                    it.kind != DebugLogKind.OFFICIAL_ON && it.kind != DebugLogKind.OFFICIAL_OFF
            }
            val typed = rows.filter { it !in official && it !in exits && it.threatType != null && it.kind != DebugLogKind.FLOURISH }
            buildList {
                if (official.isNotEmpty()) add(LogGroupSpec("official", "official", GroupAccent.OFFICIAL, null, official, subTypes = false))
                if (flourish.isNotEmpty()) add(LogGroupSpec("flourish", "flourish", GroupAccent.LEFT, null, flourish, subTypes = false))
                typed.groupBy { it.threatType!! }
                    .entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (type, groupRows) ->
                        add(LogGroupSpec("type-${type.name}", null, null, type, groupRows, subTypes = false))
                    }
                if (exits.isNotEmpty()) add(LogGroupSpec("left", "left", GroupAccent.LEFT, null, exits, subTypes = false))
            }
        }
    }
}

@Composable
private fun ViewOptionsRow(
    groupBy: GroupBy,
    newestFirst: Boolean,
    proximitySort: ProximitySort,
    shownOnly: Boolean,
    showFlourish: Boolean,
    s: Strings.StringSet,
    onGroupBy: (GroupBy) -> Unit,
    onSortToggle: () -> Unit,
    onProximitySortChange: (ProximitySort) -> Unit,
    onShownOnlyChange: (Boolean) -> Unit,
    onShowFlourishChange: (Boolean) -> Unit
) {
    val groupIcon = mapOf(
        GroupBy.TIMELINE to Icons.Outlined.AccessTime,
        GroupBy.PROXIMITY to Icons.Outlined.NearMe,
        GroupBy.TYPE to Icons.Outlined.Category
    )
    val groupLabel = mapOf(
        GroupBy.TIMELINE to s.logsGroupTimeline,
        GroupBy.PROXIMITY to s.logsGroupProximity,
        GroupBy.TYPE to s.logsGroupType
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GroupBy.entries.forEach { value ->
                FilterChip(
                    selected = groupBy == value,
                    onClick = { onGroupBy(value) },
                    label = { Text(groupLabel[value]!!) },
                    leadingIcon = {
                        Icon(groupIcon[value]!!, contentDescription = groupLabel[value], modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (groupBy == GroupBy.PROXIMITY) {
                FilterChip(
                    selected = proximitySort == ProximitySort.DISTANCE,
                    onClick = { onProximitySortChange(ProximitySort.DISTANCE) },
                    label = { Text(s.logsSortDistance) },
                    leadingIcon = { Icon(Icons.Filled.Place, contentDescription = s.logsSortDistance, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = proximitySort == ProximitySort.AGE,
                    onClick = { onProximitySortChange(ProximitySort.AGE) },
                    label = { Text(s.logsSortAge) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = s.logsSortAge, modifier = Modifier.size(16.dp)) }
                )
            } else {
                val sortRotation by animateFloatAsState(targetValue = if (newestFirst) 0f else 180f, label = "sortRotation")
                FilterChip(
                    selected = newestFirst,
                    onClick = onSortToggle,
                    label = { Text(if (newestFirst) s.logsSortNewest else s.logsSortOldest) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = if (newestFirst) s.logsSortNewest else s.logsSortOldest, modifier = Modifier.graphicsLayer { rotationZ = sortRotation }.size(16.dp)) }
                )
            }
            FilterChip(
                selected = shownOnly,
                onClick = { onShownOnlyChange(!shownOnly) },
                label = { Text(s.logsNotified) },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = s.logsNotified, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = showFlourish,
                onClick = { onShowFlourishChange(!showFlourish) },
                label = { Text(s.logsFlourishToggle) },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = s.logsFlourishToggle, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
private fun LegendRow(s: Strings.StringSet, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                s.logsLegend,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(Icons.Filled.LightMode, s.debugLogDay)
                    LegendItem(Icons.Filled.DarkMode, s.debugLogNight)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(Icons.Filled.Notifications, s.debugLogSoundFollows)
                    LegendItem(Icons.AutoMirrored.Filled.VolumeUp, s.debugLogSoundOverride)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(Icons.Filled.CheckCircle, s.debugLogShown)
                    LegendItem(Icons.Outlined.History, s.debugReasonStale)
                }
            }
        }
    }
}

@Composable
private fun LegendItem(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupHeader(group: LogGroupSpec, s: Strings.StringSet) {
    val accent = when (group.accent) {
        GroupAccent.OFFICIAL, GroupAccent.RED -> DebugRed
        GroupAccent.YELLOW -> DebugAmber
        GroupAccent.OBLAST -> DebugBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (group.title) {
        "official" -> s.debugGroupOfficial
        "flourish" -> s.debugKindFlourish
        "red" -> s.debugTierRed
        "yellow" -> s.debugTierYellow
        "oblast" -> s.logsProxOblast
        "left" -> s.debugGroupLeft
        else -> group.title ?: ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.weight(1f)
        )
        Text(
            String.format(s.debugBandCountFormat, group.entries.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeSubHeader(sub: TypeSubGroup, lang: AppLanguage, iconSet: ThreatIconSet) {
    val type = sub.type ?: return
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreatIcon(type = type, set = iconSet, size = 16.dp, contentDescription = label)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShowMoreButton(s: Strings.StringSet, onMore: () -> Unit) {
    TextButton(onClick = onMore) {
        Text(s.logsShowMore)
        Spacer(Modifier.width(2.dp))
        DoubleArrowDown()
    }
}

/** Double-chevron-down glyph for the "show more" control. */
@Composable
private fun DoubleArrowDown() {
    Box {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .offset(x = 0.dp, y = (-4).dp)
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .offset(x = 0.dp, y = 4.dp)
        )
    }
}

@Composable
private fun LogRowCard(
    row: LogRow,
    s: Strings.StringSet,
    lang: AppLanguage,
    now: Long,
    iconSet: ThreatIconSet
) {
    when (row) {
        is DecisionRow -> DecisionCard(row.entry, s, lang, now, iconSet)
        is ConnectionRow -> ConnectionCard(row.entry, s, lang, now)
        is SystemRow -> SystemCard(row.entry, s, lang, now)
    }
}

@Composable
private fun DebugLogKind.accent(tier: ThreatZone?): Color = when (this) {
    DebugLogKind.OFFICIAL_ON -> DebugRed
    DebugLogKind.OFFICIAL_OFF -> DebugGreen
    DebugLogKind.ZONE_ENTER -> when (tier) {
        ThreatZone.INNER -> DebugRed
        ThreatZone.OUTER -> DebugAmber
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun DebugLogKind.icon(): ImageVector = when (this) {
    DebugLogKind.OFFICIAL_ON -> Icons.Filled.Warning
    DebugLogKind.OFFICIAL_OFF -> Icons.Filled.CheckCircle
    DebugLogKind.ZONE_ENTER -> Icons.Filled.Warning
    DebugLogKind.ZONE_EXIT -> Icons.Filled.Close
    DebugLogKind.REGION_THREAT -> Icons.Filled.Place
    DebugLogKind.FLOURISH -> Icons.Filled.Star
}

private fun DebugLogKind.label(
    threatType: ThreatType?,
    locality: String?,
    lang: AppLanguage,
    s: Strings.StringSet
): String = when (this) {
    DebugLogKind.OFFICIAL_ON -> s.debugKindOfficialOn
    DebugLogKind.OFFICIAL_OFF -> s.debugKindOfficialOff
    DebugLogKind.ZONE_ENTER -> {
        val typeLabel = threatType?.let {
            val info = ThreatTypeCatalog.INFO.getValue(it)
            if (lang == AppLanguage.UA) info.labelUa else info.labelEn
        }
        val loc = localityText(locality, lang)
        when {
            typeLabel != null && loc != null -> "$typeLabel \u00B7 $loc"
            typeLabel != null -> typeLabel
            loc != null -> "${s.debugKindZoneEnter} \u00B7 $loc"
            else -> s.debugKindZoneEnter
        }
    }
    DebugLogKind.ZONE_EXIT -> {
        val typeLabel = threatType?.let {
            val info = ThreatTypeCatalog.INFO.getValue(it)
            if (lang == AppLanguage.UA) info.labelUa else info.labelEn
        }
        val loc = localityText(locality, lang)
        when {
            typeLabel != null && loc != null -> "$typeLabel \u00B7 ${s.debugKindZoneExit} \u00B7 $loc"
            typeLabel != null -> "$typeLabel \u00B7 ${s.debugKindZoneExit}"
            loc != null -> "${s.debugKindZoneExit} \u00B7 $loc"
            else -> s.debugKindZoneExit
        }
    }
    DebugLogKind.REGION_THREAT -> {
        val typeLabel = threatType?.let {
            val info = ThreatTypeCatalog.INFO.getValue(it)
            if (lang == AppLanguage.UA) info.labelUa else info.labelEn
        }
        val loc = localityText(locality, lang)
        when {
            typeLabel != null && loc != null -> "$typeLabel \u00B7 $loc"
            typeLabel != null -> typeLabel
            loc != null -> "${s.debugKindRegionThreat} \u00B7 $loc"
            else -> s.debugKindRegionThreat
        }
    }
    DebugLogKind.FLOURISH -> s.debugKindFlourish
}

private fun localityText(locality: String?, lang: AppLanguage): String? =
    locality?.let { if (lang == AppLanguage.UA) it else Cities.byUa[it]?.nameEn ?: Transliteration.transliterate(it) }

private fun DebugLogReason.label(s: Strings.StringSet): String = when (this) {
    DebugLogReason.BELL_MUTED -> s.debugReasonBellMuted
    DebugLogReason.ALREADY_NOTIFIED -> s.debugReasonAlreadyNotified
    DebugLogReason.COALESCED -> s.debugReasonCoalesced
    DebugLogReason.TYPE_OFF -> s.debugReasonTypeOff
    DebugLogReason.ADVISORY -> s.debugReasonAdvisory
    DebugLogReason.STALE -> s.debugReasonStale
    DebugLogReason.OUTSIDE_ZONES -> s.debugReasonOutsideZones
    DebugLogReason.TOGGLE_OFF -> s.debugReasonToggleOff
    DebugLogReason.LEFT -> s.debugReasonLeft
    DebugLogReason.FIRED -> ""
}

@Composable
private fun DecisionCard(
    entry: DebugLogEntry,
    s: Strings.StringSet,
    lang: AppLanguage,
    now: Long,
    iconSet: ThreatIconSet
) {
    val accent = entry.kind.accent(entry.tier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        DecisionLeadingIcon(entry, accent, lang, iconSet)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.kind.label(entry.threatType, entry.locality, lang, s),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatAlertAge(now, entry.atMillis, s),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.distanceKm?.let { km ->
                    Text(
                        String.format(s.logDistanceFormat, km.roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatDateTime(lang, entry.atMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.night) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    contentDescription = if (entry.night) s.debugLogNight else s.debugLogDay,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (entry.sirenOverride) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Notifications,
                    contentDescription = if (entry.sirenOverride) s.debugLogSoundOverride else s.debugLogSoundFollows,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.weight(1f))
                if (entry.notified) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = s.debugLogShown,
                        tint = DebugGreen,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    if (entry.reason == DebugLogReason.STALE) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = s.debugReasonStale,
                            tint = DebugAmber,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_notifications_off),
                            contentDescription = String.format(s.debugLogSuppressed, entry.reason.label(s)),
                            colorFilter = ColorFilter.tint(DebugAmber),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionLeadingIcon(entry: DebugLogEntry, accent: Color, lang: AppLanguage, iconSet: ThreatIconSet) {
    if (entry.kind == DebugLogKind.OFFICIAL_ON) {
        Image(
            painter = painterResource(R.drawable.ic_trident),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.size(22.dp)
        )
        return
    }
    entry.threatType?.let { type ->
        val info = ThreatTypeCatalog.INFO.getValue(type)
        val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
        ThreatIcon(type = type, set = iconSet, size = 22.dp, contentDescription = label)
        return
    }
    Icon(
        imageVector = entry.kind.icon(),
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(22.dp)
    )
}

@Composable
private fun RetryLogCard(
    events: List<ConnEvent>,
    retry: ConnRetryState?,
    s: Strings.StringSet,
    now: Long,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DebugAmber.copy(alpha = 0.08f))
            .border(1.dp, DebugAmber.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onDismiss)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                tint = DebugAmber,
                modifier = Modifier.size(16.dp).rotate(90f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                s.connLogTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = DebugAmber
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = DebugAmber.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
        Column(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
        ) {
            events.takeLast(8).reversed().forEach { ev ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ev.label(s),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatAlertAge(now, ev.atMillis, s),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        retry?.let { r ->
            Spacer(Modifier.height(2.dp))
            val minutesOffline = ((now - (events.firstOrNull()?.atMillis ?: now)) / 60_000L).toInt().coerceAtLeast(0)
            val countdownSec = ((r.nextAtMs - now) / 1000L).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(s.offlineLiveFormat, minutesOffline, 20, r.attempt + 1),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DebugAmber
                )
                Text(
                    " · ${countdownSec}${s.alertAgeSecSuffix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OemSimButton(context: android.content.Context, s: Strings.StringSet) {
    val realManufacturer = remember { Build.MANUFACTURER.lowercase() }
    var selectedOem by remember { mutableStateOf(BatteryOptimization.getSimulatedOem(context)) }
    val oemOptions = listOf(null to "Auto", "xiaomi" to "Xiaomi", "samsung" to "Samsung", "huawei" to "Huawei", "oppo" to "Oppo", "realme" to "Realme", "vivo" to "Vivo", "oneplus" to "OnePlus")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            s.batteryOemTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        val displayOem = selectedOem ?: realManufacturer
        Text(
            "Simulating: $displayOem",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            oemOptions.forEach { (oem, label) ->
                FilterChip(
                    selected = selectedOem == oem,
                    onClick = {
                        selectedOem = oem
                        BatteryOptimization.setSimulatedOem(context, oem)
                        val msg = if (oem != null) "OEM simulation: $label" else "OEM simulation cleared"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(entry: ConnLogEntry, s: Strings.StringSet, lang: AppLanguage, now: Long) {
    val accent = when (entry.status) {
        ConnStatus.ONLINE -> DebugGreen
        ConnStatus.OFFLINE -> DebugRed
        ConnStatus.DEGRADED -> DebugAmber
    }
    val icon = when (entry.status) {
        ConnStatus.ONLINE -> Icons.Filled.CheckCircle
        ConnStatus.OFFLINE -> Icons.Filled.Close
        ConnStatus.DEGRADED -> Icons.Filled.Warning
    }
    val label = when (entry.status) {
        ConnStatus.ONLINE -> s.connOnline
        ConnStatus.OFFLINE -> s.connOffline
        ConnStatus.DEGRADED -> s.connDegraded
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatAlertAge(now, entry.atMillis, s),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateTime(lang, entry.atMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.durationSec?.let { sec ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        String.format(s.connLogDurFormat, sec / 60, sec % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemCard(entry: SystemEntry, s: Strings.StringSet, lang: AppLanguage, now: Long) {
    val accent = when (entry.kind) {
        SystemEntryKind.SDK_CHANGED -> DebugAmber
        SystemEntryKind.SDK_CHECK_FAILED -> DebugRed
        SystemEntryKind.MALFORMED_FRAME -> DebugRed
        SystemEntryKind.UNKNOWN_TYPE_DETECTED -> DebugAmber
    }
    val icon = when (entry.kind) {
        SystemEntryKind.SDK_CHANGED -> Icons.Filled.Warning
        SystemEntryKind.SDK_CHECK_FAILED -> Icons.Filled.Close
        SystemEntryKind.MALFORMED_FRAME -> Icons.Filled.Warning
        SystemEntryKind.UNKNOWN_TYPE_DETECTED -> Icons.Filled.Warning
    }
    val label = when (entry.kind) {
        SystemEntryKind.SDK_CHANGED -> s.apiSdkChanged
        SystemEntryKind.SDK_CHECK_FAILED -> s.apiSdkCheckFailed
        SystemEntryKind.MALFORMED_FRAME -> s.apiMalformedFrame
        SystemEntryKind.UNKNOWN_TYPE_DETECTED -> s.apiUnknownType
    }
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatAlertAge(now, entry.atMillis, s),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDateTime(lang, entry.atMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.kind == SystemEntryKind.SDK_CHANGED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.apiSdkViewManifest,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://neptun.in.ua/sdk/build-manifest.json"))
                        )
                    }
                )
            }
        }
    }
}