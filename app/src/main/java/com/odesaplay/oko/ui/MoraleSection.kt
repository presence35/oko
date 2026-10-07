package com.odesaplay.oko

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A static thumbnail of one explosion style, drawn from the style's own [ExplosionPreview]
 * descriptor. Cheap and clock-free: one composition, no animation, no bitmap cache — the card is
 * only alive while the settings screen is open. Kept in one place so every card reads the same
 * way and a style only has to declare colours and a body plan.
 */
@Composable
internal fun ExplosionPreviewThumb(
    preview: ExplosionPreview,
    modifier: Modifier = Modifier
) {
    val hot = remember(preview.hot) { Color(preview.hot) }
    val mid = remember(preview.mid) { Color(preview.mid) }
    Canvas(modifier = modifier) {
        val r = size.minDimension * 0.34f * preview.scale
        val c = center
        when (preview.shape) {
            ExplosionShape.RINGS -> {
                val ring = mid
                repeat(3) { i ->
                    val rr = r * (0.55f + i * 0.42f)
                    drawCircle(
                        color = ring.copy(alpha = (0.9f - i * 0.26f).coerceAtLeast(0.12f)),
                        radius = rr,
                        center = c,
                        style = Stroke(width = r * 0.13f)
                    )
                }
                drawCircle(color = hot, radius = r * 0.32f, center = c)
            }
            ExplosionShape.BALL -> {
                drawCircle(color = mid.copy(alpha = 0.55f), radius = r, center = c)
                drawCircle(color = mid, radius = r * 0.72f, center = c)
                drawCircle(color = hot, radius = r * 0.34f, center = c)
            }
            ExplosionShape.BLOOM -> {
                drawCircle(color = mid.copy(alpha = 0.35f), radius = r * 1.18f, center = c)
                drawCircle(color = mid.copy(alpha = 0.6f), radius = r * 0.88f, center = c)
                drawCircle(color = hot.copy(alpha = 0.9f), radius = r * 0.46f, center = c)
                // The smoke column is what makes Aftermath read as Aftermath.
                drawCircle(color = Color(0xFF3A3734).copy(alpha = 0.55f), radius = r * 0.34f, center = c + Offset(0f, r * 1.35f))
                drawCircle(color = Color(0xFF3A3734).copy(alpha = 0.32f), radius = r * 0.24f, center = c + Offset(r * 0.35f, r * 1.85f))
            }
            ExplosionShape.STREAK -> {
                repeat(9) { i ->
                    val ang = (i * (2.0 * PI / 9.0) + 0.3).toFloat()
                    val reach = r * (1.15f + (i % 3) * 0.22f)
                    val dir = Offset(cos(ang), sin(ang)) * reach
                    drawLine(
                        color = if (i % 3 == 0) hot else mid,
                        start = c + dir * 0.28f,
                        end = c + dir,
                        strokeWidth = r * 0.16f,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(color = hot, radius = r * 0.3f, center = c)
            }
            ExplosionShape.SHARDS -> {
                drawCircle(color = mid.copy(alpha = 0.4f), radius = r * 0.9f, center = c)
                repeat(7) { i ->
                    val ang = (i * (2.0 * PI / 7.0) + 0.9).toFloat()
                    val dir = Offset(cos(ang), sin(ang))
                    val reach = r * (0.8f + (i % 3) * 0.3f)
                    val mid0 = c + dir * r * 0.55f
                    val tip = c + dir * reach
                    val side = Offset(-dir.y, dir.x) * r * 0.17f
                    drawPath(
                        path = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(mid0.x + side.x, mid0.y + side.y)
                            lineTo(mid0.x - side.x, mid0.y - side.y)
                            close()
                        },
                        color = if (i % 2 == 0) hot else mid
                    )
                }
            }
        }
    }
}

/**
 * One card of the explosion-style picker: a static thumbnail of what the style looks like, its
 * name, and how heavy it is on the phone. [intensity] is 0..3 dots.
 */
@Composable
internal fun DeathStyleCard(
    style: DeathAnimationStyle,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preview = remember(style) { style.create().preview }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.hapticClickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                ExplosionPreviewThumb(preview, Modifier.size(40.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/**
 * The explosion-style picker as a fixed 3x2 grid of [DeathStyleCard]s. Fixed rather than
 * flow-wrapped so the six options never reflow as labels change length.
 */
@Composable
internal fun DeathAnimStyleGrid(
    selected: DeathAnimationStyle,
    labelOf: (DeathAnimationStyle) -> String,
    onChange: (DeathAnimationStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val styles = remember { listOf(
        DeathAnimationStyle.SIMPLE,
        DeathAnimationStyle.CLASSIC,
        DeathAnimationStyle.ADVANCED,
        DeathAnimationStyle.BREACH,
        DeathAnimationStyle.FILM,
        DeathAnimationStyle.AFTERMATH
    ) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        styles.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { style ->
                    DeathStyleCard(
                        style = style,
                        label = labelOf(style),
                        selected = selected == style,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onChange(style) }
                    )
                }
            }
        }
    }
}

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
    neutralizedTallyEnabled: Boolean,
    neutralizedTallyAllUkraine: Boolean,
    alarmEpisodeTallyEnabled: Boolean,
    onCalmMessagesChange: (Boolean) -> Unit,
    onFlybyAnimationChange: (Boolean) -> Unit,
    onDeathAnimationChange: (Boolean) -> Unit,
    onFollowBulletChange: (Boolean) -> Unit,
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
