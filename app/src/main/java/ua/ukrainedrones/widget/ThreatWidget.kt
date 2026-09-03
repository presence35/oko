package ua.ukrainedrones

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityWithIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import ua.ukrainedrones.engine.ThreatZone
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home-screen widget for the air-threat state. Passive: it only renders the snapshot persisted
 * by [WidgetUpdater] and never derives zones/tiers itself (mirror rule — see ARCHITECTURE.md).
 * One responsive widget; density (compact / standard / detailed) is picked from the on-screen
 * size. Tapping anywhere opens the map.
 */
@OptIn(ExperimentalGlanceApi::class)
class ThreatWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(COMPACT, STANDARD, DETAILED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (snapshot, lang, iconSet) = withContext(Dispatchers.IO) {
            Triple(
                WidgetUpdater.readSnapshot(context),
                WidgetUpdater.readLang(context),
                WidgetUpdater.readIconSet(context)
            )
        }
        provideContent {
            WidgetContent(snapshot = snapshot, lang = lang, iconSet = iconSet, context = context)
        }
    }

    @Composable
    private fun WidgetContent(
        snapshot: WidgetSnapshot,
        lang: AppLanguage,
        iconSet: ThreatIconSet,
        context: Context
    ) {
        val strings = Strings.get(lang)
        val size = LocalSize.current
        val openApp = actionStartActivity<MainActivity>()
        // Tapping the primary threat icon opens the map centered on that threat — same reveal
        // extras as a threat alert notification tap, so the map pans and selects it.
        val revealAction = snapshot.primaryThreat?.let { pt ->
            actionStartActivityWithIntent(
                Intent(context, MainActivity::class.java).apply {
                    putExtra(AlertService.EXTRA_REVEAL_ID, pt.id)
                    putExtra(AlertService.EXTRA_REVEAL_LAT, pt.lat)
                    putExtra(AlertService.EXTRA_REVEAL_LON, pt.lon)
                }
            )
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(BG)
                .clickable(openApp)
        ) {
            when (size) {
                COMPACT -> CompactLayout(snapshot, strings, iconSet, revealAction)
                STANDARD -> StandardLayout(snapshot, strings, iconSet, revealAction)
                else -> DetailedLayout(snapshot, strings, iconSet, revealAction)
            }
        }
    }

    /** 2×1: accent bar, one icon, count, source dot. */
    @Composable
    private fun CompactLayout(
        snapshot: WidgetSnapshot,
        strings: Strings.StringSet,
        iconSet: ThreatIconSet,
        revealAction: Action?
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccentBar(snapshot)
            Spacer(GlanceModifier.size(8.dp))
            IconForTopType(snapshot, iconSet, 22.dp, revealAction)
            Spacer(GlanceModifier.size(8.dp))
            Column(modifier = GlanceModifier.width(40.dp)) {
                Text(
                    text = "${snapshot.threatCount}",
                    style = TextStyle(color = ColorProvider(TEXT), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = strings.widget.threatsLabel.lowercase(),
                    style = TextStyle(color = ColorProvider(MUTED), fontSize = 9.sp),
                    maxLines = 1
                )
            }
            Spacer(GlanceModifier.width(2.dp))
            SourceDot(snapshot)
        }
    }

    /** 4×2: header (trident + title + status pill), icon + count, zone chip, updated (bottom-right). */
    @Composable
    private fun StandardLayout(
        snapshot: WidgetSnapshot,
        strings: Strings.StringSet,
        iconSet: ThreatIconSet,
        revealAction: Action?
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            HeaderRow(snapshot, strings)
            Spacer(GlanceModifier.size(6.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconForTopType(snapshot, iconSet, 28.dp, revealAction)
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(
                        text = "${snapshot.threatCount}",
                        style = TextStyle(color = ColorProvider(TEXT), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = strings.widget.threatsLabel.lowercase(),
                        style = TextStyle(color = ColorProvider(MUTED), fontSize = 10.sp),
                        maxLines = 1
                    )
                }
            }
            Spacer(GlanceModifier.size(5.dp))
            ZoneChip(snapshot, strings)
            Spacer(GlanceModifier.defaultWeight())
            UpdatedLine(snapshot, strings)
        }
    }

    /** 4×3: header + status pill, per-type icons with counts, zone chip, updated (bottom-right). */
    @Composable
    private fun DetailedLayout(
        snapshot: WidgetSnapshot,
        strings: Strings.StringSet,
        iconSet: ThreatIconSet,
        revealAction: Action?
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
            HeaderRow(snapshot, strings)
            Spacer(GlanceModifier.size(8.dp))
            TypeRow(snapshot, iconSet)
            Spacer(GlanceModifier.size(6.dp))
            ZoneChip(snapshot, strings)
            Spacer(GlanceModifier.defaultWeight())
            UpdatedLine(snapshot, strings)
        }
    }

    @Composable
    private fun HeaderRow(snapshot: WidgetSnapshot, strings: Strings.StringSet) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_trident),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp, 28.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = strings.appTitle,
                style = TextStyle(color = ColorProvider(GOLD), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight()
            )
            SourcePill(snapshot, strings)
        }
    }

    @Composable
    private fun AccentBar(snapshot: WidgetSnapshot) {
        val color = accentColor(snapshot)
        Box(
            modifier = GlanceModifier
                .fillMaxHeight()
                .width(6.dp)
                .background(color)
        ) {}
    }

    @Composable
    private fun ZoneChip(snapshot: WidgetSnapshot, strings: Strings.StringSet) {
        val (label, color) = zoneChip(snapshot, strings)
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(Color.Black),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            modifier = GlanceModifier
                .background(color)
                .cornerRadius(8.dp)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }

    @Composable
    private fun SourcePill(snapshot: WidgetSnapshot, strings: Strings.StringSet) {
        val (label, color) = sourcePill(snapshot, strings)
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(Color.Black),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            modifier = GlanceModifier
                .background(color)
                .cornerRadius(10.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }

    @Composable
    private fun SourceDot(snapshot: WidgetSnapshot) {
        val color = if (!snapshot.sourceOnline) RED else GREEN
        Box(
            modifier = GlanceModifier.size(10.dp).background(color)
        ) {}
    }

    /** Up to 4 present threat types as icon + count pairs, most severe first. */
    @Composable
    private fun TypeRow(snapshot: WidgetSnapshot, iconSet: ThreatIconSet) {
        val types = snapshot.typeCounts.keys
            .sortedBy { TYPE_PRIORITY.indexOf(it) }
            .take(4)
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (type in types) {
                Column(
                    modifier = GlanceModifier
                        .background(CARD)
                        .cornerRadius(10.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        provider = ImageProvider(IconCatalog.res(type, iconSet)),
                        contentDescription = null,
                        modifier = GlanceModifier.size(22.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(GlanceModifier.size(2.dp))
                    Text(
                        text = "${snapshot.typeCounts[type]}",
                        style = TextStyle(color = ColorProvider(TEXT), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(GlanceModifier.width(6.dp))
            }
        }
    }

    @Composable
    private fun IconForTopType(
        snapshot: WidgetSnapshot,
        iconSet: ThreatIconSet,
        sizeDp: androidx.compose.ui.unit.Dp,
        revealAction: Action?
    ) {
        val pt = snapshot.primaryThreat
        if (pt == null) {
            Box(
                modifier = GlanceModifier.size(sizeDp).background(CARD).cornerRadius(sizeDp / 2)
            ) {}
        } else {
            Image(
                provider = ImageProvider(IconCatalog.res(pt.type, iconSet)),
                contentDescription = null,
                modifier = GlanceModifier
                    .size(sizeDp)
                    .let { if (revealAction != null) it.clickable(revealAction) else it },
                contentScale = ContentScale.Fit
            )
        }
    }

    @Composable
    private fun UpdatedLine(snapshot: WidgetSnapshot, strings: Strings.StringSet) {
        if (snapshot.updatedAtMs <= 0L) return
        val text = if (System.currentTimeMillis() - snapshot.updatedAtMs < 60_000L) {
            strings.widget.updatedNowLabel
        } else {
            String.format(strings.widget.updatedFormat, relativeUpdated(snapshot.updatedAtMs, strings))
        }
        Text(
            text = text,
            style = TextStyle(color = ColorProvider(MUTED), fontSize = 10.sp, textAlign = TextAlign.End),
            modifier = GlanceModifier.fillMaxWidth()
        )
    }

    private fun accentColor(snapshot: WidgetSnapshot): Color = when {
        snapshot.officialAlert || snapshot.activeZone == ThreatZone.INNER -> RED
        snapshot.activeZone == ThreatZone.OUTER -> AMBER
        snapshot.threatCount > 0 -> BLUE
        else -> GREEN
    }

    private fun zoneChip(snapshot: WidgetSnapshot, strings: Strings.StringSet): Pair<String, Color> = when {
        snapshot.activeZone == ThreatZone.INNER -> strings.redZoneLabel to RED
        snapshot.activeZone == ThreatZone.OUTER -> strings.yellowZoneLabel to AMBER
        snapshot.officialAlert -> strings.widget.officialAlertLabel to RED
        snapshot.threatCount > 0 -> strings.widget.threatsAwayFormat
            .let { if (snapshot.nearestKm != null) String.format(it, snapshot.nearestKm.toInt()) else strings.widget.active } to BLUE
        else -> strings.widget.noThreats to GREEN
    }

    private fun sourcePill(snapshot: WidgetSnapshot, strings: Strings.StringSet): Pair<String, Color> = when {
        !snapshot.sourceOnline -> strings.status.connOffline to RED
        snapshot.sourceDegraded -> strings.status.connDegraded to ORANGE
        else -> strings.status.connOnline to GREEN
    }

    /** Header title grows into free space (fills the Row, pushing the pill to the end). */
    companion object {
        val COMPACT = DpSize(100.dp, 48.dp)
        val STANDARD = DpSize(230.dp, 100.dp)
        val DETAILED = DpSize(230.dp, 165.dp)

        private val TYPE_PRIORITY = listOf(
            ThreatType.BALLISTIC,
            ThreatType.AVIATION,
            ThreatType.CRUISE_MISSILE,
            ThreatType.KAB,
            ThreatType.SHAHED,
            ThreatType.FPV_LOITERING,
            ThreatType.RECON,
            ThreatType.UNKNOWN
        )

        private val BG = Color(0xFF121212)
        private val CARD = Color(0xFF1C1C1E)
        private val TEXT = Color(0xFFE6E6E6)
        private val MUTED = Color(0xFF9E9E9E)
        private val RED = Color(0xFFE53935)
        private val AMBER = Color(0xFFFDD835)
        private val ORANGE = Color(0xFFFB8C00)
        private val GREEN = Color(0xFF43A047)
        private val BLUE = Color(0xFF1E88E5)
        private val GOLD = Color(0xFFFFD700)
    }
}

private fun relativeUpdated(epochMs: Long, s: Strings.StringSet): String {
    val minutes = ((System.currentTimeMillis() - epochMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> "$minutes ${s.minutesAgoSuffix}"
        minutes < 60 * 24 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h ${s.hoursAgoSuffix}" else String.format(s.mixedTimeFormat, h, m)
        }
        else -> {
            val d = minutes / (60 * 24)
            val h = (minutes % (60 * 24)) / 60
            if (h == 0L) "$d ${s.daysAgoSuffix}" else String.format(s.mixedTimeFormat, d, h)
        }
    }
}

/** The manifest-declared receiver for [ThreatWidget]. */
class ThreatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ThreatWidget()
}