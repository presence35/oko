package com.odesaplay.oko

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.odesaplay.oko.theme.AppPalette

internal fun timeText(min: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60)

/**
 * One night *policy* key — official red/yellow, the two siren overrides, the alert scope.
 *
 * Three states, not two, because "I never said anything about the night" and "off at night" are
 * different facts: the first follows whatever the day says ([effectiveNightPolicy]), the second is
 * a choice. Rendering the inherited day value as the row's switch is what makes the night window
 * honest; the Day chip is how the user gets back to following the day after pinning one.
 */
@Composable
private fun NightPolicyRow(
    title: String,
    value: Boolean?,
    day: Boolean,
    dayLabel: String,
    onLabel: String,
    offLabel: String,
    onChange: (Boolean?) -> Unit,
    icon: Painter? = null,
    iconTint: Color? = null
) {
    AlertToggleRow(
        title = title,
        checked = value ?: day,
        onCheckedChange = { onChange(it) },
        icon = icon,
        iconTint = iconTint,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NightPolicyChip(dayLabel, value == null) { onChange(null) }
                NightPolicyChip(onLabel, value == true) { onChange(true) }
                NightPolicyChip(offLabel, value == false) { onChange(false) }
            }
        }
    )
}

@Composable
private fun NightPolicyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.hapticClickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

/** Bordered, rounded box that visually groups a set of zone slider rows (night custom zones). */
@Composable
internal fun GroupedZoneSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
internal fun NightTimeField(
    label: String,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, interactionSource = rememberHapticInteractionSource(), modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                timeText(minute),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NightModeCard(
    lang: AppLanguage,
    enabled: Boolean,
    sleepActive: Boolean,
    startMin: Int,
    endMin: Int,
    useCustomZones: Boolean,
    slowRedKm: Int,
    slowYellowKm: Int,
    fastRedMin: Int,
    fastYellowMin: Int,
    slowRedArmed: Boolean,
    slowYellowArmed: Boolean,
    fastRedArmed: Boolean,
    fastYellowArmed: Boolean,
    zoneSirenOverride: Boolean?,
    officialSirenOverride: Boolean?,
    daySirenOverride: Boolean,
    dayOfficialAlertCityScope: Boolean,
    dayOfficialRedEnabled: Boolean,
    dayOfficialYellowEnabled: Boolean,
    daySlowRedKm: Int,
    daySlowYellowKm: Int,
    dayFastRedMin: Int,
    dayFastYellowMin: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    onUseCustomZonesChange: (Boolean) -> Unit,
    onSlowRedChange: (Int) -> Unit,
    onSlowYellowChange: (Int) -> Unit,
    onFastRedChange: (Int) -> Unit,
    onFastYellowChange: (Int) -> Unit,
    onSlowRedArmedChange: (Boolean) -> Unit,
    onSlowYellowArmedChange: (Boolean) -> Unit,
    onFastRedArmedChange: (Boolean) -> Unit,
    onFastYellowArmedChange: (Boolean) -> Unit,
    onZoneSirenOverrideChange: (Boolean?) -> Unit,
    onOfficialSirenOverrideChange: (Boolean?) -> Unit,
    nightOfficialAlertCityScope: Boolean?,
    nightOfficialRedEnabled: Boolean?,
    nightOfficialYellowEnabled: Boolean?,
    onNightOfficialAlertCityScopeChange: (Boolean?) -> Unit,
    onNightOfficialRedChange: (Boolean?) -> Unit,
    onNightOfficialYellowChange: (Boolean?) -> Unit,
    onSleepToggle: (Boolean) -> Unit
) {
    val s = Strings.get(lang)
    var editing by remember { mutableStateOf<String?>(null) }  // "start" | "end" | null

    Column {
        // Outside the `enabled` guard on purpose. The preset switches every NIGHT alert off;
        // hiding its own row when the night window is off left the user with every night bell
        // disabled and no way to see — or undo — it from anywhere in the app.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (sleepActive) Color(AppPalette.Primary).copy(alpha = 0.16f) else Color.Transparent)
                .border(
                    1.dp,
                    if (sleepActive) Color(AppPalette.Primary) else Color(AppPalette.Border),
                    RoundedCornerShape(14.dp)
                )
                .hapticClickable { onSleepToggle(!sleepActive) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(if (sleepActive) R.drawable.ic_notifications_off else R.drawable.ic_moon),
                contentDescription = null,
                tint = if (sleepActive) Color(AppPalette.Primary) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                // "Sleep well" instead of the old "Zone alerts are off": the row mutes NIGHT
                // alerts specifically, and the old wording read as a claim about the day ones.
                if (sleepActive) s.actionLog.sleepWell else s.nightSleepButton,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (sleepActive) Color(AppPalette.Primary) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = sleepActive, onCheckedChange = null)
        }
        if (enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NightTimeField(
                        label = s.nightStartTimeLabel,
                        minute = startMin,
                        onClick = { editing = "start" },
                        modifier = Modifier.weight(1f)
                    )
                    NightTimeField(
                        label = s.nightEndTimeLabel,
                        minute = endMin,
                        onClick = { editing = "end" },
                        modifier = Modifier.weight(1f)
                    )
                }
                val spanMin = nightWindowDurationMin(startMin, endMin)
                if (spanMin > 0) {
                    Text(
                        String.format(s.nightSpanFormat, spanMin / 60, spanMin % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
            if (!sleepActive) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                    SectionCaption(s.officialAlertsTitle)
                    NightPolicyRow(
                        title = s.officialRedAlertsTitle,
                        value = nightOfficialRedEnabled,
                        day = dayOfficialRedEnabled,
                        dayLabel = s.dayShortLabel,
                        onLabel = s.subtitles.onWord,
                        offLabel = s.subtitles.offWord,
                        onChange = onNightOfficialRedChange,
                        icon = painterResource(R.drawable.ic_trident),
                        iconTint = if (nightOfficialRedEnabled ?: dayOfficialRedEnabled)
                            Color(AppPalette.AlertRed) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NightPolicyRow(
                        title = s.officialYellowAlertsTitle,
                        value = nightOfficialYellowEnabled,
                        day = dayOfficialYellowEnabled,
                        dayLabel = s.dayShortLabel,
                        onLabel = s.subtitles.onWord,
                        offLabel = s.subtitles.offWord,
                        onChange = onNightOfficialYellowChange,
                        icon = painterResource(R.drawable.ic_trident),
                        iconTint = if (nightOfficialYellowEnabled ?: dayOfficialYellowEnabled)
                            Color(AppPalette.AlertYellow) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NightPolicyRow(
                        title = s.nightOfficialSirenOverrideTitle,
                        value = officialSirenOverride,
                        day = daySirenOverride,
                        dayLabel = s.dayShortLabel,
                        onLabel = s.subtitles.onWord,
                        offLabel = s.subtitles.offWord,
                        onChange = onOfficialSirenOverrideChange,
                        icon = painterResource(R.drawable.ic_trident)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NightPolicyRow(
                        title = s.nightZoneSirenOverrideTitle,
                        value = zoneSirenOverride,
                        day = daySirenOverride,
                        dayLabel = s.dayShortLabel,
                        onLabel = s.subtitles.onWord,
                        offLabel = s.subtitles.offWord,
                        onChange = onZoneSirenOverrideChange,
                        icon = painterResource(R.drawable.ic_volume_up),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NightPolicyRow(
                        title = s.officialAlertScopeTitle,
                        value = nightOfficialAlertCityScope,
                        day = dayOfficialAlertCityScope,
                        dayLabel = s.dayShortLabel,
                        onLabel = s.subtitles.onWord,
                        offLabel = s.subtitles.offWord,
                        onChange = onNightOfficialAlertCityScopeChange,
                        icon = painterResource(R.drawable.ic_city_medium),
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.nightCustomZonesTitle,
                    description = s.nightCustomZonesDesc,
                    checked = useCustomZones,
                    onCheckedChange = onUseCustomZonesChange
                )
                if (useCustomZones) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        GroupedZoneSection {
                            SectionCaption(s.slowSectionLabel, leadingIcon = R.drawable.ic_turtle, leadingDesc = s.slowGroupIconDesc, leadingTint = TurtleGreen)
                            ZoneRow(
                                value = slowRedKm,
                                range = 1f..20f,
                                unit = s.kmUnit,
                                accent = ZoneRedColor,
                                armed = slowRedArmed,
                                bellDesc = s.alertsBellToggle,
                                reference = daySlowRedKm,
                                dayLabel = s.dayShortLabel,
                                onArmedChange = onSlowRedArmedChange,
                                onCommit = onSlowRedChange
                            )
                            Spacer(Modifier.height(10.dp))
                            ZoneRow(
                                value = slowYellowKm,
                                range = (slowRedKm + 2).toFloat()..50f,
                                unit = s.kmUnit,
                                accent = ZoneYellowColor,
                                armed = slowYellowArmed,
                                bellDesc = s.alertsBellToggle,
                                reference = daySlowYellowKm,
                                dayLabel = s.dayShortLabel,
                                onArmedChange = onSlowYellowArmedChange,
                                onCommit = onSlowYellowChange
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        GroupedZoneSection {
                            SectionCaption(s.fastSectionLabel, leadingIcon = R.drawable.ic_lightning, leadingDesc = s.fastGroupIconDesc)
                            ZoneRow(
                                value = fastRedMin,
                                range = 1f..5f,
                                unit = s.minUnit,
                                accent = ZoneRedColor,
                                armed = fastRedArmed,
                                bellDesc = s.alertsBellToggle,
                                reference = dayFastRedMin,
                                dayLabel = s.dayShortLabel,
                                onArmedChange = onFastRedArmedChange,
                                onCommit = onFastRedChange
                            )
                            Spacer(Modifier.height(10.dp))
                            ZoneRow(
                                value = fastYellowMin,
                                range = (fastRedMin + 2).toFloat()..20f,
                                unit = s.minUnit,
                                accent = ZoneYellowColor,
                                armed = fastYellowArmed,
                                bellDesc = s.alertsBellToggle,
                                reference = dayFastYellowMin,
                                dayLabel = s.dayShortLabel,
                                onArmedChange = onFastYellowArmedChange,
                                onCommit = onFastYellowChange
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!slowRedArmed || !slowYellowArmed || !fastRedArmed || !fastYellowArmed) {
                            Text(
                                s.nightMuteExitNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (editing != null) {
        val initial = if (editing == "start") startMin else endMin
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(if (editing == "start") s.nightStartTimeLabel else s.nightEndTimeLabel)
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minute = timeState.hour * 60 + timeState.minute
                        if (editing == "start") onStartChange(minute) else onEndChange(minute)
                        editing = null
                    },
                    interactionSource = rememberHapticInteractionSource()
                ) { Text(s.okButton) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }, interactionSource = rememberHapticInteractionSource()) { Text(s.backButton) }
            }
        )
    }
}
