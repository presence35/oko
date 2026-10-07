package com.odesaplay.oko.beta

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.odesaplay.oko.UploadState
import com.odesaplay.oko.Strings

/** Single source of truth for the feedback panel. Upload progress lives in [UploadState]. */
sealed interface FeedbackSheet {
    data object Closed : FeedbackSheet

    data class Open(
        val category: FeedbackCategory = FeedbackCategory.BUG,
        val message: String = "",
        val includeShot: Boolean = true,
        val includeLogs: Boolean = true,
        val shot: Bitmap? = null,
        val shotLoading: Boolean = false,
        val fullscreen: Boolean = false
    ) : FeedbackSheet
}

private data class CategoryMeta(val kind: FeedbackCategory, val icon: ImageVector, val label: String)

@Composable
fun FeedbackPanel(
    s: Strings.StringSet,
    state: FeedbackSheet.Open,
    onState: (FeedbackSheet.Open) -> Unit,
    upload: UploadState,
    onSend: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sending = upload is UploadState.Building || upload is UploadState.Sending
    val canSend = state.message.trim().length >= 3 && !sending && upload !is UploadState.Done
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = s.feedbackTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
            }
        }
        Text(
            text = s.feedbackAnon,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (meta in listOf(
                CategoryMeta(FeedbackCategory.BUG, Icons.Filled.BugReport, s.feedbackBug),
                CategoryMeta(FeedbackCategory.IMPROVEMENT, Icons.Filled.Lightbulb, s.feedbackImprovement),
                CategoryMeta(FeedbackCategory.QUESTION, Icons.Filled.HelpOutline, s.feedbackQuestion)
            )) {
                FilterChip(
                    selected = state.category == meta.kind,
                    onClick = { onState(state.copy(category = meta.kind)) },
                    label = { Text(meta.label) },
                    leadingIcon = { Icon(imageVector = meta.icon, contentDescription = null) },
                    enabled = !sending
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.message,
            onValueChange = { onState(state.copy(message = it)) },
            label = { Text(s.feedbackHint) },
            minLines = 3,
            maxLines = 6,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.includeShot) {
                ShotThumb(
                    shot = state.shot,
                    loading = state.shotLoading,
                    onZoom = { onState(state.copy(fullscreen = true)) }
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(text = s.feedbackShot, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.includeShot,
                onCheckedChange = { onState(state.copy(includeShot = it)) },
                enabled = !sending && (state.shot != null || state.shotLoading)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Icon(imageVector = Icons.Filled.Description, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text = s.feedbackLogs, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.includeLogs,
                onCheckedChange = { onState(state.copy(includeLogs = it)) },
                enabled = !sending
            )
        }
        Spacer(Modifier.height(8.dp))
        when (upload) {
            is UploadState.Done -> Text(text = s.feedbackSent, style = MaterialTheme.typography.bodyMedium)
            is UploadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${s.feedbackFailed}: ${upload.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> Unit
        }
        Button(
            onClick = { if (upload is UploadState.Done) onClose() else onSend() },
            enabled = canSend || upload is UploadState.Done || upload is UploadState.Failed,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(imageVector = Icons.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (upload is UploadState.Done) s.okButton else s.feedbackSend)
        }
    }
    if (state.fullscreen && state.shot != null) {
        Dialog(
            onDismissRequest = { onState(state.copy(fullscreen = false)) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .clickable { onState(state.copy(fullscreen = false)) },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    bitmap = state.shot.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ShotThumb(shot: Bitmap?, loading: Boolean, onZoom: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = shot != null, onClick = onZoom),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            shot != null -> androidx.compose.foundation.Image(
                bitmap = shot.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
            else -> Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}
