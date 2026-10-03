package com.odesaplay.oko
import com.odesaplay.oko.theme.AppPalette

import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.toThreatType
import com.odesaplay.oko.engine.resolveOblastId
import android.content.Intent
import android.net.Uri
import com.odesaplay.oko.connection.ConnEvent
import com.odesaplay.oko.connection.ConnRetryState
import androidx.compose.ui.platform.LocalContext

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.odesaplay.oko.AppSources
import com.odesaplay.oko.LogUpload
import com.odesaplay.oko.UploadState
import com.odesaplay.oko.source.OperationalMode
import com.odesaplay.oko.source.SourceState
import com.odesaplay.oko.source.SourceTestResult
import com.odesaplay.oko.source.SourceType
import com.odesaplay.oko.source.Source
import com.odesaplay.oko.source.SourceEvent
import com.odesaplay.oko.source.SourceEventKind

private val DebugRed = Color(AppPalette.AlertRed)
private val DebugAmber = Color(AppPalette.AlertYellow)
private val DebugGreen = Color(AppPalette.SafeGreen)
private val DebugBlue = Color(AppPalette.Primary)

/** Rows shown at once; the double-arrow button reveals [VISIBLE_STEP] more. */
private const val VISIBLE_INITIAL = 25
private const val VISIBLE_STEP = 50

/** Which data source to show. */
private enum class LogsFilter { DECISIONS, CONNECTIONS, SOURCES }

/** Decisions read as a raid story (default) or the raw event list. */
private enum class LogsMode { STORY, LIST }

/** Scope of the Decisions feed: only the current focus oblast, or the whole feed. */
private enum class LogScope { MINE, ALL }
/**
 * The outcome axis of the feed. Every ALERT event ends in one of three — RANG, COVERED (a louder
 * alert won the slot), or NOT_NOTIFIED — and those three are the only segments the summary counts.
 * [INFO] is the fourth, non-alert bucket: notification-lifecycle rows (see [DebugLogKind.NOTIF])
 * that report a fact about a notification, not whether anything rang.
 */
enum class NotifyOutcome { RANG, COVERED, NOT_NOTIFIED, INFO }

/** How to group decision rows. */
private enum class LogGroupMode { NONE, TIME, OBLAST, TYPE }

/** Accent for a group header. */
private enum class GroupAccent { OFFICIAL, RED, YELLOW, OBLAST }

/** Rows of a single threat type inside a group. */
private data class TypeSubGroup(
    val type: ThreatType?,
    val entries: List<DebugLogEntry>
)

/** One rendered group of decision rows. [title] null = flat list, no header. */
private data class LogGroupSpec(
    val id: String,
    val title: String?,
    val accent: GroupAccent?,
    val headerType: ThreatType?,
    val entries: List<DebugLogEntry>,
    /** Threat-type sub-headers inside the group (type grouping only). */
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

/** A location-health row, shown in the same Connection view as the network rows. */
private data class GpsRow(val entry: GpsLogEntry) : LogRow {
    override val atMillis: Long get() = entry.atMillis
}

/** Stable list identity for a row. A live connection episode and its committed counterpart
 *  share atMillis + status, so Compose keeps the row in place and adds the new recovery row
 *  instead of remounting the list. */
private fun LogRow.stableKey(): String = when (this) {
    is DecisionRow -> "dec-${entry.atMillis}-${entry.kind.name}-${entry.threatId}-${entry.tier?.name}-${entry.reason.name}"
    is ConnectionRow -> "conn-${entry.atMillis}-${entry.status.name}"
    is GpsRow -> "gps-${entry.atMillis}-${entry.kind.name}"
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
    focusToken: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by DebugLog.entries.collectAsState()
    val connEntries by ConnectionLog.entries.collectAsState()
    val gpsEntries by GpsLog.entries.collectAsState()
    val context = LocalContext.current
    val registry = AppSources.registry
    val connRetry by registry.retryState.collectAsState()
    val connEvents by registry.connEvents.collectAsState()
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var visibleCount by remember { mutableIntStateOf(VISIBLE_INITIAL) }
    var filter by rememberSaveable { mutableStateOf(LogsFilter.DECISIONS) }
    val tabFilters = remember { listOf(LogsFilter.DECISIONS, LogsFilter.CONNECTIONS, LogsFilter.SOURCES) }
    val pagerState = rememberPagerState(pageCount = { tabFilters.size })
    // Single truth: pager -> filter. Tab taps animate the pager; swipes flow back here.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect {
            val f = tabFilters[it]
            if (f != filter) { filter = f; visibleCount = VISIBLE_INITIAL }
        }
    }
    LaunchedEffect(filter) {
        val i = tabFilters.indexOf(filter)
        if (i != pagerState.currentPage) pagerState.scrollToPage(i)
    }
    var scopeMode by rememberSaveable { mutableStateOf(LogScope.MINE) }
    var outcomeFilter by rememberSaveable { mutableStateOf<NotifyOutcome?>(null) }
    var mode by rememberSaveable { mutableStateOf(LogsMode.STORY) }
    var groupMode by rememberSaveable { mutableStateOf(LogGroupMode.TIME) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var legendExpanded by rememberSaveable { mutableStateOf(false) }
    val uploadState by LogUpload.state.collectAsState()
    // Reuses the `now` ticker this composable already runs — no second timer just to expire
    // a rate-limit cooldown.
    val retryAt = (uploadState as? UploadState.Failed)?.retryAtMillis
    val coolingDown = retryAt != null && now < retryAt

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val window = entries.filter { now - it.atMillis < DebugLog.AUTO_CLEAR_AGE_MS }

    val connColor = when {
        neptunDown -> Color(AppPalette.AlertRed)
        degraded -> Color(AppPalette.DegradedOrange)
        else -> Color(AppPalette.SafeGreen)
    }
    val healthWord = when {
        neptunDown -> s.connOffline
        degraded -> s.connDegraded
        else -> s.connOnline
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .background(Color(AppPalette.Card))
    ) {
        // Top Header Bar — clean single-row: title, then NEPTUN mark + domain + status + counts.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                colorFilter = ColorFilter.tint(connColor),
                modifier = Modifier.height(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            val siteUrl = registry.siteUrl
            Text(
                siteUrl?.removePrefix("https://")?.removeSuffix("/") ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    siteUrl?.let { url ->
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                            )
                        )
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                healthWord,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = connColor
            )
            Spacer(Modifier.width(4.dp))
            val uploading = uploadState is UploadState.Building || uploadState is UploadState.Sending
            IconButton(
                onClick = { if (uploading) LogUpload.dismiss() else LogUpload.upload(context) },
                enabled = !uploading && !coolingDown,
                interactionSource = rememberHapticInteractionSource()
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(AppPalette.AlertYellow)
                    )
                } else {
                    Icon(
                        Icons.Outlined.CloudUpload,
                        contentDescription = s.logsSendLogs,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Upload outcome — a tap with no confirmation gets tapped twice, and you get two bundles.
        when (val st = uploadState) {
            is UploadState.Done, is UploadState.Failed -> {
                val ok = st is UploadState.Done
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { LogUpload.dismiss() }
                        .background(Color(AppPalette.CardAlt))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (ok) Color(AppPalette.SafeGreen) else Color(AppPalette.AlertRed),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    val waitSec = retryAt?.let { ((it - now) / 1000L).coerceAtLeast(0L) }
                    Text(
                        when {
                            ok -> "${s.logsSendLogsOk} ${(st as UploadState.Done).fileName}"
                            // Actionable words plus a countdown, never a bare status code.
                            waitSec != null -> "${s.logsSendLogsBusy} ${waitSec}s"
                            else -> "${s.logsSendLogsFail}: ${(st as UploadState.Failed).reason}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                }
            }
            else -> Unit
        }

        // Tabs — taps drive the pager; swipes flow back via snapshotFlow above.
        val tabLabels = listOf(s.logsFilterDecisions, s.logsFilterConnections, s.logsFilterSources)
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color(AppPalette.CardAlt),
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp
        ) {
            tabFilters.forEachIndexed { index, f ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(tabLabels[index]) },
                    interactionSource = rememberHapticInteractionSource()
                )
            }
        }

        if (filter == LogsFilter.DECISIONS) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                LegendRow(s, legendExpanded) { legendExpanded = !legendExpanded }
            }
        }

        // Swipeable pages — each tab owns its rows so content follows the swipe.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            beyondViewportPageCount = 1
        ) { page ->
            LogsTabPage(
                pageFilter = tabFilters[page],
                s = s,
                lang = lang,
                iconSet = iconSet,
                window = window,
                connEntries = connEntries,
                gpsEntries = gpsEntries,
                connEvents = connEvents,
                connRetry = connRetry,
                now = now,
                focusToken = focusToken,
                scopeMode = scopeMode,
                outcomeFilter = outcomeFilter,
                mode = mode,
                groupMode = groupMode,
                newestFirst = newestFirst,
                searchQuery = searchQuery,
                visibleCount = visibleCount,
                onScopeChange = { scopeMode = it; visibleCount = VISIBLE_INITIAL },
                onOutcomeFilterChange = { outcomeFilter = it; visibleCount = VISIBLE_INITIAL },
                onModeChange = { mode = it; visibleCount = VISIBLE_INITIAL },
                onGroupModeChange = { groupMode = it; visibleCount = VISIBLE_INITIAL },
                onSortToggle = { newestFirst = !newestFirst },
                onSearchChange = { searchQuery = it; visibleCount = VISIBLE_INITIAL },
                onShowMore = { visibleCount += VISIBLE_STEP }
            )
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
 * One swipeable Logs tab page. Each tab derives its own rows from the shared
 * log state so content follows the pager; a new tab is one [LogsFilter] value
 * plus one branch below — no sync logic to touch.
 */
@Composable
private fun LogsTabPage(
    pageFilter: LogsFilter,
    s: Strings.StringSet,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    window: List<DebugLogEntry>,
    connEntries: List<ConnLogEntry>,
    gpsEntries: List<GpsLogEntry>,
    connEvents: List<ConnEvent>,
    connRetry: ConnRetryState?,
    now: Long,
    focusToken: String?,
    scopeMode: LogScope,
    outcomeFilter: NotifyOutcome?,
    mode: LogsMode,
    groupMode: LogGroupMode,
    newestFirst: Boolean,
    searchQuery: String,
    visibleCount: Int,
    onScopeChange: (LogScope) -> Unit,
    onOutcomeFilterChange: (NotifyOutcome?) -> Unit,
    onModeChange: (LogsMode) -> Unit,
    onGroupModeChange: (LogGroupMode) -> Unit,
    onSortToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onShowMore: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isDecisions = pageFilter == LogsFilter.DECISIONS
    val rows: List<LogRow> = if (pageFilter == LogsFilter.SOURCES) emptyList() else
        buildRows(window, connEntries, gpsEntries, now, isDecisions, newestFirst, scopeMode, focusToken, outcomeFilter, searchQuery, lang)
    // Story mode is the default read: cluster the same filtered events into raids. "My oblast"
    // is the write-time aboutMe flag — in my oblast, or something I would have heard — never a
    // guess from the threat's own place.
    val storySessions = if (isDecisions && mode == LogsMode.STORY) {
        buildSessions(rows.filterIsInstance<DecisionRow>().map { it.entry })
            .filter { outcomeFilter == null || it.entries.any { e -> notifyOutcome(e) == outcomeFilter } }
            .filter { scopeMode == LogScope.ALL || it.aboutMe() }
    } else emptyList()
    // Paginate decisions in GROUP order (not a raw row slice) so a newly-arrived decision
    // can't shift the boundary and inject a fresh trailing row on every "Show more".
    val decisionEntries = rows.filterIsInstance<DecisionRow>().map { it.entry }
    val allGroups = if (isDecisions) buildGroups(decisionEntries, groupMode, lang, s, newestFirst, now) else emptyList()
    val shownEntries = allGroups.asSequence().flatMap { it.entries.asSequence() }.take(visibleCount).toList()
    val shownKeys = shownEntries.map { entryKey(it) }.toSet()
    val groups = allGroups.mapNotNull { g ->
        val kept = g.entries.filter { entryKey(it) in shownKeys }
        if (kept.isEmpty()) null else g.copy(entries = kept)
    }
    val visible = if (isDecisions) rows else rows.take(visibleCount)
    val hasMore = if (isDecisions) shownEntries.size < decisionEntries.size else visible.size < rows.size
    val subtitle = if (isDecisions) {
        // Lifecycle rows aren't events, so they can't inflate the count the segments add up to.
        String.format(s.logsSubtitleFormat, decisionEntries.count { notifyOutcome(it) != NotifyOutcome.INFO })
    } else null
    // Every event falls into exactly one outcome — the summary counts come from the FULL
    // filtered set (never the visible slice) so the numbers stay honest while scrolling.
    // The three segments deliberately exclude NotifyOutcome.INFO (see its doc).
    val rang = decisionEntries.count { notifyOutcome(it) == NotifyOutcome.RANG }
    val covered = decisionEntries.count { notifyOutcome(it) == NotifyOutcome.COVERED }
    val notNotified = decisionEntries.count { notifyOutcome(it) == NotifyOutcome.NOT_NOTIFIED }
    val diagnosis = diagnosisOf(decisionEntries, s)
    // Collapsed section ids are keyed by group id so a section survives data churn; emptied
    // automatically when its rows scroll out, so a stale id can never linger.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(groups.map { it.id }) {
        val live = groups.map { it.id }.toSet()
        collapsed.keys.retainAll(live)
    }
    fun toggle(id: String) {
        collapsed[id] = !(collapsed[id] ?: false)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isDecisions) {
            item(key = "controls") {
                LogControlsRow(
                    mode = mode,
                    scopeMode = scopeMode,
                    groupMode = groupMode,
                    newestFirst = newestFirst,
                    searchQuery = searchQuery,
                    s = s,
                    onModeChange = onModeChange,
                    onScopeChange = onScopeChange,
                    onGroupModeChange = onGroupModeChange,
                    onSortToggle = onSortToggle,
                    onSearchChange = onSearchChange
                )
            }
            item(key = "summary") {
                SummaryBar(
                    total = decisionEntries.size,
                    rang = rang,
                    covered = covered,
                    notNotified = notNotified,
                    selected = outcomeFilter,
                    diagnosis = diagnosis,
                    s = s,
                    onSelect = onOutcomeFilterChange
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
        if (pageFilter == LogsFilter.SOURCES) {
            item(key = "sources") {
                SourcesList(s, now, lang, iconSet)
            }
        }
        if (pageFilter == LogsFilter.CONNECTIONS && connEvents.isNotEmpty()) {
            item(key = "retrylog") {
                RetryLogCard(connEvents, connRetry, s, now) { AppSources.registry.dismissLogCard() }
            }
        }
        if (visible.isEmpty() && pageFilter != LogsFilter.SOURCES
            && !(pageFilter == LogsFilter.CONNECTIONS && connEvents.isNotEmpty())) {
            item {
                Text(
                    when (pageFilter) {
                        LogsFilter.CONNECTIONS -> s.logsEmptyConnections
                        else -> s.debugLogEmpty
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 24.dp)
                )
            }
        } else if (isDecisions && mode == LogsMode.STORY) {
            itemsIndexed(storySessions, key = { _, session -> session.id }) { _, session ->
                SessionCard(session, s, lang, iconSet, now)
            }
        } else if (groups.isNotEmpty()) {
            groups.forEach { group ->
                val collapsedNow = collapsed[group.id] == true
                if (group.title != null) {
                    item(key = "header-${group.id}") {
                        GroupHeader(group, s, collapsedNow) { toggle(group.id) }
                    }
                } else if (group.headerType != null) {
                    item(key = "header-${group.id}") {
                        TypeGroupHeader(group.headerType, group.entries.size, lang, iconSet, s, collapsedNow) { toggle(group.id) }
                    }
                }
                if (collapsedNow) return@forEach
                if (group.subTypes) {
                    group.entries.groupBy { it.threatType ?: ThreatType.UNKNOWN }
                        .entries
                        .sortedBy { it.key.ordinal }
                        .forEach { (type, subEntries) ->
                            item(key = "sub-${group.id}-$type") {
                                TypeSubHeader(TypeSubGroup(type, subEntries), lang, iconSet)
                            }
                            itemsIndexed(subEntries, key = { _, entry -> "sub-${group.id}-$type-${entry.atMillis}-${entry.threatId}-${entry.kind.name}-${entry.reason.name}" }) { _, entry ->
                                DecisionCard(entry, s, lang, now, iconSet)
                            }
                        }
                } else {
                    itemsIndexed(group.entries, key = { _, entry -> "group-${group.id}-${entry.atMillis}-${entry.threatId}-${entry.kind.name}-${entry.reason.name}" }) { _, entry ->
                        DecisionCard(entry, s, lang, now, iconSet)
                    }
                }
            }
        } else {
            itemsIndexed(visible, key = { _, row -> row.stableKey() }) { _, row ->
                LogRowCard(row, s, lang, now, iconSet)
            }
        }
        if (visible.isNotEmpty()) {
            if (hasMore) {
                item(key = "more") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ShowMoreButton(s) { onShowMore() }
                    }
                }
            }
            if (isDecisions) {
                item(key = "clear") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { scope.launch(Dispatchers.IO) { DebugLog.clear() } },
                            interactionSource = rememberHapticInteractionSource()
                        ) {
                            Text(s.debugLogClear)
                        }
                    }
                }
            }
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
    neptunDown: Boolean,
    degraded: Boolean,
    focusToken: String?,
    onBack: () -> Unit,
) {
    LogsDropDownSheet(
        s = s,
        lang = lang,
        iconSet = iconSet,
        neptunDown = neptunDown,
        degraded = degraded,
        focusToken = focusToken,
        onClose = onBack,
        modifier = Modifier.fillMaxHeight(1f),
    )
}

/**
 * Assemble the row list for the active filter, ordered per [newestFirst]. The Decisions view
 * applies, in order: scope ("My oblast" = the write-time aboutMe flag), outcome (notified /
 * covered / not notified), then a free-text search over place, type and id. Connection rows
 * include the live in-progress episode and ignore the decision filters.
 */
private fun buildRows(
    decisions: List<DebugLogEntry>,
    connEntries: List<ConnLogEntry>,
    gpsEntries: List<GpsLogEntry>,
    now: Long,
    isDecisions: Boolean,
    newestFirst: Boolean,
    scopeMode: LogScope,
    focusToken: String?,
    outcomeFilter: NotifyOutcome?,
    searchQuery: String,
    lang: AppLanguage
): List<LogRow> {
    if (!isDecisions) {
        // Network and location episodes are one timeline: the question a user opens this view
        // with is "was I protected?", and both feeds answer it. Live in-progress episodes lead.
        val connRows: List<LogRow> = (ConnectionLog.currentEpisode(now)?.let { listOf(ConnectionRow(it)) }
            ?: emptyList()) + connEntries.map { ConnectionRow(it) }
        val gpsRows: List<LogRow> = (GpsLog.currentEpisode(now)?.let { listOf(GpsRow(it)) }
            ?: emptyList()) + gpsEntries.map { GpsRow(it) }
        val rows = connRows + gpsRows
        return if (newestFirst) rows.sortedByDescending { it.atMillis } else rows.sortedBy { it.atMillis }
    }
    var filtered: List<DebugLogEntry> = decisions
    if (scopeMode == LogScope.MINE) filtered = filtered.filter { it.aboutMe }
    if (outcomeFilter != null) filtered = filtered.filter { notifyOutcome(it) == outcomeFilter }
    val q = searchQuery.trim().lowercase()
    if (q.isNotEmpty()) {
        filtered = filtered.filter { e ->
            e.locality?.lowercase()?.contains(q) == true ||
                e.threatId?.contains(q, ignoreCase = true) == true ||
                e.threatType?.let { typeInfo(it).label(lang).lowercase().contains(q) } == true
        }
    }
    val rows = filtered.map { DecisionRow(it) }
    return if (newestFirst) rows.sortedByDescending { it.atMillis } else rows.sortedBy { it.atMillis }
}

/**
 * The outcome of a row, derived from the row's own facts — pure so the Logs screen and the
 * tests share one definition. Covered = a louder alert/notification won the slot (the event was
 * handled, not declined); everything else that didn't ring is Not notified. [NotifyOutcome.INFO]
 * is the non-alert bucket: a notification-lifecycle row reports a fact, not an outcome.
 */
fun notifyOutcome(e: DebugLogEntry): NotifyOutcome = when {
    // Lifecycle rows report no alert outcome, so they must never enter the three tallies.
    e.kind == DebugLogKind.NOTIF -> NotifyOutcome.INFO
    e.notified -> NotifyOutcome.RANG
    e.reason == DebugLogReason.COALESCED || e.reason == DebugLogReason.ALREADY_NOTIFIED ||
        e.reason == DebugLogReason.RATE_LIMITED || e.reason == DebugLogReason.ONCE_PER_THREAT ||
        e.reason == DebugLogReason.ONCE_PER_TYPE -> NotifyOutcome.COVERED
    else -> NotifyOutcome.NOT_NOTIFIED
}

/** Dominant "why they didn't ring" among the not-notified events, in one plain sentence. */
private fun diagnosisOf(entries: List<DebugLogEntry>, s: Strings.StringSet): String? {
    val counts = entries.filter { notifyOutcome(it) == NotifyOutcome.NOT_NOTIFIED }
        .groupingBy { it.reason }.eachCount()
    if (counts.isEmpty()) return null
    val top = counts.maxByOrNull { it.value }!!.key
    return String.format(s.logsDiagnosisFormat, top.label(s))
}

/** Stable identity for a decision row, matching the LazyColumn keys so pagination agrees. */
private fun entryKey(e: DebugLogEntry): String =
    "${e.atMillis}-${e.kind.name}-${e.threatId}-${e.tier?.name}-${e.reason.name}"

/**
 * Build the ordered group specs from sorted decision rows. Every mode is TOTAL — a row with no
 * resolvable bucket lands in "Other", never disappears. NONE = single header-less spec; TIME =
 * Now / Last hour / Today buckets; OBLAST = one group per event oblast ([DebugLogEntry.scopeOblastId]);
 * TYPE = official / flourish / per-type / other, each with its own header.
 */
private fun buildGroups(
    rows: List<DebugLogEntry>,
    groupMode: LogGroupMode,
    lang: AppLanguage,
    s: Strings.StringSet,
    newestFirst: Boolean = true,
    now: Long = System.currentTimeMillis()
): List<LogGroupSpec> {
    if (rows.isEmpty()) return emptyList()
    fun byTime(list: List<DebugLogEntry>): List<DebugLogEntry> =
        if (newestFirst) list.sortedByDescending { it.atMillis } else list.sortedBy { it.atMillis }
    return when (groupMode) {
        LogGroupMode.NONE -> listOf(LogGroupSpec("none", null, null, null, byTime(rows), subTypes = false))
        LogGroupMode.TIME -> {
            val fiveMin = 5L * 60 * 1000
            val hour = 60L * 60 * 1000
            fun bucket(e: DebugLogEntry): String = when {
                now - e.atMillis < fiveMin -> "now"
                now - e.atMillis < hour -> "hour"
                else -> "today"
            }
            listOf("now", "hour", "today").mapNotNull { key ->
                val entries = byTime(rows.filter { bucket(it) == key })
                if (entries.isEmpty()) null else LogGroupSpec(
                    "time-$key",
                    when (key) {
                        "now" -> s.logsTimeNow
                        "hour" -> s.logsTimeHour
                        else -> s.logsTimeToday
                    },
                    null, null, entries, subTypes = false
                )
            }
        }
        LogGroupMode.OBLAST -> {
            rows.groupBy { it.scopeOblastId }
                .entries
                .map { (id, groupRows) -> id to byTime(groupRows) }
                .sortedBy { (id, _) -> oblastTitle(id, lang, s) }
                .map { (id, groupRows) ->
                    LogGroupSpec("oblast-${id ?: "other"}", oblastTitle(id, lang, s), GroupAccent.OBLAST, null, groupRows, subTypes = false)
                }
        }
        LogGroupMode.TYPE -> {
            val official = rows.filter { it.kind == DebugLogKind.OFFICIAL_ON || it.kind == DebugLogKind.OFFICIAL_OFF || it.kind == DebugLogKind.NOTIF }
            val flourish = rows.filter { it.kind == DebugLogKind.FLOURISH }
            val typed = rows.filter { it !in official && it.threatType != null && it.kind != DebugLogKind.FLOURISH }
            val untyped = rows.filter { it !in official && it.threatType == null && it.kind != DebugLogKind.FLOURISH }
            buildList {
                if (official.isNotEmpty()) add(LogGroupSpec("official", "official", GroupAccent.OFFICIAL, null, byTime(official), subTypes = false))
                if (flourish.isNotEmpty()) add(LogGroupSpec("flourish", "flourish", null, null, byTime(flourish), subTypes = false))
                typed.groupBy { it.threatType!! }
                    .entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (type, groupRows) ->
                        add(LogGroupSpec("type-${type.name}", null, null, type, byTime(groupRows), subTypes = false))
                    }
                if (untyped.isNotEmpty()) add(LogGroupSpec("type-other", s.logsGroupOther, null, null, byTime(untyped), subTypes = false))
            }
        }
    }
}

/** Display title for an oblast group key ([resolveOblastId] result; null = unresolvable/other). */
private fun oblastTitle(id: String?, lang: AppLanguage, s: Strings.StringSet): String {
    if (id == null) return s.logsGroupOther
    val o = AdminHierarchy.getOblast(id)
    return when (lang) {
        AppLanguage.UA -> o?.nameUa ?: id
        AppLanguage.EN -> o?.nameEn ?: id
        AppLanguage.RU -> RussianToponyms.oblast(id)
    }
}

@Composable
private fun LogControlsRow(
    mode: LogsMode,
    scopeMode: LogScope,
    groupMode: LogGroupMode,
    newestFirst: Boolean,
    searchQuery: String,
    s: Strings.StringSet,
    onModeChange: (LogsMode) -> Unit,
    onScopeChange: (LogScope) -> Unit,
    onGroupModeChange: (LogGroupMode) -> Unit,
    onSortToggle: () -> Unit,
    onSearchChange: (String) -> Unit
) {
    // One row, nothing hidden off-screen: the two lenses you switch constantly, plus a Filters
    // button that reveals the rest. The old horizontal chip scroller buried controls silently.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SegmentToggle(
            options = listOf(LogsMode.STORY to s.logsModeStory, LogsMode.LIST to s.logsModeList),
            selected = mode,
            onSelect = onModeChange
        )
        SegmentToggle(
            options = listOf(LogScope.MINE to s.logsScopeMine, LogScope.ALL to s.logsScopeAll),
            selected = scopeMode,
            onSelect = onScopeChange
        )
        Spacer(Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        IconButton(
            onClick = { open = !open },
            interactionSource = rememberHapticInteractionSource()
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = s.logsFilters,
                tint = if (open || searchQuery.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (open) {
            FilterSheet(
                mode = mode,
                groupMode = groupMode,
                newestFirst = newestFirst,
                searchQuery = searchQuery,
                s = s,
                onDismiss = { open = false },
                onGroupModeChange = onGroupModeChange,
                onSortToggle = onSortToggle,
                onSearchChange = onSearchChange
            )
        }
    }
}

/** Compact popup for the less-used controls, so the header stays one clean row. */
@Composable
private fun FilterSheet(
    mode: LogsMode,
    groupMode: LogGroupMode,
    newestFirst: Boolean,
    searchQuery: String,
    s: Strings.StringSet,
    onDismiss: () -> Unit,
    onGroupModeChange: (LogGroupMode) -> Unit,
    onSortToggle: () -> Unit,
    onSearchChange: (String) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(AppPalette.Card))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                placeholder = { Text(s.logsSearchPlaceholder) },
                modifier = Modifier.fillMaxWidth()
            )
            if (mode == LogsMode.LIST) {
                Text(
                    s.logsGroupByLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val groupLabel = mapOf(
                        LogGroupMode.NONE to s.logsGroupNone,
                        LogGroupMode.TIME to s.logsGroupTime,
                        LogGroupMode.OBLAST to s.logsGroupOblasts,
                        LogGroupMode.TYPE to s.logsGroupType
                    )
                    LogGroupMode.entries.forEach { value ->
                        FilterChip(
                            selected = groupMode == value,
                            onClick = { onGroupModeChange(value) },
                            label = { Text(groupLabel[value]!!) },
                            interactionSource = rememberHapticInteractionSource()
                        )
                    }
                }
                TextButton(
                    onClick = onSortToggle,
                    interactionSource = rememberHapticInteractionSource()
                ) {
                    Text(if (newestFirst) s.logsSortNewest else s.logsSortOldest)
                }
            }
            TextButton(
                onClick = onDismiss,
                interactionSource = rememberHapticInteractionSource(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(s.alertActionOk)
            }
        }
    }
}

/** Two-option pill row (Story | List) — the primary lens switch. */
@Composable
private fun <T> SegmentToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(AppPalette.CardAlt))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (value, label) ->
            val active = selected == value
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                    .hapticClickable(onClick = { onSelect(value) })
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * One raid, told as a story: a headline verdict, when and where, and — expanded — the
 * sub-events in order. Collapsed by default so the day reads as a handful of headlines.
 */
@Composable
private fun SessionCard(
    session: LogSession,
    s: Strings.StringSet,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    now: Long
) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    val accent = when (session.official) {
        AlertLevel.RED -> DebugRed
        AlertLevel.YELLOW -> DebugAmber
        else -> if (session.told) DebugGreen else MaterialTheme.colorScheme.onSurfaceVariant
    }
    val verdict = if (session.told) s.logsSessionTold else s.logsSessionSilent
    val verdictTint = if (session.told) DebugGreen else DebugAmber
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hapticClickable(onClick = { expanded = !expanded })
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTimeRange(lang, session.startMs, session.endMs),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatDuration(s, session.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    verdict,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = verdictTint
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                session.official?.let { level ->
                    if (level != AlertLevel.NONE) {
                        // No "red"/"yellow" word — the card's colour already says it.
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (level == AlertLevel.RED) DebugRed else DebugAmber)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(
                    sessionHeadline(session, s, lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                session.entries.forEach { entry ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        DecisionCard(entry, s, lang, now, iconSet)
                    }
                }
            }
        }
    }
}

/** Plain sentence under a session headline: what it was, and why it stayed quiet. */
private fun sessionHeadline(session: LogSession, s: Strings.StringSet, lang: AppLanguage): String {
    val place = session.place
    val where = place?.let { p ->
        val en = Cities.byUa[p]?.nameEn ?: Transliteration.transliterate(p)
        lang.pick(p, en, en)
    }
    val count = String.format(s.logsSessionEvents, session.size)
    val why = session.silenceReason(s)
    return listOfNotNull(where, count, why).joinToString(" · ")
}

/** "21:10 – 21:47" in the app language. */
private fun formatTimeRange(lang: AppLanguage, startMs: Long, endMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val start = java.time.Instant.ofEpochMilli(startMs).atZone(zone).format(fmt)
    if (endMs / 60_000 == startMs / 60_000) return start
    val end = java.time.Instant.ofEpochMilli(endMs).atZone(zone).format(fmt)
    return "$start – $end"
}

/** "37 min" / "1 hr 5 min" — human, short. */
private fun formatDuration(s: Strings.StringSet, durationMs: Long): String {
    val totalMin = (durationMs / 60_000).coerceAtLeast(0)
    if (totalMin < 60) return "$totalMin${s.alertAgeMinSuffix.trim()}"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "$h${s.alertAgeHrSuffix.trim()}" else "$h${s.alertAgeHrSuffix.trim()} $m${s.alertAgeMinSuffix.trim()}"
}

/** The tappable summary: total + one segment per outcome. Tapping a segment filters to it. */
@Composable
private fun SummaryBar(
    total: Int,
    rang: Int,
    covered: Int,
    notNotified: Int,
    selected: NotifyOutcome?,
    diagnosis: String?,
    s: Strings.StringSet,
    onSelect: (NotifyOutcome?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SummarySegment(label = String.format(s.logsSummaryTotal, total), active = selected == null) { onSelect(null) }
            SummarySegment(label = String.format(s.logsSummaryRang, rang), active = selected == NotifyOutcome.RANG, tint = DebugGreen) { onSelect(NotifyOutcome.RANG) }
            SummarySegment(label = String.format(s.logsSummaryCovered, covered), active = selected == NotifyOutcome.COVERED, tint = DebugAmber) { onSelect(NotifyOutcome.COVERED) }
            SummarySegment(label = String.format(s.logsSummaryNotNotified, notNotified), active = selected == NotifyOutcome.NOT_NOTIFIED, tint = DebugAmber) { onSelect(NotifyOutcome.NOT_NOTIFIED) }
        }
        if (diagnosis != null) {
            Text(
                diagnosis,
                style = MaterialTheme.typography.labelMedium,
                color = DebugAmber,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun SummarySegment(label: String, active: Boolean, tint: Color = DebugBlue, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) tint.copy(alpha = 0.25f) else Color.Transparent,
        contentColor = if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .hapticClickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun LegendRow(s: Strings.StringSet, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hapticClickable(onClick = onToggle)
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
                    LegendItem(painterResource(R.drawable.ic_notifications_off), s.debugLogSuppressedLegend, tint = DebugAmber)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun LegendItem(painter: Painter, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint,
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
private fun GroupHeader(
    group: LogGroupSpec,
    s: Strings.StringSet,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val accent = when (group.accent) {
        GroupAccent.RED -> DebugRed
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
        else -> group.title ?: ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) s.logsSectionExpand else s.logsSectionCollapse,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(4.dp))
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

/** Unknown types can still reach the log (engine-string drift) — never throw on lookup. */
private fun typeInfo(type: ThreatType): ThreatTypeInfo =
    ThreatTypeCatalog.INFO[type] ?: ThreatTypeCatalog.INFO.getValue(ThreatType.UNKNOWN)

@Composable
private fun TypeGroupHeader(
    type: ThreatType,
    count: Int,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    s: Strings.StringSet,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val label = typeInfo(type).label(lang)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) s.logsSectionExpand else s.logsSectionCollapse,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(4.dp))
        ThreatIcon(type = type, set = iconSet, size = 16.dp, contentDescription = label)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            String.format(s.debugBandCountFormat, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeSubHeader(sub: TypeSubGroup, lang: AppLanguage, iconSet: ThreatIconSet) {
    val type = sub.type ?: return
    val info = typeInfo(type)
    val label = info.label(lang)
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
    TextButton(onClick = onMore, interactionSource = rememberHapticInteractionSource()) {
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
        is GpsRow -> GpsCard(row.entry, s, lang, now)
    }
}

@Composable
    private fun DebugLogKind.accent(tier: ThreatZone?, level: AlertLevel?): Color = when (this) {
        DebugLogKind.OFFICIAL_ON -> if (level == AlertLevel.YELLOW) DebugAmber else DebugRed
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
    DebugLogKind.REGION_THREAT -> Icons.Filled.Place
    DebugLogKind.FLOURISH -> Icons.Filled.Star
    DebugLogKind.NOTIF -> Icons.Filled.HourglassBottom
}

private fun DebugLogKind.label(
    threatType: ThreatType?,
    locality: String?,
    lang: AppLanguage,
    s: Strings.StringSet
): String = when (this) {
    DebugLogKind.OFFICIAL_ON -> {
        val loc = localityText(locality, lang)
        if (loc != null) "${s.debugKindOfficialOn} · $loc" else s.debugKindOfficialOn
    }
    DebugLogKind.OFFICIAL_OFF -> {
        val loc = localityText(locality, lang)
        if (loc != null) "${s.debugKindOfficialOff} · $loc" else s.debugKindOfficialOff
    }
    DebugLogKind.ZONE_ENTER -> {        val typeLabel = threatType?.let {
            val info = typeInfo(it)
            info.label(lang)
        }
        val loc = localityText(locality, lang)
        when {
            typeLabel != null && loc != null -> "$typeLabel \u00B7 $loc"
            typeLabel != null -> typeLabel
            loc != null -> "${s.debugKindZoneEnter} \u00B7 $loc"
            else -> s.debugKindZoneEnter
        }
    }
    DebugLogKind.REGION_THREAT -> {
        val typeLabel = threatType?.let {
            val info = typeInfo(it)
            info.label(lang)
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
    DebugLogKind.NOTIF -> {
        val loc = localityText(locality, lang)
        if (loc != null) "${s.debugKindNotifExpired} · $loc" else s.debugKindNotifExpired
    }
}

private fun localityText(locality: String?, lang: AppLanguage): String? =
    locality?.let {
        val en = Cities.byUa[it]?.nameEn ?: Transliteration.transliterate(it)
        lang.pick(it, en, en)
    }

internal fun DebugLogReason.label(s: Strings.StringSet): String = when (this) {
    DebugLogReason.NOTIF_EXPIRED -> s.debugReasonNotifExpired
    DebugLogReason.BELL_MUTED -> s.debugReasonBellMuted
    DebugLogReason.ALREADY_NOTIFIED -> s.debugReasonAlreadyNotified
    DebugLogReason.COALESCED -> s.debugReasonCoalesced
    DebugLogReason.TYPE_OFF -> s.debugReasonTypeOff
    DebugLogReason.ADVISORY -> s.debugReasonAdvisory
    DebugLogReason.STALE -> s.debugReasonStale
    DebugLogReason.OUTSIDE_ZONES -> s.debugReasonOutsideZones
    DebugLogReason.TOGGLE_OFF -> s.debugReasonToggleOff
    DebugLogReason.RATE_LIMITED -> s.debugReasonRateLimited
    DebugLogReason.ONCE_PER_THREAT -> s.debugReasonOncePerThreat
    DebugLogReason.ONCE_PER_TYPE -> s.debugReasonOncePerType
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
    val accent = entry.kind.accent(entry.tier, entry.level)
    val outcome = notifyOutcome(entry)
    // A lifecycle row is not an alert: grey, flat and tight, so it reads as a footnote under
    // the episode it belongs to instead of competing with the real rows.
    val info = outcome == NotifyOutcome.INFO
    // Dim cards that never reached the shade — the eye lands on the loud ones first.
    val bgAlpha = when (outcome) {
        NotifyOutcome.NOT_NOTIFIED -> 0.045f
        NotifyOutcome.INFO -> 0.04f
        else -> 0.10f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = bgAlpha))
            .padding(horizontal = 12.dp, vertical = if (info) 5.dp else 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        DecisionLeadingIcon(entry, accent, lang, iconSet)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.kind.label(entry.threatType, entry.locality, lang, s) +
                        entry.threatId?.let { " · #${it.takeLast(4)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f)
                )
                OutcomeGlyph(outcome, s)
                Spacer(Modifier.width(6.dp))
                Text(
                    formatAlertAge(now, entry.atMillis, s),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (outcome == NotifyOutcome.NOT_NOTIFIED) 0.5f else 1f)
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
            if (!info) {
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
                            Icons.Filled.Notifications,
                            contentDescription = s.logsOutcomeRang,
                            tint = DebugGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        // One suppressed mark, tinted by whether it was covered or declined — the
                        // stale case is already carried by the row's own stale pill.
                        Image(
                            painter = painterResource(R.drawable.ic_notifications_off),
                            contentDescription = String.format(s.debugLogSuppressed, entry.reason.label(s)),
                            colorFilter = ColorFilter.tint(
                                if (outcome == NotifyOutcome.COVERED) DebugAmber
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The one glanceable signal: did this event reach the shade (RANG), get overtaken by a louder
 *  one (COVERED), or was it declined (NOT_NOTIFIED)? */
@Composable
private fun OutcomeGlyph(outcome: NotifyOutcome, s: Strings.StringSet) {
    when (outcome) {
        NotifyOutcome.RANG -> Icon(
            Icons.Filled.Notifications,
            contentDescription = s.logsOutcomeRang,
            tint = DebugGreen,
            modifier = Modifier.size(16.dp)
        )
        NotifyOutcome.COVERED -> Image(
            painter = painterResource(R.drawable.ic_notifications_off),
            contentDescription = s.logsOutcomeCovered,
            colorFilter = ColorFilter.tint(DebugAmber),
            modifier = Modifier.size(16.dp)
        )
        NotifyOutcome.NOT_NOTIFIED -> Image(
            painter = painterResource(R.drawable.ic_notifications_off),
            contentDescription = s.logsOutcomeNotNotified,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
            modifier = Modifier.size(16.dp)
        )
        NotifyOutcome.INFO -> Icon(
            imageVector = Icons.Filled.HourglassBottom,
            contentDescription = s.logsOutcomeInfo,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
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
        val info = typeInfo(type)
        val label = info.label(lang)
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
            modifier = Modifier.hapticClickable(onClick = onDismiss)
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
            val minutesOffline = ((now - (events.lastOrNull()?.atMillis ?: now)) / 60_000L).toInt().coerceAtLeast(0)
            val countdownSec = ((r.nextAtMs - now) / 1000L).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(s.offlineLiveFormat, minutesOffline),
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





private fun NetTransport.iconRes(): Int = when (this) {
    NetTransport.WIFI -> R.drawable.ic_wifi
    NetTransport.CELLULAR -> R.drawable.ic_signal_cellular
    NetTransport.OTHER -> R.drawable.ic_language
}

private fun NetTransport.label(s: Strings.StringSet): String = when (this) {
    NetTransport.WIFI -> s.connTransportWifi
    NetTransport.CELLULAR -> s.connTransportCellular
    NetTransport.OTHER -> s.connTransportOther
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
                    color = accent
                )
                entry.transport?.let { transport ->
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(transport.iconRes()),
                        contentDescription = transport.label(s),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
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
            entry.activeSource?.let { source ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "${s.sourceFallbackLabel}: $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = DebugAmber
                )
            }
        }
    }
}

@Composable
private fun GpsCard(entry: GpsLogEntry, s: Strings.StringSet, lang: AppLanguage, now: Long) {
    val blocked = entry.kind == GpsEventKind.BLOCKED
    val accent = if (blocked) DebugRed else DebugBlue
    val icon = when (entry.kind) {
        GpsEventKind.VERIFIED -> Icons.Filled.CheckCircle
        GpsEventKind.BLOCKED -> Icons.Filled.Warning
        GpsEventKind.UNVERIFIED -> Icons.Filled.Warning
    }
    val label = when (entry.kind) {
        GpsEventKind.UNVERIFIED -> s.gpsLogUnverified
        GpsEventKind.VERIFIED -> s.gpsLogVerified
        GpsEventKind.BLOCKED -> s.gpsLogBlocked
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
                    color = accent
                )
                Spacer(Modifier.weight(1f))
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
                entry.accuracyM?.let { acc ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        String.format(s.gpsAccuracyFormat, acc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Drift is the only reason to care about this row: it means the position the engine
            // was evaluating was wrong, and by how much.
            entry.detailKm?.let { km ->
                Spacer(Modifier.height(2.dp))
                Text(
                    String.format(s.gpsDriftFormat, km),
                    style = MaterialTheme.typography.bodySmall,
                    color = DebugAmber
                )
            }
        }
    }
}

@Composable
private fun SourcesList(s: Strings.StringSet, now: Long, lang: AppLanguage, iconSet: ThreatIconSet) {
    val registry = AppSources.registry
    val sources by registry.sources.collectAsState()
    val states by registry.perSourceState.collectAsState()
    val events = remember { mutableStateListOf<SourceEvent>() }
    LaunchedEffect(registry) {
        registry.sourceEvents.collect { ev -> events.add(0, ev); while (events.size > 20) events.removeAt(events.size - 1) }
    }
    if (sources.isEmpty()) {
        Text(
            s.logsEmptySources,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEach { source ->
            SourceCard(source, states[source.id] ?: SourceState.DISCONNECTED, s)
            SourceDataCard(source, lang, iconSet, s)
        }
        MergedAlertsDebugCard(s)
        if (events.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                s.sourceActivityLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            events.forEach { ev ->
                SourceEventRow(ev, s, now)
            }
        }
    }
}

/** Debug: the MERGED alert feed (what the engine actually sees) — every alert with its own key,
 *  name, parent oblast and wide flag. This is where the old dedup-by-oblast bug showed up: all
 *  raions of one oblast used to collapse into a single alert. */
@Composable
private fun MergedAlertsDebugCard(s: Strings.StringSet) {
    val registry = AppSources.registry
    val alerts by registry.allAlerts.collectAsState()
    val owner by registry.activeAlertSource.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(AppPalette.CardDeep))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Merged alerts (${alerts.size}) · owner: ${owner ?: "none"}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = DebugAmber,
                modifier = Modifier.weight(1f)
            )
        }
        if (alerts.isEmpty()) {
            Text(
                s.logsEmptySources,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        alerts.forEach { a ->
            val wideTag = if (a.wide == true) "WIDE" else if (a.wide == false) "raion" else "?"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "· ${a.key}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.wide == true) DebugRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    wideTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.wide == true) DebugRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "   ${a.name} · ${a.oblast}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceEventRow(ev: SourceEvent, s: Strings.StringSet, now: Long) {
    val label = when (ev.kind) {
        SourceEventKind.TOGGLED_ON -> String.format(s.connEventSourceToggled, "on · ${ev.sourceId}")
        SourceEventKind.TOGGLED_OFF -> String.format(s.connEventSourceToggled, "off · ${ev.sourceId}")
        SourceEventKind.TAKEOVER -> String.format(s.connEventFallbackActive, ev.sourceId)
        SourceEventKind.RESTORED -> s.connEventFallbackRestored
    }
    val accent = when (ev.kind) {
        SourceEventKind.TAKEOVER -> DebugAmber
        SourceEventKind.RESTORED -> DebugGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(AppPalette.Card))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatAlertAge(now, ev.atMillis, s),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SourceCard(source: Source, state: SourceState, s: Strings.StringSet) {
    val mode by source.operationalMode.collectAsState()
    val enabled by source.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<SourceTestResult?>(null) }
    val connLabel = when (state) {
        SourceState.CONNECTED -> s.connOnline
        SourceState.DEGRADED -> s.connDegraded
        SourceState.OFFLINE -> s.connOffline
        else -> s.sourceModeStandby
    }
    val status = when {
        !enabled -> s.connOff
        source.sourceType == SourceType.REST && mode == OperationalMode.POLLING -> "$connLabel · ${s.sourceModePolling}"
        else -> connLabel
    }
    val statusColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        state == SourceState.OFFLINE -> DebugRed
        state == SourceState.DEGRADED -> DebugAmber
        state == SourceState.CONNECTED -> DebugGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val typeLabel = source.badgeLabel ?: if (source.sourceType == SourceType.WS) s.sourceTypeWs else s.sourceTypeRest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(AppPalette.CardAlt))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (source.id == "test") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.simulationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = DebugAmber,
                        modifier = Modifier
                            .background(DebugAmber.copy(alpha = 0.08f), RoundedCornerShape(50))
                            .border(1.dp, DebugAmber.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
            testResult?.let { r ->
                Spacer(Modifier.height(3.dp))
                Text(
                    r.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) DebugGreen else DebugRed
                )
            }
        }
        TextButton(
            enabled = !testing,
            onClick = {
                scope.launch {
                    testing = true
                    testResult = source.testConnection()
                    testing = false
                }
            },
            interactionSource = rememberHapticInteractionSource()
        ) {
            Text(s.sourceTestLabel)
        }
        Switch(
            checked = enabled,
            onCheckedChange = { newEnabled ->
                AppSources.registry.setEnabled(source, newEnabled)
            },
            interactionSource = rememberHapticInteractionSource()
        )
    }
}

@Composable
private fun SourceDataCard(
    source: Source,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    s: Strings.StringSet
) {
    val threats by source.threats.collectAsState()
    val alerts by source.alerts.collectAsState()
    val grouped = threats.groupBy { it.type }
    val isEmpty = threats.isEmpty() && alerts.isEmpty()
    if (isEmpty) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(AppPalette.CardAlt))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (grouped.isNotEmpty()) {
            Text(
                s.connActiveLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            grouped.entries.sortedByDescending { it.value.size }.forEach { (typeStr, list) ->
                val type = typeStr.toThreatType()
                val info = typeInfo(type)
                val label = info.label(lang)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThreatIcon(type = type, set = iconSet, size = 16.dp, contentDescription = label)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "×${list.size}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (alerts.isNotEmpty()) {
            if (grouped.isNotEmpty()) Spacer(Modifier.height(2.dp))
            val oblasts = alerts.map { it.oblast }.distinct()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = DebugAmber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    oblasts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
            }
        }
    }
}
