package ua.ukrainedrones

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Drives a medium-intensity haptic pattern tied to the flyby animation progress.
 * Called each frame from the overlay; cheap enough to call every frame (~30 Hz).
 */
@Composable
fun FlybyHaptics(
    progress: Float,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    // Throttle to ~30 Hz to avoid spamming the vibrator
    val lastFire = remember { mutableStateOf(0L) }
    val progressRounded = (progress * 1000).roundToInt()

    if (!enabled || !vibrator.hasVibrator()) return

    val now = System.currentTimeMillis()
    if (now - lastFire.value < 33) return // ~30 Hz max
    lastFire.value = now

    val isApproach = progress < 0.5f
    val intensity = if (isApproach) {
        // Ramp up: 0.2 -> 1.0
        0.2f + 0.8f * (progress * 2f)
    } else {
        // Fade out: 1.0 -> 0.0
        1f - (progress - 0.5f) * 2f
    }.coerceIn(0.2f, 1f)

    val amplitude = (intensity * 255).roundToInt()

    if (BuildConfig.DEBUG) android.util.Log.d("VibTrace", "flyby progress=${"%.2f".format(progress)}")
    when {
        progress < 0.3f -> {
            // Approach: steady low rumble
            vibrator.vibrate(VibrationEffect.createOneShot(60, amplitude))
        }
        progress in 0.45f..0.55f -> {
            // Closest approach: strong pulse
            vibrator.vibrate(VibrationEffect.createOneShot(120, 255))
        }
        progress in 0.7f..0.9f -> {
            // Receding: fading pulses
            vibrator.vibrate(VibrationEffect.createOneShot(40, amplitude))
        }
    }
}