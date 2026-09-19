package ua.ukrainedrones

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Global toggle for press haptics — provided from the user setting at the app root. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * True when the device renders no animations ("Remove animations" accessibility toggle or a
 * zero Developer-options animator scale — both zero the global duration scale). Callers skip
 * motion work entirely instead of running transition machinery that completes instantly.
 */
@Composable
fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        android.provider.Settings.Global.getFloat(
            resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/**
 * Plays a small haptic tick while [source] reports a press. Driven by the interaction source
 * that already powers each control's press animation (a signal proven to fire in this app) —
 * not by pointer events, which proved unreliable here.
 *
 * The source must also be passed to the element's clickable/toggleable, so both observe it:
 * ```
 * val interaction = remember { MutableInteractionSource() }
 * Modifier.pressTick(interaction).clickable(interactionSource = interaction, ...)
 * ```
 *
 * Vibrates via the raw Vibrator service (same as the shot-down flourish) rather than Compose's
 * haptic feedback: USAGE_ALARM keeps the tick working even when system touch feedback is off.
 */
@Composable
fun Modifier.pressTick(source: InteractionSource): Modifier {
    val enabled = LocalHapticsEnabled.current
    if (!enabled) return this
    val appContext = LocalContext.current.applicationContext
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            tick(appContext)
        }
    }
    return this
}

/**
 * Safely plays a vibration on the ALARM channel with full backward compatibility:
 * - API >= 33: Uses [VibrationAttributes.createForUsage] with USAGE_ALARM.
 * - API 26..32: Uses [AudioAttributes] with USAGE_ALARM and CONTENT_TYPE_SONIFICATION to bypass touch-mute.
 * - API < 26: Falls back to legacy [Vibrator.vibrate].
 *
 * Catches hardware exceptions and OEM edge cases to prevent app crashes on non-standard Android builds.
 */
fun Vibrator.vibrateAlarm(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
    if (!hasVibrator()) return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val effect = VibrationEffect.createOneShot(durationMs, amplitude)
            val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            vibrate(effect, attributes)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(durationMs, amplitude)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vibrate(effect, audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrate(durationMs)
        }
    }
}

/**
 * Safely plays a standard vibration effect across all Android versions without crashing older devices.
 */
fun Vibrator.vibrateSafe(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
    if (!hasVibrator()) return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrate(durationMs)
        }
    }
}

private fun tick(context: Context) {
    if (BuildConfig.DEBUG) android.util.Log.d("VibTrace", "tick() source=pressTick")
    val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
    vibrator.vibrateAlarm(30L, VibrationEffect.DEFAULT_AMPLITUDE)
}

/**
 * Imperative one-shot for call sites with no interaction source to observe (e.g. osmdroid's
 * marker click listener, where a tap must feel instant before anything composes). Callers
 * gate on [LocalHapticsEnabled] themselves.
 */
internal fun hapticTick(context: Context) = tick(context)

@Composable
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    pressTick(interaction).clickable(
        interactionSource = interaction,
        indication = androidx.compose.foundation.LocalIndication.current,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
fun <T> rememberHapticClick(onValueChange: (T) -> Unit): (T) -> Unit {
    val appContext = LocalContext.current.applicationContext
    val enabled = LocalHapticsEnabled.current
    return remember(enabled, appContext, onValueChange) { { newValue ->
        if (enabled) tick(appContext)
        onValueChange(newValue)
    } }
}

@Composable
fun rememberHapticClick(onClick: () -> Unit): () -> Unit {
    val appContext = LocalContext.current.applicationContext
    val enabled = LocalHapticsEnabled.current
    return remember(enabled, appContext, onClick) {
        {
            if (enabled) tick(appContext)
            onClick()
        }
    }
}