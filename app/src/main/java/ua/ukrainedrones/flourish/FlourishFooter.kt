package ua.ukrainedrones

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
private fun StopPill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF3A2E00),
        contentColor = Color(0xFFF9A825),
        border = BorderStroke(1.dp, Color(0xFFF9A825).copy(alpha = 0.6f))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}

/**
 * Self-contained flourish footer: owns the entire bottom region during any flourish phase
 * (countdown, auto-strike, death animation, tally replay, MiG flyby). Replaces both the
 * ThreatStripFooter replay section and the old StopLayer — one component, one source of truth.
 *
 * Fully isolated from the threat strip: the caller decides which footer renders (`active`),
 * so this component knows nothing about threats or the map footer. Left-aligned stop button
 * + centered content (countdown digits / progress / message); the whole bar is a stop target.
 */
@Composable
fun BoxScope.FlourishFooter(
    active: Boolean,
    countdown: Int?,
    replayProgress: ReplayProgress?,
    strikeType: ThreatType?,
    pendingStrikeCount: Int,
    message: String?,
    stopLabel: String,
    language: AppLanguage,
    onStop: () -> Unit
) {
    if (!active) return

    val amber = Color(0xFFF9A825)
    val isCountdown = countdown != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(if (isCountdown) Color.Black.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onStop
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StopPill(stopLabel)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isCountdown) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (n in 3 downTo 1) {
                            val active = n == countdown
                            val dim by animateFloatAsState(
                                if (active) 1f else 0.35f,
                                tween(200), label = "cdDim$n"
                            )
                            val grow by animateFloatAsState(
                                if (active) 1f else 0.8f,
                                tween(200), label = "cdGrow$n"
                            )
                            Text(
                                text = "$n",
                                color = amber.copy(alpha = dim),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
                            )
                        }
                    }
                    val typeLabel = strikeType?.let { t ->
                        val info = ThreatTypeCatalog.INFO.getValue(t)
                        if (language == AppLanguage.UA) info.labelUa else info.labelEn
                    }
                    if (typeLabel != null) {
                        Text(
                            text = "$typeLabel · $pendingStrikeCount",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                } else if (replayProgress != null) {
                    val rp = replayProgress!!
                    val fraction by animateFloatAsState(
                        targetValue = rp.fraction,
                        animationSpec = tween(250),
                        label = "flourishProgress"
                    )
                    Text(
                        resolvingThreatsPhrase(rp.groupSize, language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = amber,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(amber)
                        )
                    }
                } else if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = amber,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}