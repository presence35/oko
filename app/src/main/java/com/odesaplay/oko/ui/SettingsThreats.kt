package com.odesaplay.oko

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.odesaplay.oko.IconCatalog
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.ThreatType
import kotlin.math.roundToInt

/** A single threat type: a full-width art band, then name/desc with the Map/Alerts toggles, then
 *  the blurb. Deliberately card-less: the page body is already a card, so a card per type was
 *  card-in-card noise that gave the list no hierarchy. Separation is the caller's dividers plus
 *  the Fast/Slow group captions, and the page scrolls, so nothing needs folding. */
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .explainerFlash(flash)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            // The pack art is the point of this page: a 2.5x icon is the only way a user can
            // actually tell the Shahed apart from the FPV at a glance. It gets its own full-width
            // band above the name rather than sitting in the row beside it — at this size the row
            // layout has nowhere left for the label or the toggles.
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        typicalSpeed?.let {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    "~$it ${s.speedUnit}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
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
            // Clamped: with the per-type expansion gone there is no way to reveal more, so this has
            // to carry the gist on its own. `details` is clamped tighter than `joke` because it is
            // the factual half — the joke is pure flavour and reads fine truncated.
            val blurb = listOf(details, joke).filter { it.isNotBlank() }.joinToString(" ")
            if (blurb.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
