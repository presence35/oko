package ua.ukrainedrones

import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-size MiG-31K takeoff flourish: a screen-wide jet crosses the viewport on a random
 * bearing with a contrail, then the threat card opens ([onFinished]). Pure overlay — consumes
 * no input, moves no camera.
 *
 * Each icon pack has two exhausts (behind-nozzle anchors) defined explicitly in
 * [IconCatalog.aviationGeometry]; the jet's rotation comes from the pack's baked facing via
 * [AviationFlyby.spriteTransform]. No silhouette detection.
 */
@Composable
fun AviationFlybyOverlay(
    show: AviationFlybyShow,
    iconSet: ThreatIconSet,
    onFinished: (String) -> Unit
) {
    val context = LocalContext.current
    val geometry = remember(iconSet) { IconCatalog.aviationGeometry(iconSet) }

    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val progress = remember(show.tick) { Animatable(0f) }
    val fadeProgress = remember { Animatable(1f) }
    val audioPlayer = remember(show.tick) { FlybyAudioPlayer.create(context) }

    androidx.compose.runtime.DisposableEffect(audioPlayer) {
        onDispose { audioPlayer.release() }
    }

    LaunchedEffect(show.tick) {
        audioPlayer.start()
        progress.snapTo(0f)
        progress.animateTo(1f, tween(show.durationMs.toInt(), easing = LinearEasing))
        try {
            fadeProgress.animateTo(0f, tween(2000, easing = LinearEasing))
        } finally {
            try { audioPlayer.stop() } catch (_: Exception) {}
        }
        onFinished(show.threatId)
    }
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { canvasPx = it }) {
        if (canvasPx == IntSize.Zero) return@Box
        val planeW = canvasPx.width * 0.6f
        val trailLen = planeW * 1.4f
        val trailStroke = (planeW * 0.02f).coerceIn(6f, 14f)
        val (entry, exit) = AviationFlyby.endpoints(
            show.courseDeg, canvasPx.width.toFloat(), canvasPx.height.toFloat(), planeW
        )
        val x = lerp(entry.first, exit.first, progress.value)
        val y = lerp(entry.second, exit.second, progress.value)
        val (rotZ, flipped) = AviationFlyby.spriteTransform(
            show.courseDeg.toFloat(), geometry.facingDeg
        )
        FlybyHaptics(progress.value)
        Canvas(modifier = Modifier.matchParentSize()) {
            val rad = Math.toRadians(rotZ.toDouble())
            val fade = fadeProgress.value
            val contrailAlpha = 0.55f * fade
            val slideBy = trailLen * (1f - fade)
            val effLen = trailLen * fade
            for (exhaust in geometry.exhausts) {
                var ox = exhaust.anchorXFrac * planeW
                var oy = exhaust.anchorYFrac * planeW
                if (flipped) oy = -oy
                val wx = cos(rad) * ox - sin(rad) * oy
                val wy = sin(rad) * ox + cos(rad) * oy
                val anchorX0 = (x + wx).toFloat()
                val anchorY0 = (y + wy).toFloat()
                val facingBiasRad = Math.toRadians((geometry.facingDeg + exhaust.angleBiasDeg).toDouble())
                var nx = sin(facingBiasRad).toFloat()
                var ny = -cos(facingBiasRad).toFloat()
                if (flipped) ny = -ny
                val noseX = (cos(rad) * nx - sin(rad) * ny).toFloat()
                val noseY = (sin(rad) * nx + cos(rad) * ny).toFloat()
                val anchorX = (anchorX0 + slideBy * noseX).toFloat()
                val anchorY = (anchorY0 + slideBy * noseY).toFloat()
                val tailX = (anchorX - effLen * noseX).toFloat()
                val tailY = (anchorY - effLen * noseY).toFloat()
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0f),
                            Color.White.copy(alpha = contrailAlpha)
                        ),
                        start = Offset(tailX, tailY),
                        end = Offset(anchorX, anchorY)
                    ),
                    start = Offset(tailX, tailY),
                    end = Offset(anchorX, anchorY),
                    strokeWidth = trailStroke,
                    cap = StrokeCap.Round
                )
            }
        }
        PlaneSprite(planeW, rotZ, flipped, iconSet, alpha = 0.35f, tint = Color.Black,
            modifier = Modifier.offset {
                IntOffset(
                    (x - planeW / 2 + planeW * 0.02f).roundToInt(),
                    (y - planeW / 2 + planeW * 0.03f).roundToInt()
                )
            })
        PlaneSprite(planeW, rotZ, flipped, iconSet,
            modifier = Modifier.offset {
                IntOffset((x - planeW / 2).roundToInt(), (y - planeW / 2).roundToInt())
            })
    }
}

@Composable
private fun PlaneSprite(
    sidePx: Float,
    rotationZDeg: Float,
    flip: Boolean,
    iconSet: ThreatIconSet,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    tint: Color = Color.Unspecified
) {
    val side = with(LocalDensity.current) { sidePx.toDp() }
    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer {
                this.alpha = alpha
                this.rotationZ = rotationZDeg
                this.scaleY = if (flip) -1f else 1f
            }
    ) {
        ThreatIcon(type = ThreatType.AVIATION, set = iconSet, size = side, tint = tint)
    }
}
