package ua.ukrainedrones

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ShelterPopupCard(
    lang: AppLanguage,
    shelter: NearestShelter,
    withKids: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = Strings.get(lang)
    val context = LocalContext.current
    val name = if (lang == AppLanguage.EN) shelterNameEn(shelter.shelter.name) else shelter.shelter.name
    val type = shelter.shelter.type

    val typeColor = when (type) {
        ShelterType.MOBILE -> Color(0xFFFFA000) // Amber/Orange
        ShelterType.BUNKER -> Color(0xFF2196F3) // Royal Blue
        ShelterType.BASIC -> Color(0xFF4CAF50)  // Emerald Green
    }

    val typeLabel = when (type) {
        ShelterType.MOBILE -> s.shelterTypeMobile
        ShelterType.BUNKER -> s.shelterTypeBunker
        ShelterType.BASIC -> s.shelterTypeBasic
    }

    val dismissInteraction = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .pressTick(dismissInteraction)
            .clickable(
                interactionSource = dismissInteraction,
                indication = ripple(bounded = true),
                onClick = onDismiss
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E2124)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Header: Type badge, distance, and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(typeColor)
                    )
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = typeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        formatDistance(shelter.distanceMeters, s),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Shelter Name / Location
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            // Walking times & Open in Maps button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val (walkAdultIcon, walkChildIcon) = walkIconPair(withKids)
                    val iconTint = Color.White.copy(alpha = 0.8f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WalkFigureIcon(walkAdultIcon, height = 20.dp, tint = iconTint)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            String.format(s.shelterWalkMinutes, shelter.walkMinutesAdult),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }

                    if (withKids) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            WalkFigureIcon(walkAdultIcon, height = 20.dp, tint = iconTint)
                            Spacer(Modifier.width(2.dp))
                            if (walkChildIcon != null) {
                                WalkFigureIcon(walkChildIcon, height = 12.dp, tint = iconTint)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                String.format(s.shelterWalkMinutes, shelter.walkMinutesKid),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                // Action button: Open in maps
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable {
                            val uri = Uri.parse("geo:0,0?q=${shelter.shelter.lat},${shelter.shelter.lon}($name)")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            runCatching { context.startActivity(intent) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        s.shelterOpenInMaps,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private fun formatDistance(meters: Double, s: Strings.StringSet): String =
    if (meters < 1000) String.format(s.shelterDistanceM, meters.toInt())
    else String.format(s.shelterDistanceKm, meters / 1000.0)
