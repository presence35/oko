package ua.ukrainedrones

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.ukrainedrones.connection.NeptunConnectionClient

@Composable
internal fun ConnectionStatus(
    neptunDown: Boolean,
    degraded: Boolean,
    onOpenLogs: () -> Unit,
    showInfo: Boolean,
    onShowInfoChange: (Boolean) -> Unit,
    s: Strings.StringSet,
    modifier: Modifier = Modifier
) {
    // Three-tier connection: red offline / orange degraded / green online.
    val connColor = when {
        neptunDown -> Color(0xFFE57373)
        degraded -> Color(0xFFFB8C00)
        else -> Color(0xFF4CAF50)
    }
    val label = when {
        neptunDown -> s.connOffline
        degraded -> s.connDegraded
        else -> s.connOnline
    }
    val pillInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .pressTick(pillInteraction)
            .clickable(
                interactionSource = pillInteraction,
                indication = ripple(bounded = true),
                onClick = { onShowInfoChange(!showInfo) }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.neptun),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(connColor),
            modifier = Modifier.size(width = 14.dp, height = 14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = connColor,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
internal fun SourceStatusRow(color: Color, name: String, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(if (active) 1f else 0.65f)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}
