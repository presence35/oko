package com.odesaplay.oko

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odesaplay.oko.IconCatalog
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.engine.isFastType
import com.odesaplay.oko.theme.AppPalette
import kotlin.math.min
import kotlin.math.roundToInt

/** A single threat type as a bordered card that stands apart from its neighbours and from the
 *  page body — a thin divider was not enough separation. Order: title + speed + Fast/Slow tag +
 *  the two toggles, short description, the 2.5x pack art, then the full facts and the italic
 *  joke (the page scrolls, so nothing is truncated). The Fast/Slow pill repeats the group a row
 *  belongs to, so the distinction survives a mid-list scan now that the group headers are gone. */
@Composable
internal fun ThreatTypeRow(
    type: ThreatType,
    lang: AppLanguage,
    iconSet: ThreatIconSet,
    hiddenTypes: Set<ThreatType>,
    silencedTypes: Set<ThreatType>,
    onThreatMapToggle: (ThreatType, Boolean) -> Unit,
    onThreatAlertToggle: (ThreatType, Boolean) -> Unit,
    flash: Boolean = false
) {
    val s = Strings.get(lang)
    val info = ThreatTypeCatalog.INFO.getValue(type)
    val label = info.label(lang)
    val description = info.description(lang)
    val details = info.details(lang)
    val joke = info.joke(lang)
    val onMap = type !in hiddenTypes
    val onAlerts = type !in silencedTypes
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val typicalSpeed = com.odesaplay.oko.engine.typicalSpeedKmh(type, typeCatalog)?.roundToInt()
    val fast = isFastType(type, typeCatalog)

    Surface(
        modifier = Modifier.fillMaxWidth().explainerFlash(flash),
        shape = RoundedCornerShape(16.dp),
        color = Color(AppPalette.SurfaceVariant),
        border = BorderStroke(1.dp, Color(AppPalette.Border))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // Title row: name, speed, Fast/Slow tag, then the two toggles hard right. The
            // label+pills share the leftover width so the toggles always sit at the far edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    typicalSpeed?.let {
                        Spacer(Modifier.width(6.dp))
                        Pill("~$it ${s.speedUnit}", MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        if (fast) s.fastGroupLabel else s.slowGroupLabel,
                        if (fast) ZoneRedColor else ZoneYellowColor
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconToggle(
                    icon = Icons.Filled.Place,
                    contentDescription = s.threatMapLabel,
                    on = onMap,
                    enabled = true,
                    onClick = { onThreatMapToggle(type, !onMap) }
                )
                IconToggle(
                    icon = Icons.Filled.Notifications,
                    contentDescription = s.threatAlertLabel,
                    on = onAlerts,
                    enabled = true,
                    onClick = { onThreatAlertToggle(type, !onAlerts) }
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            // The pack art is the point of this page: a 2.5x icon is the only way a user can
            // actually tell the Shahed apart from the FPV at a glance, so it keeps its own
            // full-width band under the title. Unknown is the exception: its cat is a poster,
            // so it gets the lettering treatment instead of a plain band.
            if (type == ThreatType.UNKNOWN) {
                UnknownCatPoster(s)
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(IconCatalog.res(type, iconSet)),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // Facts stay persona-proof in `details`; `joke` is the single flavour slot a
            // persona may adjust, rendered separately so the two never blend. Neither is
            // truncated — the page scrolls, so a safety fact never hides behind an ellipsis.
            if (details.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (joke.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    joke,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Overlay geometry, measured against `ic_unknown_cat.webp` (717x662) and kept as fractions of
 *  the art. The poster box locks the art's aspect ratio, so the numbers hold at any width and
 *  neither overlay needs a measured size. */
private const val CAT_ART_ASPECT = 717f / 662f

/** The crate's front face in the art, in TL / TR / BR / BL order, as fractions of the art width
 *  and height. The name is warped onto this quad, so its perspective comes from the drawing
 *  instead of a hand-tuned tilt and width. Measured on `ic_unknown_cat.webp`. */
private val CRATE_FACE = listOf(
    Offset(0.382f, 0.707f),
    Offset(0.773f, 0.650f),
    Offset(0.773f, 0.816f),
    Offset(0.382f, 0.903f)
)
private const val BUBBLE_LEFT = 0.655f
private const val BUBBLE_TOP = 0.200f
private const val BUBBLE_WIDTH = 0.335f

/** How much smaller the long word of the name is drawn next to the short one, and how far down
 *  the finished block sits from the face's centre (as a fraction of the face's height). */
private const val CRATE_LONG_LINE_SCALE = 0.90f
private const val CRATE_LETTER_DROP = 0.08f

/** The warp follows the crate's face and lands the lettering at about -8.7 deg; the crate's
 *  bottom edge runs at -15.5 deg, and this is the extra counterclockwise turn that puts the
 *  letters on that line. Both measured off `ic_unknown_cat.webp`. */
private const val CRATE_LETTER_TILT = -6.8f

/** The crate lettering mirrors the original art: the first word on its own line, the rest under
 *  it, so UA/EN/RU all break the same way instead of relying on where the text happens to wrap. */
private fun crateLabel(name: String): List<String> =
    name.uppercase().split(' ', limit = 2)

/**
 * The cat's name lettered on the crate front. Both the size and the perspective come from the
 * art: the block is scaled uniformly to sit inside [CRATE_FACE]'s bounding box and that box is
 * then warped onto the face quad, so the letters converge with the planks the way painted type
 * would — and every language lands the same fit without per-language constants. Because the fit
 * is relative, a large system font scale grows the letters into the face rather than out of it.
 * [CRATE_LONG_LINE_SCALE] sets the long word against the short one, [CRATE_LETTER_DROP] nudges
 * the pair down off the crate's rim and [CRATE_LETTER_TILT] turns the finished block onto the
 * crate's bottom edge.
 */
@Composable
private fun CrateLettering(name: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleSmall.copy(
        fontSize = 12.sp,
        lineHeight = 13.sp,
        color = Color(0xFF1B1206),
        fontWeight = FontWeight.Black
    )
    Canvas(modifier) {
        val text = crateLabel(name)
        val probe = text.map { measurer.measure(it, style) }
        val blockWidth = probe.maxOf { it.size.width }.toFloat()
        val blockHeight = probe.sumOf { it.size.height }.toFloat()
        if (blockWidth <= 0f || blockHeight <= 0f) return@Canvas
        val face = CRATE_FACE.map { Offset(it.x * size.width, it.y * size.height) }
        val left = face.minOf { it.x }
        val top = face.minOf { it.y }
        val right = face.maxOf { it.x }
        val bottom = face.maxOf { it.y }
        // Text scales linearly with its size, so one measuring pass gives the factor that fits the
        // block into the face's box; re-measuring at that size leaves the warp as the only
        // transform, and the letters stop depending on a hand-picked font size or width.
        val fit = min((right - left) / blockWidth, (bottom - top) / blockHeight)
        val fittedStyle = style.copy(
            fontSize = style.fontSize * fit,
            lineHeight = style.lineHeight * fit
        )
        val lines = text.map { measurer.measure(it, fittedStyle) }
        // The Schrödinger word is the longer of the two in every language; drawing it a touch
        // smaller evens the pair out the way the original lettering reads.
        val longest = lines.indices.maxByOrNull { lines[it].size.width } ?: 0
        val drawn = lines.mapIndexed { index, line ->
            if (index != longest) line
            else measurer.measure(
                text[index],
                fittedStyle.copy(
                    fontSize = fittedStyle.fontSize * CRATE_LONG_LINE_SCALE,
                    lineHeight = fittedStyle.lineHeight * CRATE_LONG_LINE_SCALE
                )
            )
        }
        val drawnWidth = drawn.maxOf { it.size.width }.toFloat()
        val drawnHeight = drawn.sumOf { it.size.height }.toFloat()
        val blockLeft = left + ((right - left) - drawnWidth) / 2f
        val blockTop = top + ((bottom - top) - drawnHeight) / 2f + (bottom - top) * CRATE_LETTER_DROP
        // The face's box -> the face quad: one homography gives the lettering the crate's own
        // perspective, so the letters converge with the planks the way painted type would.
        val warp = android.graphics.Matrix().apply {
            setPolyToPoly(
                floatArrayOf(left, top, right, top, right, bottom, left, bottom), 0,
                floatArrayOf(
                    face[0].x, face[0].y, face[1].x, face[1].y,
                    face[2].x, face[2].y, face[3].x, face[3].y
                ), 0,
                4
            )
        }
        withTransform({ transform(Matrix().apply { setFrom(warp) }) }) {
            withTransform({
                rotate(
                    degrees = CRATE_LETTER_TILT,
                    pivot = Offset(blockLeft + drawnWidth / 2f, blockTop + drawnHeight / 2f)
                )
            }) {
                var lineTop = blockTop
                drawn.forEach { line ->
                    drawText(
                        line,
                        topLeft = Offset(blockLeft + (drawnWidth - line.size.width) / 2f, lineTop)
                    )
                    lineTop += line.size.height
                }
            }
        }
    }
}

/**
 * The Unknown sticker as a poster: the cat's name lettered on the crate and a quote in a bubble
 * by its head. Both texts are overlays read from [Strings], not baked into the art, so UA/EN/RU
 * each get real text. The crate lettering is near-black on wood like the original art; the
 * bubble is a flat dark plate that wraps its own quote, so a longer translation grows the plate
 * instead of spilling out of it.
 */
@Composable
private fun UnknownCatPoster(s: Strings.StringSet) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(CAT_ART_ASPECT),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        Image(
            painter = painterResource(R.drawable.ic_unknown_cat),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        val bubbleShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = w * BUBBLE_LEFT, y = h * BUBBLE_TOP)
                .width(w * BUBBLE_WIDTH)
                .drawBehind {
                    val tail = Path().apply {
                        moveTo(size.width * 0.30f, size.height)
                        lineTo(size.width * 0.72f, size.height)
                        lineTo(-16.dp.toPx(), size.height - 4.dp.toPx())
                        close()
                    }
                    drawPath(tail, Color(AppPalette.Surface))
                    drawPath(tail, Color(AppPalette.Border), style = Stroke(1.5.dp.toPx()))
                }
                .background(Color(AppPalette.Surface), bubbleShape)
                .border(1.5.dp, Color(AppPalette.Border), bubbleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = s.unknownCatQuote,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = Color(AppPalette.TextPrimary)
            )
        }
        CrateLettering(name = s.unknownCatName, modifier = Modifier.matchParentSize())
    }
}

/** A compact tinted capsule, e.g. the speed badge or the per-row Fast/Slow tag. */
@Composable
private fun Pill(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = tint,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
