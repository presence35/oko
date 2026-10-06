package com.odesaplay.oko

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.odesaplay.oko.IconCatalog
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.engine.isFastType
import com.odesaplay.oko.theme.AppPalette
import kotlin.math.roundToInt

/** A single threat type as a bordered card that stands apart from its neighbours and from the
 *  page body — a thin divider was not enough separation. Order: title + speed + Fast/Slow tag +
 *  the two toggles, short description, the 2.5x pack art, then the full facts and the italic
 *  joke (the page scrolls, so nothing is truncated). The Fast/Slow pill repeats the group a row
 *  belongs to, so the distinction survives a mid-list scan now that the group headers are gone. */
@Composable
internal fun ThreatTypeRow(
    type: ThreatType,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    flash: Boolean = false
) {
    val s = Strings.get(lang)
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = info.label(lang)
    val description = info.description(lang)
    val details = info.details(lang)
    val joke = info.joke(lang)
    val onMap = type !in hiddenTypes
    val onAlerts = type !in silencedTypes
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val typicalSpeed = com.odesaplay.oko.engine.typicalSpeedKmh(type, typeCatalog)?.roundToInt()
    val fast = isFastType(type, typeCatalog)

    Surface(
        modifier = Modifier.fillMaxWidth().explainerFlash(flash),
        shape = RoundedCornerShape(16.dp),
        color = Color(AppPalette.SurfaceVariant),
        border = BorderStroke(1.dp, Color(AppPalette.Border))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Title row: name, speed, Fast/Slow tag, then the two toggles hard right. The
            // label+pills share the leftover width so the toggles always sit at the far edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    typicalSpeed?.let {
                        Spacer(Modifier.width(6.dp))
                        Pill("~$it ${s.speedUnit}", MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        if (fast) s.fastGroupLabel else s.slowGroupLabel,
                        if (fast) Color(AppPalette.AlertYellow) else TurtleGreen
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconToggle(
                    icon = Icons.Filled.Place,
                    contentDescription = s.threatMapLabel,
                    on = onMap,
                    enabled = true,
                    onClick = { onThreatMapToggle(type, !onMap) }
                )
                IconToggle(
                    icon = Icons.Filled.Notifications,
                    contentDescription = s.threatAlertLabel,
                    on = onAlerts,
                    enabled = true,
                    onClick = { onThreatAlertToggle(type, !onAlerts) }
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            // The pack art is the point of this page: a 2.5x icon is the only way a user can
            // actually tell the Shahed apart from the FPV at a glance, so it keeps its own
            // full-width band under the title.
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(
                        if (type == ThreatType.UNKNOWN) R.drawable.ic_unknown_cat
                        else IconCatalog.res(type, iconSet)
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (type == ThreatType.UNKNOWN) Modifier.scale(1.15f) else Modifier)
                )
            }
            // Facts stay persona-proof in `details`; `joke` is the single flavour slot a
            // persona may adjust, rendered separately so the two never blend. Neither is
            // truncated — the page scrolls, so a safety fact never hides behind an ellipsis.
            if (details.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (joke.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    joke,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A compact tinted capsule, e.g. the speed badge or the per-row Fast/Slow tag. */
@Composable
private fun Pill(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = tint,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
