package com.odesaplay.oko

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One-time explainer for an advanced setting: title + visual example + real-life scenario. */
data class Explainer(
    val id: String,
    val title: String,
    val visual: String,
    val scenario: String,
    val diagram: GuideDiagram
)

/** Catalog of one-time explainers, keyed by the setting id, in display order. */
fun explainers(s: Strings.StringSet): List<Explainer> {
    val items = s.explainers.items
    // Positional by necessity (the copy is a flat list of triples), so the pairing lives here and
    // fails loudly instead of silently putting the night copy under the threat-toggles diagram —
    // or crashing on items[5] — when one language gains or loses an entry.
    require(items.size >= EXPLAINER_LAYOUT.size) {
        "explainers: expected ${EXPLAINER_LAYOUT.size} entries, got ${items.size}"
    }
    return EXPLAINER_LAYOUT.mapIndexed { i, (id, diagram) ->
        Explainer(id, items[i].first, items[i].second, items[i].third, diagram)
    }
}

/** The fixed id/diagram order [explainers] reads the copy in. */
private val EXPLAINER_LAYOUT: List<Pair<String, GuideDiagram>> = listOf(
    "threatToggles" to GuideDiagram.THREAT_TOGGLES,
    "officialAlerts" to GuideDiagram.NOTIF,
    "sirenOverride" to GuideDiagram.TOGGLES,
    "followMe" to GuideDiagram.FOLLOW,
    "cardSize" to GuideDiagram.CARD_SIZE,
    "nightMode" to GuideDiagram.NIGHT
)

/** Popup that explains what a just-toggled advanced setting does. */
@Composable
fun FeatureExplainerDialog(
    explainer: Explainer,
    s: Strings.StringSet,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss, interactionSource = rememberHapticInteractionSource()) { Text(s.explainers.gotIt) } },
        title = { Text(explainer.title) },
        text = {
            Column {
                FeatureDiagram(
                    kind = explainer.diagram,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    s.explainers.visualLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    explainer.visual,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    s.explainers.scenarioLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    explainer.scenario,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}