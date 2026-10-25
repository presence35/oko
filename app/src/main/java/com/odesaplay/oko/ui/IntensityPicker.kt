package com.odesaplay.oko

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The four ready-made setups as one row of equal quadrants plus a single subtext line, shared by
 * the first-launch wizard page and the expandable row in Settings → Reliability so the two can
 * never describe a preset differently.
 *
 * One shared subtext rather than four descriptions in the tiles: a quadrant is too narrow to hold
 * a sentence, and four paragraphs on one screen is four times the reading for a choice most people
 * make once. The subtext is the selected tile's description and nothing else, so it can never
 * describe a preset the user is not currently looking at.
 *
 * Stateless by contract: [selected] and [onPick] come from the caller, which owns the choice.
 * Picking fires [onPick] and nothing else — this block never writes a setting itself.
 */
@Composable
internal fun IntensityPicker(
    s: Strings.StringSet,
    selected: AppIntensity?,
    onPick: (AppIntensity) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (intensity in AppIntensity.values()) {
                PresetQuadrant(
                    icon = intensity.icon(),
                    label = intensity.title(s),
                    selected = intensity == selected,
                    isDefault = intensity == AppIntensity.DEFAULT,
                    defaultLabel = s.wizardIntensityDefaultBadge,
                    onClick = { onPick(intensity) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                selected.desc(s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One quadrant: icon over the name, filling an equal share of the row. Shared with the
 *  persona picker on Settings → Alerts so both grids stay one visual thing.
 *
 *  [muted] is the "nothing to restore" look: visible, so the control does not appear only after
 *  the user already needed it, but inert — not just faded. */
@Composable
internal fun PresetQuadrant(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    isDefault: Boolean,
    defaultLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val alpha = if (muted) 0.45f else 1f
    // Grows with the system font instead of ellipsising the label: a tile that cannot show its own
    // name is a tile nobody picks. Capped, because past ~1.6× the grid switches columns instead.
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Box(modifier = modifier.height((78 * scale).dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(
                    width = 1.5.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape
                )
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .pressTick(interaction)
                .clickable(
                    enabled = !muted,
                    interactionSource = interaction,
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = (if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // The shipped default, marked so a first-timer can see which tile is what they already
        // have. Deliberately NOT the `selected` state: a preset is a one-shot writer, so there is
        // no "currently applied" tile to show — only the one the app ships with.
        if (isDefault) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = defaultLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(14.dp)
            )
        }
    }
}

private fun AppIntensity.title(s: Strings.StringSet): String = when (this) {
    AppIntensity.ALWAYS_READY -> s.wizardIntensityReady
    AppIntensity.BALANCED -> s.wizardIntensityBalanced
    AppIntensity.QUIET -> s.wizardIntensityQuiet
    AppIntensity.WATCHING -> s.wizardIntensityWatching
}

private fun AppIntensity.desc(s: Strings.StringSet): String = when (this) {
    AppIntensity.ALWAYS_READY -> s.wizardIntensityReadyDesc
    AppIntensity.BALANCED -> s.wizardIntensityBalancedDesc
    AppIntensity.QUIET -> s.wizardIntensityQuietDesc
    AppIntensity.WATCHING -> s.wizardIntensityWatchingDesc
}

private fun AppIntensity.icon(): ImageVector = when (this) {
    AppIntensity.ALWAYS_READY -> Icons.Filled.Security
    AppIntensity.BALANCED -> Icons.Filled.Tune
    AppIntensity.QUIET -> Icons.Filled.NotificationsOff
    AppIntensity.WATCHING -> Icons.Filled.Visibility
}