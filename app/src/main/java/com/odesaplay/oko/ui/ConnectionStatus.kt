package com.odesaplay.oko
import com.odesaplay.oko.theme.AppPalette

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

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
    // The word stays: the three states differ by colour alone, and colour is exactly what a
    // red-green colourblind reader cannot use. The description adds what the visible row cannot
    // say — that the pill is a button, and that tapping it opens the Logs screen.
    SourcePill(
        description = "$label · ${s.logsTitle}",
        onClick = onOpenLogs,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(R.drawable.neptun),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(connColor),
            modifier = Modifier.size(width = 14.dp, height = 14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = connColor,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** The pill's one reading of how the source is doing — three tiers, three words. The wizard's
 *  pill reads the same source, so it borrows these instead of repeating the mapping. */
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

/** The pill itself: dark rounded chip, row padding, and — only when there is somewhere to go —
 *  the tap target. One shell, so a pill on the wizard can never drift from the map header's.
 *  The tap target sits inside the padding on purpose: the whole chip stays tappable. */
@Composable
internal fun SourcePill(
    description: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val shell = modifier
        .clip(RoundedCornerShape(50))
        .background(Color.Black.copy(alpha = 0.55f))
        .padding(horizontal = 8.dp, vertical = 3.dp)
    val pillInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = if (onClick == null) shell else shell
            .pressTick(pillInteraction)
            .clickable(
                interactionSource = pillInteraction,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
