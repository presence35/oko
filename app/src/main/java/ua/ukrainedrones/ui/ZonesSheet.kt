package ua.ukrainedrones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val RedZoneColor = Color(0xFFD32F2F)
private val YellowZoneColor = Color(0xFFF9A825)
internal val TurtleGreen = Color(0xFF4CAF50)

/** The red/yellow accent colors used by zone sliders everywhere (sheet + Settings). */
internal val ZoneRedColor = RedZoneColor
internal val ZoneYellowColor = YellowZoneColor

/** Shared grab-handle pill used by the zones sheet and the connection bottom sheet. */
@Composable
internal fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp, bottom = 8.dp)
            .width(48.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF555555).copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF888888))
        )
    }
}

/**
 * Alert-zone panel shown in the bottom sheet over the map, so the sliders can be
 * tuned while the slow-distance circles are live on the map behind. Slow threats tier
 * by distance (km), fast threats by time-to-arrival (minutes). Everything is visible
 * at once.
 */
@Composable
fun ZonesPanel(
    slowRedKm: Int,
    slowYellowKm: Int,
    fastRedMin: Int,
    fastYellowMin: Int,
    slowRedArmed: Boolean,
    slowYellowArmed: Boolean,
    fastRedArmed: Boolean,
    fastYellowArmed: Boolean,
    lang: AppLanguage,
    nightActive: Boolean = false,
    useNightZones: Boolean = false,
    nightEnabled: Boolean = false,
    daySlowRedKm: Int? = null,
    daySlowYellowKm: Int? = null,
    dayFastRedMin: Int? = null,
    dayFastYellowMin: Int? = null,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onFastRedChange: (Int) -> Unit,
    onFastYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit,
    onFastRedArmedChange: (Boolean) -> Unit,
    onFastYellowArmedChange: (Boolean) -> Unit,
    onOpenThreatSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = Strings.get(lang)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    nightActive -> s.nightZonesTitle
                    nightEnabled && useNightZones -> s.dayZonesTitle
                    else -> s.alertZonesTitle
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenThreatSettings,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_ua),
                    contentDescription = s.settingsButton,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ZoneGroup(
            caption = s.slowSectionLabel,
            leadingIcon = R.drawable.ic_turtle,
            leadingDesc = s.slowGroupIconDesc,
            leadingTint = TurtleGreen
        ) {
            ZoneRow(
                value = slowRedKm,
                range = 1f..20f,
                unit = s.kmUnit,
                accent = RedZoneColor,
                armed = slowRedArmed,
                bellDesc = s.alertsBellToggle,
                reference = if (nightActive) daySlowRedKm else null,
                dayLabel = s.dayShortLabel,
                onArmedChange = onSlowRedArmedChange,
                onCommit = onSlowRedChange
            )
Spacer(Modifier.height(10.dp))
            ZoneRow(
                value = slowYellowKm,
                range = (slowRedKm + 2).toFloat()..50f,
                unit = s.kmUnit,
                accent = YellowZoneColor,
                armed = slowYellowArmed,
                bellDesc = s.alertsBellToggle,
                reference = if (nightActive) daySlowYellowKm else null,
                dayLabel = s.dayShortLabel,
                onArmedChange = onSlowYellowArmedChange,
                onCommit = onSlowYellowChange
            )
        }
        Spacer(Modifier.height(12.dp))
        ZoneGroup(
            caption = s.fastSectionLabel,
            leadingIcon = R.drawable.ic_lightning,
            leadingDesc = s.fastGroupIconDesc
        ) {
            ZoneRow(
                value = fastRedMin,
                range = 1f..5f,
                unit = s.minUnit,
                accent = RedZoneColor,
                armed = fastRedArmed,
                bellDesc = s.alertsBellToggle,
                reference = if (nightActive) dayFastRedMin else null,
                dayLabel = s.dayShortLabel,
                onArmedChange = onFastRedArmedChange,
                onCommit = onFastRedChange
            )
Spacer(Modifier.height(10.dp))
            ZoneRow(
                value = fastYellowMin,
                range = (fastRedMin + 2).toFloat()..20f,
                unit = s.minUnit,
                accent = YellowZoneColor,
                armed = fastYellowArmed,
                bellDesc = s.alertsBellToggle,
                reference = if (nightActive) dayFastYellowMin else null,
                dayLabel = s.dayShortLabel,
                onArmedChange = onFastYellowArmedChange,
                onCommit = onFastYellowChange
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ZoneGroup(
    caption: String,
    leadingIcon: Int,
    leadingDesc: String,
    leadingTint: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        SectionCaption(caption, leadingIcon = leadingIcon, leadingDesc = leadingDesc, leadingTint = leadingTint)
        content()
    }
}

@Composable
internal fun SectionCaption(
    text: String,
    leadingIcon: Int? = null,
    leadingDesc: String? = null,
    leadingTint: Color? = null
) {
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(id = leadingIcon),
                contentDescription = leadingDesc,
                tint = leadingTint ?: Color.Unspecified,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ZoneRow(
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    accent: Color,
    armed: Boolean,
    bellDesc: String,
    reference: Int? = null,
    dayLabel: String? = null,
    onArmedChange: (Boolean) -> Unit,
    onCommit: (Int) -> Unit
) {
    var local by remember { mutableStateOf(value.toFloat()) }
    LaunchedEffect(value) { local = value.toFloat() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Per-zone alert bell + switch on the left: filled/colored while armed,
        // red crossed bell when muted.
        IconButton(
            onClick = { onArmedChange(!armed) },
            modifier = Modifier.size(40.dp)
        ) {
            if (armed) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = bellDesc,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                AlertsOffBell(size = 26.dp, contentDescription = bellDesc)
            }
        }
        Switch(
            checked = armed,
            onCheckedChange = onArmedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.45f),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color(0xFF9E9E9E),
                uncheckedTrackColor = Color(0xFF555555),
                uncheckedBorderColor = Color.Transparent
            )
        )
Spacer(Modifier.width(8.dp))
        // A subtle ghost tick on the track marks the day value while night zones are being
        // edited, so the two can be compared on the spot.
        Box(
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    val ref = reference ?: return@drawBehind
                    val thumbR = 10.dp.toPx()
                    val fraction =
                        ((ref - range.start) / (range.endInclusive - range.start)).toFloat()
                    val x = thumbR + fraction * (size.width - 2 * thumbR)
                    drawLine(
                        color = Color(0xFFB0BEC5).copy(alpha = 0.45f),
                        start = Offset(x, size.height * 0.25f),
                        end = Offset(x, size.height * 0.75f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
        ) {
            Slider(
                value = local,
                onValueChange = {
                    val v = it.roundToInt()
                    local = v.toFloat()
                    onCommit(v)
                },
                valueRange = range,
                steps = 0,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
Spacer(Modifier.width(6.dp))
        if (reference != null && dayLabel != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$value $unit",
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "$dayLabel $reference $unit",
                    color = Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else {
            Text(
                "$value $unit",
                color = accent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}