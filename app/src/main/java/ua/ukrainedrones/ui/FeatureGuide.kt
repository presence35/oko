package ua.ukrainedrones

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class GuideFeature(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val details: List<String>,
    val diagram: GuideDiagram
)

/** Builds the curated feature list in display order (grouped by category). */
fun guideFeatures(s: Strings.StringSet): List<GuideFeature> = listOf(
    GuideFeature("live", s.guideCategoryMap, s.guideLiveTitle, s.guideLiveSummary,
        listOf(s.guideLiveD1, s.guideLiveD2, s.guideLiveD3), GuideDiagram.LIVE),
    GuideFeature("footer", s.guideCategoryMap, s.guideStripTitle, s.guideStripSummary,
        listOf(s.guideStripD1, s.guideStripD2, s.guideStripD3), GuideDiagram.STRIP),
    GuideFeature("conn", s.guideCategoryMap, s.guideConnTitle, s.guideConnSummary,
        listOf(s.guideConnD1, s.guideConnD2, s.guideConnD3), GuideDiagram.CONN),
    GuideFeature("zones", s.guideCategoryZones, s.guideZonesTitle, s.guideZonesSummary,
        listOf(s.guideZonesD1, s.guideZonesD2, s.guideZonesD3), GuideDiagram.ZONES),
    GuideFeature("editZones", s.guideCategoryZones, s.guideEditZonesTitle, s.guideEditZonesSummary,
        listOf(s.guideEditZonesD1, s.guideEditZonesD2, s.guideEditZonesD3), GuideDiagram.EDIT_ZONES),
    GuideFeature("notif", s.guideCategoryZones, s.guideNotifTitle, s.guideNotifSummary,
        listOf(s.guideNotifD1, s.guideNotifD2, s.guideNotifD3), GuideDiagram.NOTIF),
    GuideFeature("fast", s.guideCategoryZones, s.guideFastTitle, s.guideFastSummary,
        listOf(s.guideFastD1, s.guideFastD2, s.guideFastD3), GuideDiagram.TOGGLES),
    GuideFeature("night", s.guideCategoryZones, s.guideNightTitle, s.guideNightSummary,
        listOf(s.guideNightD1, s.guideNightD2, s.guideNightD3), GuideDiagram.NIGHT),
    GuideFeature("follow", s.guideCategoryLocation, s.guideFollowTitle, s.guideFollowSummary,
        listOf(s.guideFollowD1, s.guideFollowD2, s.guideFollowD3), GuideDiagram.FOLLOW),
    GuideFeature("shelter", s.guideCategoryLocation, s.guideShelterTitle, s.guideShelterSummary,
        listOf(s.guideShelterD1, s.guideShelterD2, s.guideShelterD3), GuideDiagram.SHELTER),
    GuideFeature("pin", s.guideCategoryLocation, s.guidePinTitle, s.guidePinSummary,
        listOf(s.guidePinD1, s.guidePinD2), GuideDiagram.PIN),
    GuideFeature("cardSize", s.guideCategoryCards, s.guideCardSizeTitle, s.guideCardSizeSummary,
        listOf(s.guideCardSizeD1, s.guideCardSizeD3), GuideDiagram.CARD_SIZE),
    GuideFeature("cardRead", s.guideCategoryCards, s.guideCardReadTitle, s.guideCardReadSummary,
        listOf(s.guideCardReadD1, s.guideCardReadD2, s.guideCardReadD3), GuideDiagram.CARD_READ),
    GuideFeature("widget", s.guideCategoryWidget, s.guideWidgetTitle, s.guideWidgetSummary,
        listOf(s.guideWidgetD1, s.guideWidgetD2, s.guideWidgetD3), GuideDiagram.WIDGET),
    GuideFeature("lang", s.guideCategorySettings, s.guideLangTitle, s.guideLangSummary,
        listOf(s.guideLangD1, s.guideLangD2, s.guideLangD3), GuideDiagram.LANG),
    GuideFeature("toggles", s.guideCategorySettings, s.guideTogglesTitle, s.guideTogglesSummary,
        listOf(s.guideTogglesD1, s.guideTogglesD2, s.guideTogglesD3), GuideDiagram.THREAT_TOGGLES),
    GuideFeature("update", s.guideCategorySettings, s.guideUpdateTitle, s.guideUpdateSummary,
        listOf(s.guideUpdateD1, s.guideUpdateD2, s.guideUpdateD3), GuideDiagram.UPDATE)
)

private sealed interface GuideItem {
    data class Header(val label: String) : GuideItem
    data class Feature(val f: GuideFeature) : GuideItem
}

/** Scrollable, categorized manual for every feature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureGuideScreen(
    s: Strings.StringSet,
    initialFeatureId: String? = null,
    onBack: () -> Unit
) {
    val features = remember(s) { guideFeatures(s) }
    val items = remember(s) {
        buildList {
            var last: String? = null
            for (f in features) {
                if (f.category != last) {
                    add(GuideItem.Header(f.category))
                    last = f.category
                }
                add(GuideItem.Feature(f))
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(initialFeatureId) {
        if (initialFeatureId != null) {
            val idx = items.indexOfFirst { it is GuideItem.Feature && it.f.id == initialFeatureId }
            if (idx > 0) listState.animateScrollToItem(idx)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.guideTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.backButton)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { item ->
                when (item) {
                    is GuideItem.Header -> GuideSectionHeader(item.label)
                    is GuideItem.Feature -> FeatureCard(item.f)
                }
            }
        }
    }
}

@Composable
private fun GuideSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun FeatureCard(f: GuideFeature) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeatureDiagram(
                    kind = f.diagram,
                    modifier = Modifier
                        .size(width = 92.dp, height = 62.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        f.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        f.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    f.details.forEach { detail ->
                        Row(modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(
                                "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}