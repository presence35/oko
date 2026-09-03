package ua.ukrainedrones

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

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

private fun tick(context: Context) {
    if (BuildConfig.DEBUG) android.util.Log.d("VibTrace", "tick() source=pressTick")
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    // One-shot with full amplitude (same mechanism as the shoot-down flourish) — the
    // predefined EFFECT_TICK is a device-tuned "keyboard tap" that many OEMs render as a no-op.
    val effect = VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= 30) {
        vibrator.vibrate(
            effect,
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    } else {
        vibrator.vibrate(effect)
    }
}

/**
 * Imperative one-shot for call sites with no interaction source to observe (e.g. osmdroid's
 * marker click listener, where a tap must feel instant before anything composes). Callers
 * gate on [LocalHapticsEnabled] themselves.
 */
internal fun hapticTick(context: Context) = tick(context)