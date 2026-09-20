package com.presaince.oko

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun MoraleToggles(
    s: Strings.StringSet,
    calmMessagesEnabled: Boolean,
    flybyAnimationEnabled: Boolean,
    deathAnimationEnabled: Boolean,
    followBullet: Boolean,
    highQualityExplosions: Boolean,
    neutralizedTallyEnabled: Boolean,
    neutralizedTallyAllUkraine: Boolean,
    alarmEpisodeTallyEnabled: Boolean,
    onCalmMessagesChange: (Boolean) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
    onHighQualityExplosionsChange: (Boolean) -> Unit,
    onNeutralizedTallyChange: (Boolean) -> Unit,
    onNeutralizedTallyAllUkraineChange: (Boolean) -> Unit,
    onAlarmEpisodeTallyChange: (Boolean) -> Unit,
) {
    Column {
        AlertToggleRow(
            title = s.calmMessagesTitle,
            description = s.calmMessagesDesc,
            checked = calmMessagesEnabled,
            onCheckedChange = onCalmMessagesChange,
            icon = painterResource(R.drawable.ic_peace),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.flybyAnimationLabel,
            description = s.flybyAnimationDesc,
            checked = flybyAnimationEnabled,
            onCheckedChange = onFlybyAnimationChange,
            icon = painterResource(R.drawable.ic_mig),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.deathAnimationTitle,
            description = s.deathAnimationDesc,
            checked = deathAnimationEnabled,
            onCheckedChange = onDeathAnimationChange,
            icon = painterResource(R.drawable.ic_explosion),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(visible = deathAnimationEnabled) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                AlertToggleRow(
                    title = s.followBulletTitle,
                    description = s.followBulletDesc,
                    checked = followBullet,
                    onCheckedChange = onFollowBulletChange,
                    icon = painterResource(R.drawable.bullet),
                    iconTint = null
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.hdExplosionTitle,
                    description = s.hdExplosionDesc,
                    checked = highQualityExplosions,
                    onCheckedChange = onHighQualityExplosionsChange,
                    emoji = "\uD83D\uDD25"
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.neutralizedTallyTitle,
            description = s.neutralizedTallyDesc,
            checked = neutralizedTallyEnabled,
            onCheckedChange = onNeutralizedTallyChange,
            icon = rememberVectorPainter(Icons.Default.Notifications),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconBadge = "21"
        )
        if (neutralizedTallyEnabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.padding(start = 40.dp)) {
                AlertToggleRow(
                    title = s.neutralizedTallyAllUkraineTitle,
                    description = s.neutralizedTallyAllUkraineDesc,
                    checked = neutralizedTallyAllUkraine,
                    onCheckedChange = onNeutralizedTallyAllUkraineChange,
                    emoji = "🇺🇦"
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.alarmEpisodeTallyTitle,
            description = s.alarmEpisodeTallyDesc,
            checked = alarmEpisodeTallyEnabled,
            onCheckedChange = onAlarmEpisodeTallyChange,
            icon = rememberVectorPainter(Icons.Default.Notifications),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconBadge = "1"
        )
    }
}
