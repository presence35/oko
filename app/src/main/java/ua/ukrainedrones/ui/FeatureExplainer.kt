package ua.ukrainedrones

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
fun explainers(s: Strings.StringSet): List<Explainer> = listOf(
        Explainer("threatToggles", s.explainers.items[0].first, s.explainers.items[0].second,
            s.explainers.items[0].third, GuideDiagram.THREAT_TOGGLES),
        Explainer("officialAlerts", s.explainers.items[1].first, s.explainers.items[1].second,
            s.explainers.items[1].third, GuideDiagram.NOTIF),
        Explainer("sirenOverride", s.explainers.items[2].first, s.explainers.items[2].second,
            s.explainers.items[2].third, GuideDiagram.TOGGLES),
        Explainer("followMe", s.explainers.items[3].first, s.explainers.items[3].second,
            s.explainers.items[3].third, GuideDiagram.FOLLOW),
        Explainer("cardSize", s.explainers.items[4].first, s.explainers.items[4].second,
            s.explainers.items[4].third, GuideDiagram.CARD_SIZE),
        Explainer("nightMode", s.explainers.items[5].first, s.explainers.items[5].second,
            s.explainers.items[5].third, GuideDiagram.NIGHT)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.explainers.gotIt) } },
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