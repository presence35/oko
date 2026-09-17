package ua.ukrainedrones
import ua.ukrainedrones.theme.AppPalette

import ua.ukrainedrones.engine.SpeedSource
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.threatTypeInfoByString
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.min
import kotlin.math.roundToInt

private val ReliabilityRed = Color(AppPalette.AlertRed)
private val UncertaintyEmpty = Color(AppPalette.Border)
private val AdvisoryAmber = Color(AppPalette.AlertYellow)
private val DistUserRed = Color(AppPalette.AlertRed)
private val DistUserAmber = Color(AppPalette.AlertYellow)
private val DistUserGreen = Color(AppPalette.SafeGreen)
private val GpsDot = Color(AppPalette.GpsBlue)

/** One stacked metric pill on the small card: number + unit + optional dot/label. */
private data class PillSpec(
    val number: String,
    val unit: String,
    val dotColor: Color?,
    val contentDescription: String?
)

/** Grey crossed bell marking a type whose alerts are switched off in Settings. The tint
 *  matches the toggles' own "off" gray (the standard 0xFF9E9E9E) so the chip reads as one
 *  family, on a subtle grey fill so it stays visible on the dark card. */
@Composable
internal fun AlertsOffBell(
    size: Dp = 14.dp,
    tint: Color = Color(AppPalette.TextSecondary),
    contentDescription: String? = null
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_notifications_off),
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(size)
    )
}

/** Crossed bell + small "off" chip shown next to the popup title when the type's alerts are off. */
@Composable
internal fun AlertsOffChip(s: Strings.StringSet) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(AppPalette.Chip).copy(alpha = if (isPressed) 0.9f else 1f),
        modifier = Modifier.pressTick(interactionSource).clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            onClick = {}
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AlertsOffBell(size = fontAware(14.dp))
            Text(
                s.alertsOffLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color(AppPalette.TextSecondary)
            )
        }
    }
}

/** Amber "SIMULATION" tag shown on cards for threats emitted by the Test simulator. */
@Composable
private fun SimulationChip(s: Strings.StringSet) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AdvisoryAmber.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, AdvisoryAmber.copy(alpha = 0.6f))
    ) {
        Text(
            s.simulationLabel,
            style = MaterialTheme.typography.labelSmall,
            color = AdvisoryAmber,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** System font scale, capped so extreme accessibility sizes can't break the layout. */
@Composable
private fun fontScale(): Float = min(LocalDensity.current.fontScale, 1.5f)

/** Scale a fixed size by the (capped) system font scale so it grows with the text. */
@Composable
private fun fontAware(dp: Dp): Dp = dp * fontScale()

/** Leaf composable that runs its own 1s clock and returns the formatted elapsed time
 *  and stale flag for a threat. Isolated here so the parent card doesn't recompose every second. */
@Composable
private fun ThreatElapsedText(
    threat: NormalizedThreat,
    strings: Strings.StringSet
): Pair<String, Boolean> {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val engine = remember(typeCatalog) { ThreatEngine(typeCatalog) }
    val nt = threat
    val stale = engine.isStale(nt, engine.propsFor(nt.type), now)
    val elapsedText = if (stale) strings.lastSeenAgoFormat.format(formatElapsedMss(threat.updatedAtMillis, now))
        else formatElapsedMss(threat.updatedAtMillis, now)
    return elapsedText to stale
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThreatPopupCard(
    threat: NormalizedThreat,
    lang: AppLanguage,
    iconSet: ThreatIconSet = ThreatIconSet.PHOTO,
    proximity: ThreatProximity?,
    pinnedCity: City?,
    threatLevel: Double,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    cardSize: ThreatCardSize = ThreatCardSize.LARGE,
    interactive: Boolean = true,
    alertsOff: Boolean = false,
    neutralized: Boolean = false,
    neutralizing: Boolean = false,
    fakeNeutralize: Boolean = false
) {
    val s = Strings.get(lang)
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val engine = remember(typeCatalog) { ThreatEngine(typeCatalog) }
    val typeInfo = threatTypeInfoByString(threat.type) ?: ThreatTypeCatalog.INFO.getValue(ThreatType.UNKNOWN)
    val typeLabel = if (lang == AppLanguage.UA) typeInfo.labelUa else typeInfo.labelEn
    // Wave count (group size) prefixes the title when the server reports it (>1 only).
    val titleLabel = if (threat.count > 1) "${threat.count}x $typeLabel" else typeLabel

    val regionText = listOf(threat.locality, threat.district, threat.region)
        .filter { !it.isNullOrBlank() }
        .distinct()
        .joinToString(" · ")
        .ifBlank { s.noRegion }

    // NEPTUN's locality text is Ukrainian; for the EN UI transliterate it (place names are
    // romanized, never semantically translated — the romanization is all an EN reader needs).
    // The national MiG carries descriptors, not places — show the fixed EN text instead.
    val displayRegion = when {
        lang == AppLanguage.EN && isNationalMig(threat) -> nationalMigWhereText()
        lang == AppLanguage.EN -> Transliteration.transliterate(regionText)
        else -> regionText
    }

    // Elapsed time + stale flag from leaf composable (runs its own 1s clock, doesn't invalidate parent).
    val (elapsedText, stale) = ThreatElapsedText(threat, s)

    val confirmations = threat.confirmations.takeIf { it > 0 }

    val band = proximity?.let { p ->
        val props = typeCatalog[threat.type] ?: return@let null
        engine.zoneTier(props, p.distToUserKm ?: return@let null, p.speedKmh, p.params)
    }
    val bandColor = when (band) {
        ThreatZone.INNER -> DistUserRed
        ThreatZone.OUTER -> DistUserAmber
        null -> Color(AppPalette.TextSecondary)
    }

    // Selection-change feedback: the body renders in one frame (tap feels instant); the title
    // icon pops 0.4 → 1 with a quick spring as the only motion — whenever a different threat
    // is selected (first open included). Stream refreshes keep the threat id, so they never
    // re-trigger.
    // Hoisted here so card-size toggles don't reset the pop. The tap haptic lives at the
    // marker-click site (immediate); with system animations off there is no pop at all.
    val animsOff = animationsOff()
    val iconScale = remember { Animatable(1f) }
    var lastSelectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(threat.id, interactive) {
        when {
            !interactive -> {
                iconScale.snapTo(1f)
                lastSelectedId = threat.id
            }
            threat.id == lastSelectedId -> {}
            else -> {
                lastSelectedId = threat.id
                // Tap-site haptic (MapView) already ticked on touch; no second buzz here.
                if (!animsOff) {
                    iconScale.snapTo(0.4f)
                    iconScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = 800f
                        )
                    )
                }
            }
        }
    }

    // Neutralized state: a compact, non-interactive card that just announces the resolved
    // threat by its type — no pills, skull, region or close.
    if (neutralized) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = Color(AppPalette.Card),
            border = BorderStroke(2.dp, Color(AppPalette.Border)),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThreatIcon(
                        type = threat.type.toThreatType(),
                        set = iconSet,
                        size = 28.dp,
                        contentDescription = typeLabel
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        typeLabel,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (fakeNeutralize) s.fakeNeutralizingLabel else if (neutralizing) s.neutralizingLabel else s.neutralizedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(AppPalette.TextSecondary)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (fakeNeutralize) s.fakeNeutralizingNote else if (neutralizing) s.neutralizingNote else s.neutralizedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(AppPalette.TextSecondary)
                )
            }
        }
        return
    }

            val cardInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .then(if (interactive) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .then(
                if (interactive) Modifier.pressTick(cardInteraction).clickable(
                    interactionSource = cardInteraction,
                    indication = ripple(bounded = true, radius = 200.dp),
                    onClick = onDismiss
                ) else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (stale) Color(AppPalette.Panel) else Color(AppPalette.Card),
        border = BorderStroke(2.dp, if (stale) Color(AppPalette.Border) else bandColor),
        tonalElevation = 8.dp
    ) {
        when (cardSize) {
            // Compact top-left card: icon + title + elapsed time on top-right,
            // metric pills, R and P bars side-by-side, and vertical skull gauge on right edge.
            ThreatCardSize.SMALL -> {
                val distUser = proximity?.distToUserKm
                val cityName = pinnedCity?.let { if (lang == AppLanguage.UA) it.nameUa else it.nameEn }
                val distCd = if (cityName != null && distUser != null) {
                    String.format(s.pillDistanceCd, cityName, distUser.roundToInt())
                } else null
                val pillSpecs = if (distUser != null) {
                    buildList {
                        proximity?.etaToUserMin?.let { eta ->
                            add(PillSpec(ThreatEngine.formatEtaMinutes(eta), s.etaUnit, GpsDot, null))
                        }
                        add(PillSpec(formatKm(distUser), s.kmUnit, null, distCd))
                    }
                } else emptyList()

                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = min(density.fontScale, 1.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            // Row 1: Header - Icon + Title + Status Chips + Elapsed time on top right
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(modifier = Modifier.graphicsLayer { val sc = iconScale.value; scaleX = sc; scaleY = sc }) {
                                    ThreatIcon(
                                        type = threat.type.toThreatType(),
                                        set = iconSet,
                                        size = fontAware(34.dp),
                                        contentDescription = typeLabel
                                    )
                                }
Text(
                                        titleLabel,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                if (alertsOff) {
                                    AlertsOffChip(s)
                                }
                                if (threat.simulated) {
                                    SimulationChip(s)
                                }
                                Spacer(Modifier.weight(1f, fill = false))
                                Text(
                                    elapsedText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stale) AdvisoryAmber else Color(AppPalette.TextSecondary)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Row 2: Metric pills (or GPS off message)
                            if (distUser != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    pillSpecs.forEach { p ->
                                        MetricPill(
                                            number = p.number,
                                            unit = p.unit,
                                            contentDescription = p.contentDescription,
                                            dotColor = p.dotColor
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    s.gpsOffLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(AppPalette.TextSecondary)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Row 3: R and P bars
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.reliabilityShort,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(AppPalette.TextSecondary)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    ReliabilityBar(
                                        reliability = Reliability.fromApi(threat.reliability),
                                        s = s,
                                        compact = true
                                    )
                                }
                                threat.uncertaintyKm?.let { uKm ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            s.uncertaintyShort,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(AppPalette.TextSecondary)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        CompactUncertaintyBar(uncertaintyKm = uKm)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Vertical skull gauge on right edge
                        ThreatLevelGauge(
                            level = threatLevel,
                            height = fontAware(70.dp),
                            skullSize = fontAware(20.dp),
                            barWidth = fontAware(10.dp)
                        )
                    }
                }
            }

            // The full card: clean layout without dividers, elapsed time on top-right,
            // P and R on separate lines for senior/large font accessibility.
            ThreatCardSize.LARGE -> {
                Row(modifier = Modifier.padding(14.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Header: icon, type, status chips, region, and elapsed time on top-right
                        Row(verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.graphicsLayer { val sc = iconScale.value; scaleX = sc; scaleY = sc }) {
                                ThreatIcon(
                                    type = threat.type.toThreatType(),
                                    set = iconSet,
                                    size = fontAware(40.dp),
                                    contentDescription = typeLabel
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        titleLabel,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    if (alertsOff) {
                                        Spacer(Modifier.width(6.dp))
                                        AlertsOffChip(s)
                                    }
                                    if (threat.simulated) {
                                        Spacer(Modifier.width(6.dp))
                                        SimulationChip(s)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        elapsedText,
                                        color = if (stale) AdvisoryAmber else Color(AppPalette.TextSecondary),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        displayRegion,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color(AppPalette.TextTertiary),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                            }
                        }

                        // NEPTUN's course assessment, e.g. "Drone heading toward Chornomorsk"
                        val course = translateCourseAssessment(threat.explanationShort, lang)
                            ?.let { firstSentence(it) }
                            ?.takeUnless { repeatsShownInfo(it, typeLabel, typeInfo.labelEn, displayRegion) }
                        course?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(AppPalette.TextDetail))
                            Spacer(Modifier.height(4.dp))
                        }

                        // Always-visible trio: distance + ETA + speed pills.
                        Spacer(Modifier.height(4.dp))
                        SummaryPills(
                            proximity = proximity,
                            pinnedCity = pinnedCity,
                            s = s,
                            lang = lang,
                            modifier = Modifier.padding(start = 52.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        if (threat.advisory) {
                            Surface(shape = RoundedCornerShape(12.dp), color = AdvisoryAmber.copy(alpha = 0.18f)) {
                                Text(
                                    s.advisoryLabel,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = AdvisoryAmber,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // Precision (P) on its own line for accessibility and readability
                        threat.uncertaintyKm?.let { uKm ->
                            UncertaintyBar(uncertaintyKm = uKm, s = s)
                            Spacer(Modifier.height(6.dp))
                        }

                        if (threat.areaOnly) {
                            Surface(shape = RoundedCornerShape(12.dp), color = AdvisoryAmber.copy(alpha = 0.18f)) {
                                Text(
                                    s.areaOnlyLabel,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AdvisoryAmber,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // Reliability (R) on its own line with sources count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ReliabilityBar(reliability = Reliability.fromApi(threat.reliability), s = s)
                            confirmations?.let { n ->
                                Text(
                                    "$n ${sourcesWord(n, lang)}",
                                    color = Color(AppPalette.TextSecondary),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    ThreatLevelGauge(level = threatLevel)
                }
            }
        }
    }
}

/** Threat-level colour shared by the vertical gauge, horizontal bar and skull icons. */
private fun levelColor(level: Double): Color = when {
    level >= 8.0 -> Color(AppPalette.AlertRed)
    level >= 6.0 -> DistUserRed
    level >= 3.0 -> DistUserAmber
    else -> DistUserGreen
}

/** Keep only the first sentence of NEPTUN's course text. */
private fun firstSentence(text: String): String {
    for (c in text) {
        if (c == '.' || c == '!' || c == '?') return text.substringBefore(c).trim()
    }
    return text
}

/** True when the course line carries nothing beyond the type label and the place names
 *  already shown in the header: deleting those leaves no real words behind. */
internal fun repeatsShownInfo(course: String, typeLabel: String, labelEn: String, regionText: String): Boolean {
    fun norm(s: String): String = s.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
    var rest = " ${norm(course)} "
    val drops = (listOf(typeLabel, labelEn) +
            listOf("БпЛА", "Shahed", "Шахед", "Шахеди", "Drone", "Дрон") +
            listOf("guided bomb", "guided bombs", "cruise missile", "ballistic missile", "high-speed target") +
            listOf("heading toward", "moving toward", "in the area of", "from the direction of") +
            regionText.split('·', ','))
        .map { norm(it) }
        .filter { it.isNotBlank() }
        .sortedByDescending { it.length }
    for (d in drops) {
        val padded = " $d "
        while (padded in rest) rest = rest.replace(padded, " ")
    }
    return rest.isBlank()
}

/** Vertical 0–10 gauge: skull above a bar that fills with the level. */
@Composable
private fun ThreatLevelGauge(
    level: Double,
    height: Dp = fontAware(130.dp),
    skullSize: Dp = fontAware(26.dp),
    barWidth: Dp = fontAware(12.dp)
) {
    val fraction = (level / 10.0).coerceIn(0.0, 1.0)
    val color = levelColor(level)
    val skullTint = if (level >= 3.0) color else Color(AppPalette.TextSecondary)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(id = R.drawable.ic_skull),
            contentDescription = null,
            tint = skullTint,
            modifier = Modifier.size(skullSize)
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(AppPalette.Border))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(barWidth)
                    .fillMaxHeight(fraction.toFloat().coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryPills(
    proximity: ThreatProximity?,
    pinnedCity: City?,
    s: Strings.StringSet,
    lang: AppLanguage,
    singleLine: Boolean = false,
    modifier: Modifier = Modifier
) {
    val distUser = proximity?.distToUserKm
    if (distUser == null) {
        Text(
            s.gpsOffLabel,
            style = MaterialTheme.typography.bodySmall,
            color = Color(AppPalette.TextSecondary)
        )
        return
    }
    val cityName = pinnedCity?.let { if (lang == AppLanguage.UA) it.nameUa else it.nameEn }
    val distCd = if (cityName != null) {
        String.format(s.pillDistanceCd, cityName, distUser.roundToInt())
    } else null
    if (singleLine) {
        // Single-line pills can't wrap — cap the font scale so extreme accessibility
        // sizes don't push the row off the card.
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = density.density,
                fontScale = min(density.fontScale, 1.25f)
            )
        ) {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillTrio(proximity = proximity, distUser = distUser, distCd = distCd, s = s)
            }
        }
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PillTrio(proximity = proximity, distUser = distUser, distCd = distCd, s = s)
        }
    }
}

/** The ETA / distance / speed pill trio (no wrapping container of its own). */
@Composable
private fun PillTrio(
    proximity: ThreatProximity?,
    distUser: Double,
    distCd: String?,
    s: Strings.StringSet
) {
    proximity?.etaToUserMin?.let { eta ->
        MetricPill(
            number = ThreatEngine.formatEtaMinutes(eta),
            unit = s.etaUnit,
            dotColor = GpsDot
        )
    }
    MetricPill(
        number = formatKm(distUser),
        unit = s.kmUnit,
        contentDescription = distCd
    )
    proximity?.takeIf { it.speedSource == SpeedSource.RECORDED }?.speedKmh?.let { speed ->
        MetricPill(
            number = speed.roundToInt().toString(),
            unit = s.speedUnit
        )
    }
}

/** Neutral, low-color pill where the number is the hero and the unit stays muted. */
@Composable
private fun MetricPill(
    number: String,
    unit: String,
    contentDescription: String? = null,
    dotColor: Color? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(AppPalette.Chip)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                // Mirrors the map's GPS dot (same blue core + white ring) but with a much
                // subtler radial glow so it reads as a card indicator, not a beacon.
                Box(
                    modifier = Modifier
                        .size(fontAware(14.dp))
                        .drawBehind {
                            val core = 4.2.dp.toPx()
                            val haloR = size.minDimension / 2f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.5f to dotColor.copy(alpha = 0.14f),
                                        1f to dotColor.copy(alpha = 0f)
                                    ),
                                    center = center,
                                    radius = haloR
                                ),
                                radius = haloR,
                                center = center
                            )
                            drawCircle(color = dotColor, radius = core, center = center)
                            drawCircle(
                                color = Color.White,
                                radius = core * 0.55f,
                                center = center,
                                style = Stroke(width = 1.4.dp.toPx())
                            )
                        }
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                number,
                color = Color(AppPalette.PillNumber),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                }
            )
            Spacer(Modifier.width(2.dp))
            Text(
                unit,
                color = Color(AppPalette.TextSecondary),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private fun formatKm(km: Double): String = km.roundToInt().toString()

/** Maps uncertainty km to a 1–5 quality rating (more bars = tighter fix). */
private fun uncertaintyBars(km: Double): Int {
    return when {
        km < 1.0 -> 5
        km < 2.0 -> 4
        km < 4.0 -> 3
        km < 8.0 -> 2
        else -> 1
    }
}

/** Fill colour for the precision bar: green at 5 bars, amber mid-range, red when coarse. */
private fun uncertaintyColor(bars: Int): Color = when (bars) {
    5 -> DistUserGreen
    3, 4 -> DistUserAmber
    else -> DistUserRed
}

/** 5-segment uncertainty indicator with the raw ±km kept as a small caption. */
@Composable
private fun UncertaintyBar(uncertaintyKm: Double?, s: Strings.StringSet) {
    if (uncertaintyKm == null) return
    val bars = uncertaintyBars(uncertaintyKm)
    val color = uncertaintyColor(bars)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.uncertaintyLabel, style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TextSecondary))
        Spacer(Modifier.width(8.dp))
        repeat(5) { i ->
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < bars) color else UncertaintyEmpty)
            )
            if (i < 4) Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "±${formatKm(uncertaintyKm)} ${s.kmUnit}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(AppPalette.TextSecondary)
        )
    }
}

/** Compact 5-segment uncertainty bar for small cards. */
@Composable
private fun CompactUncertaintyBar(uncertaintyKm: Double) {
    val bars = uncertaintyBars(uncertaintyKm)
    val color = uncertaintyColor(bars)
    val segmentWidth = fontAware(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(
                modifier = Modifier
                    .size(width = segmentWidth, height = fontAware(6.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < bars) color else UncertaintyEmpty)
            )
            if (i < 4) Spacer(Modifier.width(2.dp))
        }
    }
}

/** Precision-style reliability indicator: label + 3 segments, LOW left → HIGH right. */
@Composable
private fun ReliabilityBar(
    reliability: Reliability,
    s: Strings.StringSet,
    compact: Boolean = false
) {
    val level = when (reliability) {
        Reliability.HIGH -> 3
        Reliability.MEDIUM -> 2
        Reliability.LOW -> 1
        Reliability.UNKNOWN -> 0
    }
    val color = when (reliability) {
        Reliability.HIGH -> DistUserGreen
        Reliability.MEDIUM -> DistUserAmber
        Reliability.LOW -> ReliabilityRed
        Reliability.UNKNOWN -> Color(AppPalette.TextSecondary)
    }
    val segmentWidth = if (compact) fontAware(10.dp) else fontAware(22.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Text(s.reliabilityLabel, style = MaterialTheme.typography.bodySmall, color = Color(AppPalette.TextSecondary))
            Spacer(Modifier.width(8.dp))
        }
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(width = segmentWidth, height = fontAware(6.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < level) color else UncertaintyEmpty)
            )
            if (i < 2) Spacer(Modifier.width(2.dp))
        }
    }
}
