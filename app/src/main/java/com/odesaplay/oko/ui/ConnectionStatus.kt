package com.odesaplay.oko
import com.odesaplay.oko.theme.AppPalette

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The pill's one reading of how the source is doing — three tiers, three words. Anything that
 *  shows the source's state reads this instead of repeating the mapping. */
internal fun pillColor(neptunDown: Boolean, degraded: Boolean): Color = when {
    neptunDown -> Color(AppPalette.AlertRed)
    degraded -> Color(AppPalette.DegradedOrange)
    else -> Color(AppPalette.SafeGreen)
}

internal fun pillLabel(s: Strings.StringSet, neptunDown: Boolean, degraded: Boolean): String = when {
    neptunDown -> s.connOffline
    degraded -> s.connDegraded
    else -> s.connOnline
}

@Composable
internal fun ConnectionStatus(
    neptunDown: Boolean,
    degraded: Boolean,
    onOpenLogs: () -> Unit,
    s: Strings.StringSet,
    modifier: Modifier = Modifier
) {
    val connColor = pillColor(neptunDown, degraded)
    val label = pillLabel(s, neptunDown, degraded)
    val pillInteraction = remember { MutableInteractionSource() }
    // The word stays: the three states differ by colour alone, and colour is exactly what a
    // red-green colourblind reader cannot use. The description adds what the visible row cannot
    // say — that the pill is a button, and that tapping it opens the Logs screen.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .pressTick(pillInteraction)
            .clickable(
                interactionSource = pillInteraction,
                indication = ripple(bounded = true),
                onClick = onOpenLogs
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$label · ${s.logsTitle}"
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = connColor,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
