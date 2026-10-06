package com.odesaplay.oko

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Single-select chip with the standard selector styling (same as OverlapModeChip). [caption] and
 *  [intensity] turn the plain label into a small self-explaining card: a caption says what the
 *  option actually looks like, and 1–3 dots rank how heavy it is on the phone. */
@Composable
internal fun SelectorChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    intensity: Int = 0,
    onClick: () -> Unit
) {
    val accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.hapticClickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (intensity > 0) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { i ->
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (i < intensity) accent else accent.copy(alpha = 0.22f))
                        )
                    }
                }
            }
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun MoraleToggles(
    s: Strings.StringSet,
    voice: MoraleVoice,
    onVoiceChange: (MoraleVoice) -> Unit,
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
    val lang = s.language
    // Pure derivation: no timers, no state — midnight rollover lands on next recomposition.
    val effective = resolveMoraleVoice(voice)
    val pack = remember(lang, effective) { moraleVoicePack(lang, effective) }
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                moraleVoiceSectionTitle(lang),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (voice == MoraleVoice.RANDOM) randomTodayLabel(lang, effective.label(lang))
                else effective.label(lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            MoraleVoice.values().toList().chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { v ->
                        SelectorChip(
                            label = v.label(lang),
                            selected = v == voice,
                            modifier = Modifier.weight(1f),
                            onClick = { onVoiceChange(v) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.calmMessagesTitle,
            description = pack.calmDesc,
            checked = calmMessagesEnabled,
            onCheckedChange = onCalmMessagesChange,
            icon = painterResource(R.drawable.ic_peace),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.flybyAnimationLabel,
            description = pack.flybyDesc,
            checked = flybyAnimationEnabled,
            onCheckedChange = onFlybyAnimationChange,
            icon = painterResource(R.drawable.ic_mig),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.deathAnimationTitle,
            description = pack.deathDesc,
            checked = deathAnimationEnabled,
            onCheckedChange = onDeathAnimationChange,
            icon = painterResource(R.drawable.ic_explosion),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedVisibility(visible = deathAnimationEnabled) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                AlertToggleRow(
                    title = s.followBulletTitle,
                    description = pack.followBulletDesc,
                    checked = followBullet,
                    onCheckedChange = onFollowBulletChange,
                    icon = painterResource(R.drawable.bullet),
                    iconTint = null
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = s.hdExplosionTitle,
                    description = pack.hdExplosionDesc,
                    checked = highQualityExplosions,
                    onCheckedChange = onHighQualityExplosionsChange,
                    emoji = "\uD83D\uDD25"
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.neutralizedTallyTitle,
            description = pack.tallyDesc,
            checked = neutralizedTallyEnabled,
            onCheckedChange = onNeutralizedTallyChange,
            icon = rememberVectorPainter(Icons.Default.Notifications),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconBadge = "1",
            iconBadgeSize = 14.sp,
            iconBadgeSuperscriptSuffix = "2"
        )
        if (neutralizedTallyEnabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.padding(start = 40.dp)) {
                AlertToggleRow(
                    title = s.neutralizedTallyAllUkraineTitle,
                    description = pack.allUkraineDesc,
                    checked = neutralizedTallyAllUkraine,
                    onCheckedChange = onNeutralizedTallyAllUkraineChange,
                    emoji = "🇺🇦"
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AlertToggleRow(
            title = s.alarmEpisodeTallyTitle,
            description = pack.alarmEpisodeDesc,
            checked = alarmEpisodeTallyEnabled,
            onCheckedChange = onAlarmEpisodeTallyChange,
            icon = rememberVectorPainter(Icons.Default.Notifications),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconBadge = "1",
            iconBadgeSize = 14.sp,
            iconBadgeSuperscriptSuffix = "2"
        )
    }
}
