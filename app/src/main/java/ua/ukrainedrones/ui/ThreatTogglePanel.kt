package ua.ukrainedrones

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.ukrainedrones.engine.isFastType

/** The two threat groupings shown in Settings: fast (missiles, bombs) and slow (drones). */
internal fun fastAndSlowGroups(lang: AppLanguage): List<Triple<Int, String, Set<ThreatType>>> {
    val s = Strings.get(lang)
    val fast = ThreatType.entries.filter { isFastType(it) }.toSet()
    val slow = ThreatType.values().toSet() - fast
    return listOf(
        Triple(R.drawable.ic_lightning, s.fastGroupLabel, fast),
        Triple(R.drawable.ic_turtle, s.slowGroupLabel, slow)
    )
}

/** A compact icon-chip Map/Alerts toggle: bordered card with an icon and label, on/off/dimmed. */
@Composable
internal fun ToggleChip(
    icon: ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        border = if (on && enabled) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (on && enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.5f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** A small icon-only toggle for the Fast/Slow section title rows (Map / Alerts). */
@Composable
internal fun IconToggle(
    icon: ImageVector,
    contentDescription: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(30.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (on && enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Compact Fast/Slow threat toggles used in the zones-panel expansion and the first-launch
 * tips dialog: a slim group row (emoji + title + Map/Alerts master toggles), then one tiny
 * row per type (icon + name + Map/Alerts toggles).
 */
@Composable
fun SlimThreatToggles(
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    onThreatMapToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    onThreatAlertToggleAll: (Set<ThreatType>, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = Strings.get(lang)
    Column(modifier = modifier) {
        fastAndSlowGroups(lang).forEach { (groupIcon, groupTitle, types) ->
            val groupMapOn = types.none { it in hiddenTypes }
            val groupAlertsOn = types.none { it in silencedTypes }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = groupIcon),
                    contentDescription = if (groupIcon == R.drawable.ic_lightning) s.fastGroupIconDesc else s.slowGroupIconDesc,
                    tint = if (groupIcon == R.drawable.ic_turtle) TurtleGreen else Color.Unspecified,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    groupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SlimIconToggle(
                    icon = Icons.Filled.Place,
                    contentDescription = s.threatMapLabel,
                    on = groupMapOn,
                    enabled = true,
                    onClick = { onThreatMapToggleAll(types, !groupMapOn) }
                )
                SlimIconToggle(
                    icon = Icons.Filled.Notifications,
                    contentDescription = s.threatAlertLabel,
                    on = groupAlertsOn,
                    enabled = true,
                    onClick = { onThreatAlertToggleAll(types, !groupAlertsOn) }
                )
            }
            types.forEach { type ->
                SlimTypeRow(
                    type = type,
                    lang = lang,
                    iconSet = iconSet,
                    hiddenTypes = hiddenTypes,
                    silencedTypes = silencedTypes,
                    onThreatMapToggle = onThreatMapToggle,
                    onThreatAlertToggle = onThreatAlertToggle
                )
            }
        }
    }
}

/**
 * A single threat's slim toggle row: icon + name, compact Map/Alerts toggles on the right.
 */
@Composable
private fun SlimTypeRow(
    type: ThreatType,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit
) {
    val s = Strings.get(lang)
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
    val onMap = type !in hiddenTypes
    val onAlerts = type !in silencedTypes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThreatIcon(
            type = type,
            set = iconSet,
            size = 20.dp,
            contentDescription = label
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        SlimIconToggle(
            icon = Icons.Filled.Place,
            contentDescription = s.threatMapLabel,
            on = onMap,
            enabled = true,
            onClick = { onThreatMapToggle(type, !onMap) }
        )
        SlimIconToggle(
            icon = Icons.Filled.Notifications,
            contentDescription = s.threatAlertLabel,
            on = onAlerts,
            enabled = true,
            onClick = { onThreatAlertToggle(type, !onAlerts) }
        )
    }
}

/** Extra-compact icon toggle for the slim panels (smaller touch target than IconToggle). */
@Composable
private fun SlimIconToggle(
    icon: ImageVector,
    contentDescription: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(26.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (on && enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}