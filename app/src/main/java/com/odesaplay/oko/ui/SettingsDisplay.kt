package com.odesaplay.oko
import com.odesaplay.oko.theme.AppPalette

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.SpeedSource
import com.odesaplay.oko.engine.ZoneParams
import kotlin.math.roundToInt

/** Mock threat + proximity driving the live card-size previews. */
private val PreviewThreat = NormalizedThreat(
    id = "preview",
    type = "shahed",
    title = "БпЛА",
    region = "Одеська область",
    district = null,
    locality = "Одеса",
    lat = 46.4825,
    lon = 30.7233,
    heading = null,
    bearingDeg = 210.0,
    status = "active",
    advisory = false,
    areaOnly = false,
    confirmations = 3,
    reliability = "MEDIUM",
    count = 2,
    explanationShort = "БпЛА курсом на Чорноморськ",
    speedKmh = 180.0,
    uncertaintyKm = 1.5,
    positionQuality = "approx",
    confirmedAtMillis = null,
    updatedAtMillis = null,
    trail = emptyList()
)

private val PreviewProximity = ThreatProximity(
    predicted = LatLng(46.48, 30.72),
    distToUserKm = 6.0,
    etaToUserMin = 4.5,
    params = ZoneParams(slowRedKm = 20, slowYellowKm = 50, fastRedMin = 5, fastYellowMin = 20),
    speedSource = SpeedSource.RECORDED,
    speedKmh = 180.0
)

/** App-language picker: one horizontal row of flags, no text under them. The flag alone is the
 *  label — a name under it restates what the flag already says in the user's own script. The
 *  heading carries the *other* languages' words for "language" so a user who cannot read the
 *  current UI can still find the row. */
@Composable
internal fun LanguageSelector(
    selected: AppLanguage,
    onChange: (AppLanguage) -> Unit
) {
    val heading = remember(selected) {
        AppLanguage.values()
            .filter { it != selected }
            // English leads when it is on offer: a Latin word is the beacon for a stranded
            // reader, so it comes before the Cyrillic spellings.
            .sortedBy { it != AppLanguage.EN }
            .joinToString(" · ") { Strings.get(it).languageLabel }
    }
    Column {
        Text(
            heading,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLanguage.values().forEach { lang ->
                FlagTile(
                    flagRes = languageFlagRes(lang),
                    contentDescription = Strings.get(lang).languageLabel,
                    selected = lang == selected,
                    onClick = { onChange(lang) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun languageFlagRes(lang: AppLanguage): Int = lang.pick(
    R.drawable.flag_ua,
    R.drawable.flag_ca,
    R.drawable.flag_odesa
)

@Composable
private fun FlagTile(
    flagRes: Int,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .hapticClickable(onClick = onClick)
    ) {
        // Square-ish padding now that the flag is the whole tile: no text row to balance.
        Image(
            painter = painterResource(flagRes),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
        )
    }
}

/** Two selectable tiles, each a live scaled preview of that card size. */
@Composable
internal fun ThreatCardSizeSelector(
    lang: AppLanguage,
    selected: ThreatCardSize,
    onChange: (ThreatCardSize) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreatCardSize.values().forEach { size ->
            CardSizeTile(
                size = size,
                lang = lang,
                selected = size == selected,
                onClick = { onChange(size) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun CardSizeTile(
    size: ThreatCardSize,
    lang: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .hapticClickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val density = LocalDensity.current
            val previewNominal = if (size == ThreatCardSize.SMALL) 300.dp else 340.dp
            val cachedHeight = remember(size, lang) { intArrayOf(0) }
            SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
                val nominalW = with(density) { previewNominal.toPx() }
                val nominalWpx = with(density) { previewNominal.roundToPx() }
                val scale = if (size == ThreatCardSize.SMALL) {
                    constraints.maxWidth * 0.75f / nominalW
                } else {
                    constraints.maxWidth.toFloat() / nominalW
                }
                val cardPlaceable = subcompose("preview-card") {
                    Box(
                        modifier = Modifier
                            .width(previewNominal)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                    ) {
                        ThreatPopupCard(
                            threat = PreviewThreat,
                            lang = lang,
                            proximity = PreviewProximity,
                            pinnedCity = null,
                            threatLevel = 7.0,
                            onDismiss = {},
                            cardSize = size,
                            interactive = false
                        )
                    }
                }[0].measure(
                    constraints.copy(
                        minWidth = nominalWpx,
                        maxWidth = nominalWpx,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity
                    )
                )
                val measuredHeight = (cardPlaceable.height * scale).roundToInt()
                if (constraints.maxWidth != Constraints.Infinity && constraints.maxWidth > 0) {
                    cachedHeight[0] = measuredHeight
                }
                val height = if (cachedHeight[0] > 0) cachedHeight[0] else measuredHeight
                layout(constraints.maxWidth, height) {
                    cardPlaceable.place(0, 0)
                }
            }
        }
    }
}

/** Icon size inside an icon-set tile (the Shahed is the pack's face). */
internal val IconTileSlot = 72.dp

/** One chip of the overlapping-threats mode selector. */
@Composable
internal fun OverlapModeChip(
    mode: OverlapMode,
    label: String,
    selected: OverlapMode,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selectedMode = mode == selected
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selectedMode) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selectedMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selectedMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.hapticClickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
        )
    }
}

/** Icon-style picker: one row of packs (Photo, Army, Comic, Russian), each tile showing
 *  only its Shahed — the full pack art already sits on every type card below, so the strip of
 *  sevens was redundant. Names sit under the drones. */@Composable
internal fun IconSetSelector(
    lang: AppLanguage,
    selected: ThreatIconSet,
    onChange: (ThreatIconSet) -> Unit,
    slot: Dp = IconTileSlot,
    /** Which packs to offer. Photo always qualifies; the rest are flourish and master-gated. */
    packs: List<ThreatIconSet> = ThreatIconSet.values().toList()
) {
    val s = Strings.get(lang)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        packs.forEach { set ->
            IconSetTile(
                set = set,
                label = when (set) {
                    ThreatIconSet.PHOTO -> s.iconSetPhotoLabel
                    ThreatIconSet.ARMY -> s.iconSetArmyLabel
                    ThreatIconSet.COMIC -> s.iconSetComicLabel
                    ThreatIconSet.RUSSIAN -> s.iconSetRussianLabel
                },
                selected = selected == set,
                onClick = { onChange(set) },
                slot = slot,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Grade row: five dots, one per look, with the active one named once beside them. A dot states
 * the hue without repeating the threat art that the cards below already carry; the grade is
 * remembered per pack, so this row shows the grade of the pack selected above it.
 */
@Composable
internal fun IconGradeSelector(
    lang: AppLanguage,
    selected: ThreatIconGrade,
    onChange: (ThreatIconGrade) -> Unit
) {
    val s = Strings.get(lang)
    val labelOf = { grade: ThreatIconGrade ->
        when (grade) {
            ThreatIconGrade.NONE -> s.iconGradeNoneLabel
            ThreatIconGrade.CYAN -> s.iconGradeCyanLabel
            ThreatIconGrade.IR -> s.iconGradeIrLabel
            ThreatIconGrade.VIOLET -> s.iconGradeVioletLabel
            ThreatIconGrade.STENCIL -> s.iconGradeStencilLabel
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ThreatIconGrade.values().forEach { grade ->
            val isSelected = grade == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = labelOf(grade)) { onChange(grade) }
                    .semantics {
                        contentDescription = labelOf(grade)
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center
            ) {
                GradeDot(grade = grade, selected = isSelected)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            labelOf(selected),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One grade dot: its hue, or a black/white split for the grade that has no single hue. */
@Composable
private fun GradeDot(grade: ThreatIconGrade, selected: Boolean) {
    val ring = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val dashColor = MaterialTheme.colorScheme.onSurfaceVariant
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val dot = Modifier
        .size(if (selected) 32.dp else 26.dp)
        .clip(CircleShape)
        .then(
            when (grade) {
                // "Nothing" is an empty ring with a dash, not a grey swatch — a filled circle
                // read as just another colour.
                ThreatIconGrade.NONE -> Modifier.drawBehind {
                    drawLine(
                        color = dashColor,
                        start = Offset(size.width * 0.28f, size.height / 2f),
                        end = Offset(size.width * 0.72f, size.height / 2f),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round
                    )
                }
                ThreatIconGrade.CYAN -> Modifier.background(Color(0xFF7FD4F0))
                ThreatIconGrade.IR -> Modifier.background(Color(0xFFF2F2F2))
                ThreatIconGrade.VIOLET -> Modifier.background(Color(0xFFB79CFF))
                ThreatIconGrade.STENCIL -> Modifier.drawBehind {
                    drawCircle(Color.White)
                    drawArc(Color(0xFF111111), 90f, 180f, useCenter = true)
                }
            }
        )
        .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
    Box(modifier = dot)
}

@Composable
internal fun IconSetTile(
    set: ThreatIconSet,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slot: Dp = IconTileSlot
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "iconSetScale"
    )
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The selected tile wears the active grade so a tap gives immediate feedback; the
            // others stay ungraded, so the strip still shows each pack's own art.
            ThreatIcon(
                type = ThreatType.SHAHED,
                set = set,
                size = slot,
                grade = if (selected) LocalIconGrade.current else ThreatIconGrade.NONE,
                contentDescription = label
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** City-labels row: title/description with three independent FilterChips (large / medium /
 *  small towns), each acting as its own on/off toggle. All default on. */
@Composable
internal fun CityLabelTogglesRow(
    title: String,
    description: String,
    largeChecked: Boolean,
    mediumChecked: Boolean,
    smallChecked: Boolean,
    largeLabel: String,
    mediumLabel: String,
    smallLabel: String,
    onLargeChange: (Boolean) -> Unit,
    onMediumChange: (Boolean) -> Unit,
    onSmallChange: (Boolean) -> Unit,
    flash: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .explainerFlash(flash)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = largeChecked,
                onClick = { onLargeChange(!largeChecked) },
                label = { Text(largeLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_city),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = CityChipColors(selected = largeChecked),
                interactionSource = rememberHapticInteractionSource(),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = mediumChecked,
                onClick = { onMediumChange(!mediumChecked) },
                label = { Text(mediumLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_city_medium),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = CityChipColors(selected = mediumChecked),
                interactionSource = rememberHapticInteractionSource(),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = smallChecked,
                onClick = { onSmallChange(!smallChecked) },
                label = { Text(smallLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_house),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = CityChipColors(selected = smallChecked),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun AlertRegionModeRow(
    title: String,
    description: String,
    selected: AlertRegionMode,
    cityLabelsLabel: String,
    fillLabel: String,
    borderLabel: String,
    onModeChange: (AlertRegionMode) -> Unit,
    flash: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .explainerFlash(flash)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                AlertRegionMode.CITY_LABELS to cityLabelsLabel,
                AlertRegionMode.FILL to fillLabel,
                AlertRegionMode.BORDER to borderLabel
            ).forEach { (mode, label) ->
                AlertRegionModeChip(
                    label = label,
                    selected = selected == mode,
                    onClick = { onModeChange(mode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun AlertRegionModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(AppPalette.Card))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .hapticClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}

/** Four option tiles in one row — or 2x2 when a large system font (or a narrow width)
 *  would crush quarter-width labels. The breakpoint keys off the font scale, not just the
 *  width: dp widths don't shrink when the user enlarges text, so a width-only check would
 *  never fire for exactly the case that breaks. */
@Composable
private fun FourTiles(
    a: @Composable RowScope.() -> Unit,
    b: @Composable RowScope.() -> Unit,
    c: @Composable RowScope.() -> Unit,
    d: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tight = LocalDensity.current.fontScale > 1.15f || maxWidth < 320.dp
        if (!tight) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                a()
                b()
                c()
                d()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    a()
                    b()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    c()
                    d()
                }
            }
        }
    }
}

/** Notification frequency preset picker: four full-width rows, each with title,
 *  description and the what-if retrospective over the last 24h of Decisions. */
@Composable
internal fun NotifyPolicyRow(
    title: String,
    description: String,
    selected: ZonePolicy,
    whatIf: Map<ZonePolicy, Int>,
    onChange: (ZonePolicy) -> Unit,
    s: Strings.StringSet,
    detail: @Composable (ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(3.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        FourTiles(
            { PolicyTile(ZonePolicy.EVERY_CHANGE, selected == ZonePolicy.EVERY_CHANGE, Modifier.weight(1f), s) { onChange(ZonePolicy.EVERY_CHANGE) } },
            { PolicyTile(ZonePolicy.ONCE_PER_THREAT, selected == ZonePolicy.ONCE_PER_THREAT, Modifier.weight(1f), s) { onChange(ZonePolicy.ONCE_PER_THREAT) } },
            { PolicyTile(ZonePolicy.ONCE_PER_TYPE, selected == ZonePolicy.ONCE_PER_TYPE, Modifier.weight(1f), s) { onChange(ZonePolicy.ONCE_PER_TYPE) } },
            { PolicyTile(ZonePolicy.DIGEST, selected == ZonePolicy.DIGEST, Modifier.weight(1f), s) { onChange(ZonePolicy.DIGEST) } }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            policyDesc(selected, s),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val sub = whatIf[selected]?.let { String.format(s.policyWhatIfFormat, it) }
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        detail?.invoke(this)
    }
}

/** One policy option as a compact square: title only, the description lives under the row
 *  for the selected tile (there is no room for it at quarter width). */
@Composable
private fun PolicyTile(
    policy: ZonePolicy,
    selected: Boolean,
    modifier: Modifier = Modifier,
    s: Strings.StringSet,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .heightIn(min = 56.dp)
            .hapticClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = policyTitle(policy, s),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun policyTitle(policy: ZonePolicy, s: Strings.StringSet): String = when (policy) {
    ZonePolicy.EVERY_CHANGE -> s.policyEveryChangeTitle
    ZonePolicy.ONCE_PER_THREAT -> s.policyOncePerThreatTitle
    ZonePolicy.ONCE_PER_TYPE -> s.policyOncePerTypeTitle
    ZonePolicy.DIGEST -> s.policyDigestTitle
}

private fun policyDesc(policy: ZonePolicy, s: Strings.StringSet): String = when (policy) {
    ZonePolicy.EVERY_CHANGE -> s.policyEveryChangeDesc
    ZonePolicy.ONCE_PER_THREAT -> s.policyOncePerThreatDesc
    ZonePolicy.ONCE_PER_TYPE -> s.policyOncePerTypeDesc
    ZonePolicy.DIGEST -> s.policyDigestDesc
}

/** Digest knobs, shown only under the Digest preset: max sounds stepper (1-10),
 *  window chips (off / minutes / per alarm sitting) and counting scope. */
@Composable
internal fun DigestControlsRow(
    max: Int,
    onMaxChange: (Int) -> Unit,
    window: DigestWindow,
    onWindowChange: (DigestWindow) -> Unit,
    perType: Boolean,
    onPerTypeChange: (Boolean) -> Unit,
    s: Strings.StringSet
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.digestMaxLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { onMaxChange((max - 1).coerceAtLeast(1)) },
                enabled = max > 1,
                interactionSource = rememberHapticInteractionSource(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("−") }
            Text(
                "$max",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(
                onClick = { onMaxChange((max + 1).coerceAtMost(10)) },
                enabled = max < 10,
                interactionSource = rememberHapticInteractionSource(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("+") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            s.digestWindowLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        FourTiles(
            {
                DigestChip(
                    String.format(s.digestWindowMinFormat, 2),
                    window == DigestWindow.MIN_2,
                    Modifier.weight(1f)
                ) { onWindowChange(DigestWindow.MIN_2) }
            },
            {
                DigestChip(
                    String.format(s.digestWindowMinFormat, 10),
                    window == DigestWindow.MIN_10,
                    Modifier.weight(1f)
                ) { onWindowChange(DigestWindow.MIN_10) }
            },
            {
                DigestChip(
                    String.format(s.digestWindowMinFormat, 60),
                    window == DigestWindow.MIN_60,
                    Modifier.weight(1f)
                ) { onWindowChange(DigestWindow.MIN_60) }
            },
            {
                DigestChip(s.digestWindowEpisode, window == DigestWindow.EPISODE, Modifier.weight(1f)) {
                    onWindowChange(DigestWindow.EPISODE)
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            s.digestScopeLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DigestChip(s.digestScopePerType, perType, Modifier.weight(1f)) { onPerTypeChange(true) }
            DigestChip(s.digestScopeAny, !perType, Modifier.weight(1f)) { onPerTypeChange(false) }
        }
    }
}

@Composable
private fun DigestChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.hapticClickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
        )
    }
}

/** ON = vivid primary pill with dark content; OFF = muted grey pill — the two states
 *  can't be confused in the dark theme. */
@Composable
internal fun CityChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = Color(AppPalette.Card),
    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    iconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = Color(AppPalette.MapBackground),
    selectedLeadingIconColor = Color(AppPalette.MapBackground)
)
