package com.odesaplay.oko

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The seven alert personas plus the restore cell, as a grid of four columns (three at large system
 * fonts, where four names per row would clip), with the one-liners behind the "i".
 *
 * The last cell is "mine": it writes back the alerting state captured just before the first preset
 * tap on this page, and sits muted — visible but inert — until there is something to restore.
 *
 * Stateless by contract: [selected], [canRestore] and the callbacks come from the caller, which
 * owns the choice. Picking fires [onPick] and nothing else — this block never writes a setting.
 */
@Composable
internal fun AlertPersonaPicker(
    s: Strings.StringSet,
    selected: AlertPersona?,
    canRestore: Boolean,
    onPick: (AlertPersona) -> Unit,
    onRestore: () -> Unit
) {
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    val columns = if (LocalDensity.current.fontScale >= 1.3f) 3 else 4

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.alertPersonaTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The descriptions are one tap away instead of seven lines on the page.
            IconButton(onClick = { infoOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = s.alertPersonaInfoTitle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            s.alertPersonaDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // The restore cell is part of the grid so it reads as one more choice, and the last cell of
        // the last row so a persona is never displaced by it.
        val cells: List<AlertPersona?> = AlertPersona.values().toList() + listOf(null)
        cells.chunked(columns).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (cell in row) {
                    if (cell == null) {
                        PresetQuadrant(
                            icon = Icons.Filled.Restore,
                            label = s.alertPersonaRestore,
                            selected = false,
                            isDefault = false,
                            defaultLabel = "",
                            onClick = onRestore,
                            muted = !canRestore,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        PresetQuadrant(
                            icon = cell.icon(),
                            label = cell.title(s),
                            selected = cell == selected,
                            isDefault = false,
                            defaultLabel = "",
                            onClick = { onPick(cell) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // A short final row keeps its tiles the same width as the full ones.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                selected.desc(s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (infoOpen) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            confirmButton = {
                TextButton(
                    onClick = { infoOpen = false },
                    interactionSource = rememberHapticInteractionSource()
                ) { Text(s.explainers.gotIt) }
            },
            title = { Text(s.alertPersonaInfoTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (persona in AlertPersona.values()) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = persona.icon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    persona.title(s),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    persona.desc(s),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Restore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                s.alertPersonaRestore,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                s.alertPersonaRestoreDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }
}

/**
 * The same restore cell as a plain row, for Settings → Reliability, whose four intensities fill
 * their row by design (and are shared with the wizard) — a fifth cell there would break both.
 */
@Composable
internal fun PresetRestoreRow(
    label: String,
    canRestore: Boolean,
    onRestore: () -> Unit
) {
    TextButton(
        onClick = onRestore,
        enabled = canRestore,
        interactionSource = rememberHapticInteractionSource(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Filled.Restore,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun AlertPersona.title(s: Strings.StringSet): String = when (this) {
    AlertPersona.GRANDMA -> s.personaGrandma
    AlertPersona.PARENT -> s.personaParent
    AlertPersona.FRONTLINE -> s.personaMilitary
    AlertPersona.TEEN -> s.personaKid
    AlertPersona.ABROAD -> s.personaAbroad
    AlertPersona.AT_WORK -> s.personaWork
    AlertPersona.ON_ROAD -> s.personaRoad
}

internal fun AlertPersona.desc(s: Strings.StringSet): String = when (this) {
    AlertPersona.GRANDMA -> s.personaGrandmaDesc
    AlertPersona.PARENT -> s.personaParentDesc
    AlertPersona.FRONTLINE -> s.personaMilitaryDesc
    AlertPersona.TEEN -> s.personaKidDesc
    AlertPersona.ABROAD -> s.personaAbroadDesc
    AlertPersona.AT_WORK -> s.personaWorkDesc
    AlertPersona.ON_ROAD -> s.personaRoadDesc
}

private fun AlertPersona.icon(): ImageVector = when (this) {
    AlertPersona.GRANDMA -> Icons.Filled.Elderly
    AlertPersona.PARENT -> Icons.Filled.FamilyRestroom
    AlertPersona.FRONTLINE -> Icons.Filled.MilitaryTech
    AlertPersona.TEEN -> Icons.Filled.School
    AlertPersona.ABROAD -> Icons.Filled.Public
    AlertPersona.AT_WORK -> Icons.Filled.Work
    AlertPersona.ON_ROAD -> Icons.Filled.DirectionsCar
}
