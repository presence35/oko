package ua.ukrainedrones
import ua.ukrainedrones.theme.AppPalette

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.ukrainedrones.IconCatalog
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.SpeedSource
import ua.ukrainedrones.engine.ZoneParams
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
            .clickable(onClick = onClick),
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

/** Icon-slot size inside an icon-set tile. */
internal val IconTileSlot = 60.dp

/** Gap between icon slots in a tile's swipeable row. */
internal val IconTileSpacing = 10.dp

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
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selectedMode) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selectedMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selectedMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .pressTick(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
        )
    }
}

/** Icon-style picker: four stacked full-width rows (one per real set — Photo,
 *  Army, Comic, Russian). Each row is a horizontally swipeable strip of enlarged icons. */
@Composable
internal fun IconSetSelector(
    lang: AppLanguage,
    selected: ThreatIconSet,
    onChange: (ThreatIconSet) -> Unit,
    slot: Dp = IconTileSlot
) {
    val s = Strings.get(lang)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconSetTile(
            set = ThreatIconSet.PHOTO,
            label = s.iconSetPhotoLabel,
            selected = selected == ThreatIconSet.PHOTO,
            onClick = { onChange(ThreatIconSet.PHOTO) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.ARMY,
            label = s.iconSetArmyLabel,
            selected = selected == ThreatIconSet.ARMY,
            onClick = { onChange(ThreatIconSet.ARMY) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.COMIC,
            label = s.iconSetComicLabel,
            selected = selected == ThreatIconSet.COMIC,
            onClick = { onChange(ThreatIconSet.COMIC) },
            slot = slot
        )
        IconSetTile(
            set = ThreatIconSet.RUSSIAN,
            label = s.iconSetRussianLabel,
            selected = selected == ThreatIconSet.RUSSIAN,
            onClick = { onChange(ThreatIconSet.RUSSIAN) },
            slot = slot
        )
    }
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
            .fillMaxWidth()
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
        val types = IconCatalog.photoTypes()
        val naturalWidth = (slot + IconTileSpacing) * types.size + IconTileSpacing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(naturalWidth)
                    .padding(horizontal = IconTileSpacing, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IconTileSpacing)
            ) {
                types.forEach { type ->
                    Box(modifier = Modifier.size(slot), contentAlignment = Alignment.Center) {
                        ThreatIcon(
                            type = type,
                            set = set,
                            size = slot,
                            contentDescription = label
                        )
                    }
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
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
    onSmallChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
    onModeChange: (AlertRegionMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                selected = selected == AlertRegionMode.CITY_LABELS,
                onClick = { onModeChange(AlertRegionMode.CITY_LABELS) },
                label = { Text(cityLabelsLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Row(modifier = Modifier.size(width = 18.dp, height = 12.dp).clip(RoundedCornerShape(2.dp))) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(AppPalette.AlertRed)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(AppPalette.AlertYellow)))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(AppPalette.Card),
                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    iconColor = Color.White,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color(AppPalette.MapBackground),
                    selectedLeadingIconColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == AlertRegionMode.CITY_LABELS,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = Color(AppPalette.AlertRed),
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.5.dp
                ),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selected == AlertRegionMode.FILL,
                onClick = { onModeChange(AlertRegionMode.FILL) },
                label = { Text(fillLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Row(modifier = Modifier.size(width = 18.dp, height = 12.dp).clip(RoundedCornerShape(2.dp))) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(AppPalette.RedFill)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(AppPalette.YellowFill)))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(AppPalette.Card),
                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    iconColor = Color.White,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color(AppPalette.MapBackground),
                    selectedLeadingIconColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == AlertRegionMode.FILL,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = Color(AppPalette.RedFill),
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.5.dp
                ),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selected == AlertRegionMode.BORDER,
                onClick = { onModeChange(AlertRegionMode.BORDER) },
                label = { Text(borderLabel, style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Box(
                        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(3.dp))
                            .border(2.dp, Color(AppPalette.RedLine), RoundedCornerShape(3.dp))
                            .background(Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(2.dp)
                                .border(1.dp, Color(AppPalette.YellowLine), RoundedCornerShape(1.dp))
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(AppPalette.Card),
                    labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    iconColor = Color.White,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color(AppPalette.MapBackground),
                    selectedLeadingIconColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == AlertRegionMode.BORDER,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = Color(AppPalette.RedLine),
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.5.dp
                ),
                modifier = Modifier.weight(1f)
            )
        }
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
