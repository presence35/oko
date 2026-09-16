package ua.ukrainedrones

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.unit.dp
import ua.ukrainedrones.IconCatalog
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.ThreatType
import kotlin.math.roundToInt

/** A single threat's settings card: icon + name/desc, compact Map/Alerts switches on the right. */
@Composable
internal fun ThreatSettingsCard(
    type: ThreatType,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    expanded: Boolean,
    onExpandChange: () -> Unit,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    flash: Boolean = false
) {
    val s = Strings.get(lang)
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
    val description = if (lang == AppLanguage.UA) info.descriptionUa else info.descriptionEn
    val details = if (lang == AppLanguage.UA) info.detailsUa else info.detailsEn
    val joke = if (lang == AppLanguage.UA) info.jokeUa else info.jokeEn
    val onMap = type !in hiddenTypes
    val onAlerts = type !in silencedTypes
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val typicalSpeed = ua.ukrainedrones.engine.typicalSpeedKmh(type, typeCatalog)?.roundToInt()

    Card(modifier = Modifier.fillMaxWidth().explainerFlash(flash)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThreatIcon(
                    type = type,
                    set = iconSet,
                    size = 36.dp,
                    contentDescription = label
                )
                Spacer(Modifier.width(12.dp))
                val expandInteraction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pressTick(expandInteraction)
                        .clickable(
                            interactionSource = expandInteraction,
                            indication = ripple(bounded = true),
                            onClick = onExpandChange
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = s.moreInfoLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    typicalSpeed?.let {
                        Surface(
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                "~$it ${s.speedUnit}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (type == ThreatType.UNKNOWN) 220.dp else 160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(
                                if (type == ThreatType.UNKNOWN) R.drawable.ic_unknown_cat
                                else IconCatalog.res(type, iconSet)
                            ),
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (type == ThreatType.UNKNOWN) Modifier.scale(1.15f) else Modifier
                                )
                        )
                    }
                    joke.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
