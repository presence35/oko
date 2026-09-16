package ua.ukrainedrones

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val UkraineBlue = Color(0xFF005BBB)

/** Night mode's boxed section inside the Alerts card: a darker purple tint + border. */
internal val NightSectionBg = Color(0xFF1A1130)
internal val NightSectionBorder = Color(0xFF44357A)

/** Subtle one-shot blue border pulse around the row whose one-time explainer just closed. */
@Composable
internal fun Modifier.explainerFlash(active: Boolean): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            alpha.snapTo(0f)
            alpha.animateTo(0.45f, tween(180))
            alpha.animateTo(0f, tween(520))
        }
    }
    return if (active) then(
        Modifier.drawWithContent {
            drawContent()
            val sw = 2.dp.toPx()
            drawRoundRect(
                color = UkraineBlue.copy(alpha = alpha.value),
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(width = sw)
            )
        }
    ) else this
}

@Composable
internal fun AlertToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: Painter? = null,
    iconTint: Color? = null,
    iconSize: Dp = 28.dp,
    iconBadge: String? = null,
    emoji: String? = null,
    note: String? = null,
    noteIcon: Painter? = null,
    noteIconTint: Color? = null,
    noteIconSize: TextUnit = 14.sp,
    flash: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier)
            .explainerFlash(flash)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPressed) 0.06f else 0f))
            .pressTick(interactionSource)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            )
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (emoji != null) {
            Text(
                text = emoji,
                fontSize = 22.sp,
                modifier = Modifier.size(28.dp)
            )
        } else {
            icon?.let {
                Box(modifier = Modifier.size(iconSize)) {
                    Image(
                        painter = it,
                        contentDescription = null,
                        colorFilter = iconTint?.let { c -> ColorFilter.tint(c) },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (iconBadge != null) {
                        Text(
                            text = iconBadge,
                            color = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            note?.let {
                Spacer(Modifier.height(6.dp))
                if (noteIcon != null) {
                    val iconId = "noteIcon"
                    Text(
                        buildAnnotatedString {
                            appendInlineContent(iconId, "[icon]")
                            append(" $it")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        inlineContent = mapOf(
                            iconId to InlineTextContent(
                                Placeholder(
                                    noteIconSize,
                                    noteIconSize,
                                    PlaceholderVerticalAlign.TextCenter
                                )
                            ) {
                                Image(
                                    painter = noteIcon,
                                    contentDescription = null,
                                    colorFilter = noteIconTint?.let { ColorFilter.tint(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        )
                    )
                } else {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(if (isPressed) 0.92f else 1f)
        )
    }
}

/** Red / Yellow official-alert sub-channels on one line, each with its own switch. */
@Composable
internal fun OfficialPairToggleRow(
    redTitle: String,
    redChecked: Boolean,
    onRedChange: (Boolean) -> Unit,
    yellowTitle: String,
    yellowChecked: Boolean,
    onYellowChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubToggleCell(
                title = redTitle,
                checked = redChecked,
                onCheckedChange = onRedChange,
                icon = painterResource(R.drawable.ic_trident),
                iconTint = if (redChecked) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            SubToggleCell(
                title = yellowTitle,
                checked = yellowChecked,
                onCheckedChange = onYellowChange,
                icon = painterResource(R.drawable.ic_trident),
                iconTint = if (yellowChecked) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun SubToggleCell(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: Painter? = null,
    iconTint: Color? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.let {
            Image(
                painter = it,
                contentDescription = null,
                colorFilter = iconTint?.let { c -> ColorFilter.tint(c) },
                modifier = Modifier.size(26.dp)
            )
        }
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A horizontal row of tappable search suggestion chips under the search box. */
@Composable
internal fun SearchChipsRow(
    label: String,
    chips: List<SearchChip>,
    lang: AppLanguage,
    onChip: (SearchChip) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { chip ->
                FilterChip(
                    selected = false,
                    onClick = { onChip(chip) },
                    label = { Text(chip.label(lang)) }
                )
            }
        }
    }
}

@Composable
internal fun CollapsibleSectionCard(
    title: String,
    icon: Painter,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    emoji: String? = null,
    cardColor: Color? = null,
    cardBorder: Color? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val chevronAngle = animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "chevronAngle"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (cardColor != null) CardDefaults.cardColors(containerColor = cardColor) else CardDefaults.cardColors(),
        border = if (cardBorder != null) BorderStroke(1.dp, cardBorder) else null
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressTick(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                        onClick = onToggle
                    )
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isPressed) 0.06f else 0f
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (emoji != null) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!expanded && !subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                trailing?.invoke()
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronAngle.value }
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

@Composable
internal fun WarningTriangle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w, h * 0.95f)
            lineTo(0f, h * 0.95f)
            close()
        }
        drawPath(path, color = Color(0xFFF9A825))
        drawLine(
            color = Color(0xFF3A2B00),
            start = Offset(w / 2f, h * 0.38f),
            end = Offset(w / 2f, h * 0.62f),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF3A2B00),
            radius = 1.4f,
            center = Offset(w / 2f, h * 0.8f)
        )
    }
}

@Composable
internal fun LanguageFlag(
    emoji: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "langFlagScale"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pressTick(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .then(
                if (active) Modifier.background(UkraineBlue.copy(alpha = 0.25f)) else Modifier
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        ) {
            Text(
                emoji,
                fontSize = 32.sp,
                modifier = Modifier.alpha(if (active) 0.3f else 1f)
            )
            if (label != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
