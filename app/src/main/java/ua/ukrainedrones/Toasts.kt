package ua.ukrainedrones

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * In-app toast bus. Android's `Toast.setGravity` is a no-op on API 31+ (the system forces
 * bottom-center), so we render toasts ourselves to control placement: top (below the header,
 * [topInset]) by default, bottom when a card/popup is open (cardVisible) so the toast never
 * hides behind it. Callers keep the same [showToast] signature; the visual is [ToastHost],
 * placed once at the screen root. The pill uses a dark themed surface (not the theme's
 * inverseSurface, which reads as plain white in a dark theme).
 */
private data class ToastRequest(val text: String, val cardVisible: Boolean)

private val toastBus = MutableSharedFlow<ToastRequest>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

fun showToast(context: android.content.Context, text: CharSequence, cardVisible: Boolean = false) {
    toastBus.tryEmit(ToastRequest(text.toString(), cardVisible))
}

@Composable
fun ToastHost(topInset: Dp = 0.dp) {
    var current by remember { mutableStateOf<ToastRequest?>(null) }
    LaunchedEffect(Unit) {
        toastBus.collectLatest { req ->
            current = req
            delay(2200)
            current = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (current?.cardVisible == true) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 20.dp,
                    top = if (current?.cardVisible == true) 20.dp else topInset + 14.dp
                )
        ) {
            current?.let { req ->
                Surface(
                    tonalElevation = 3.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2A2A2E),
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 360.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF4A4A4E), RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A2A2E))
                ) {
                    Text(
                        req.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
