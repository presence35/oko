package ua.ukrainedrones

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MapBlue = Color(0xFF1B2534)
private val GridGrey = Color(0xFF2E3A4C)
private val DimGrey = Color(0xFF7A8699)
private val White = Color(0xFFECEFF4)
private val Red = Color(0xFFD32F2F)
private val Yellow = Color(0xFFFFD500)
private val Gold = Color(0xFFE8B53A)
private val Blue = Color(0xFF005BBB)
private val Green = Color(0xFF4CAF50)

enum class GuideDiagram {
    LIVE, STRIP, CONN, ZONES, EDIT_ZONES, NOTIF, TOGGLES, FOLLOW, PIN, SHELTER,
    CARD_SIZE, CARD_READ, LANG, THREAT_TOGGLES, UPDATE, NIGHT, WIDGET
}

/** Animated mini-illustration for one feature-guide card. */
@Composable
fun FeatureDiagram(kind: GuideDiagram, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "diagram").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "diagram"
    )
    if (kind == GuideDiagram.LANG) {
        // The real flag emojis — the same ones Settings uses for the language switcher.
        Box(modifier = modifier.background(MapBlue), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83C\uDDFA\uD83C\uDDE6", fontSize = 24.sp)
                Spacer(Modifier.width(14.dp))
                Text("\uD83C\uDDE8\uD83C\uDDE6", fontSize = 24.sp)
            }
        }
        return
    }
    Canvas(modifier = modifier) {
        when (kind) {
            GuideDiagram.LANG -> Unit
            GuideDiagram.LIVE -> drawLive(t)
            GuideDiagram.STRIP -> drawStrip(t)
            GuideDiagram.CONN -> drawConn()
            GuideDiagram.ZONES -> drawZones(t)
            GuideDiagram.EDIT_ZONES -> drawEditZones()
            GuideDiagram.NOTIF -> drawNotif(t)
            GuideDiagram.TOGGLES -> drawToggles()
            GuideDiagram.FOLLOW -> drawFollow(t)
            GuideDiagram.PIN -> drawPin()
            GuideDiagram.SHELTER -> drawShelter(t)
            GuideDiagram.CARD_SIZE -> drawCardSize(t)
            GuideDiagram.CARD_READ -> drawCardRead()
            GuideDiagram.THREAT_TOGGLES -> drawThreatToggles()
            GuideDiagram.UPDATE -> drawUpdate(t)
            GuideDiagram.NIGHT -> drawNight()
            GuideDiagram.WIDGET -> drawWidget(t)
        }
    }
}

private fun DrawScope.bg() {
    drawRect(MapBlue)
}

private fun DrawScope.drawLive(t: Float) {
    bg()
    repeat(4) { i -> drawLine(GridGrey, Offset(size.width * (i + 1) / 5f, 0f), Offset(size.width * (i + 1) / 5f, size.height), 1.dp.toPx()) }
    repeat(3) { i -> drawLine(GridGrey, Offset(0f, size.height * (i + 1) / 4f), Offset(size.width, size.height * (i + 1) / 4f), 1.dp.toPx()) }
    drawCircle(DimGrey.copy(alpha = 0.7f), 3.dp.toPx(), Offset(size.width * 0.22f, size.height * 0.3f))
    drawCircle(DimGrey.copy(alpha = 0.7f), 3.dp.toPx(), Offset(size.width * 0.74f, size.height * 0.66f))
    val x = size.width * (0.15f + 0.7f * t)
    val y = size.height * 0.5f
    drawLine(Red.copy(alpha = 0.45f), Offset(size.width * 0.15f, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
    drawCircle(Red.copy(alpha = 0.25f), 7.dp.toPx(), Offset(x, y))
    drawCircle(Red, 4.dp.toPx(), Offset(x, y))
}

private fun DrawScope.drawStrip(t: Float) {
    bg()
    val n = 4
    val tileW = size.width / (n + 0.4f)
    val y = size.height * 0.52f
    val h = size.height * 0.34f
    for (i in 0 until n) {
        val x = size.width * 0.07f + i * tileW
        drawRoundRect(DimGrey.copy(alpha = 0.16f), Offset(x, y), Size(tileW * 0.72f, h), CornerRadius(4.dp.toPx()))
        val frac = 0.35f + 0.55f * ((t + i * 0.25f) % 1f)
        drawRoundRect(Yellow.copy(alpha = 0.85f), Offset(x + tileW * 0.12f, y + h - h * frac), Size(tileW * 0.48f, h * frac), CornerRadius(2.dp.toPx()))
    }
    bell(size.width * 0.07f + tileW * 0.36f, size.height * 0.26f, Yellow)
    bell(size.width * 0.07f + tileW * 1.36f, size.height * 0.26f, DimGrey, muted = true)
    drawCross(size.width * 0.07f + tileW * 2.36f, size.height * 0.26f, Red)
}

private fun DrawScope.bell(cx: Float, cy: Float, color: Color, muted: Boolean = false) {
    val r = 5.dp.toPx()
    drawCircle(color, r, Offset(cx, cy))
    drawLine(color, Offset(cx - r, cy + r * 0.6f), Offset(cx + r, cy + r * 0.6f), 2.dp.toPx(), StrokeCap.Round)
    drawCircle(color, 1.5.dp.toPx(), Offset(cx, cy + r * 1.1f))
    if (muted) {
        drawLine(Red, Offset(cx + r * 0.7f, cy - r * 0.7f), Offset(cx - r * 0.7f, cy + r * 0.7f), 2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawCross(cx: Float, cy: Float, color: Color) {
    val r = 4.dp.toPx()
    drawLine(color, Offset(cx - r, cy - r), Offset(cx + r, cy + r), 2.dp.toPx(), StrokeCap.Round)
    drawLine(color, Offset(cx + r, cy - r), Offset(cx - r, cy + r), 2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawConn() {
    bg()
    drawCircle(Green, 3.5.dp.toPx(), Offset(size.width * 0.72f, size.height * 0.2f))
    drawRoundRect(Green.copy(alpha = 0.25f), Offset(size.width * 0.66f, size.height * 0.14f), Size(size.width * 0.16f, size.height * 0.13f), CornerRadius(4.dp.toPx()))
    val x0 = size.width * 0.15f
    val y = size.height * 0.72f
    val w = size.width * 0.5f
    drawLine(DimGrey, Offset(x0, y), Offset(x0 + w, y), 2.dp.toPx(), StrokeCap.Round)
    drawLine(White, Offset(x0, y - 5.dp.toPx()), Offset(x0, y + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    drawLine(White, Offset(x0 + w, y - 5.dp.toPx()), Offset(x0 + w, y + 5.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    drawLine(Yellow, Offset(x0 + w * 0.45f, y - 4.dp.toPx()), Offset(x0 + w * 0.45f, y + 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawZones(t: Float) {
    bg()
    val c = Offset(size.width / 2f, size.height / 2f)
    val base = size.width * 0.3f
    val pulse = 1f + 0.08f * t
    drawCircle(Yellow.copy(alpha = 0.12f), base * pulse, c)
    drawCircle(Yellow, base * pulse, c, style = Stroke(2.dp.toPx()))
    drawCircle(Red.copy(alpha = 0.2f), base * 0.52f * pulse, c)
    drawCircle(Red, base * 0.52f * pulse, c, style = Stroke(2.dp.toPx()))
    drawGpsDot(c, 3.dp.toPx())
}

/** The blue GPS fix dot (with a thin white ring) — matches the map's location marker. */
private fun DrawScope.drawGpsDot(c: Offset, r: Float) {
    drawCircle(White, r * 1.5f, c)
    drawCircle(Color(0xFF2196F3), r, c)
}

private fun DrawScope.drawEditZones() {
    bg()
    val c = Offset(size.width * 0.22f, size.height * 0.44f)
    drawCircle(Yellow.copy(alpha = 0.15f), size.width * 0.14f, c)
    drawCircle(Yellow, size.width * 0.14f, c, style = Stroke(2.dp.toPx()))
    drawGpsDot(c, 3.dp.toPx())
    val y0 = size.height * 0.26f
    val y1 = size.height * 0.78f
    val sx = size.width * 0.56f
    drawLine(DimGrey.copy(alpha = 0.5f), Offset(sx, y1), Offset(sx, y0), 3.dp.toPx(), StrokeCap.Round)
    drawLine(White, Offset(sx, y1), Offset(sx, y0 + (y1 - y0) * 0.45f), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(Yellow, 5.dp.toPx(), Offset(sx, y0 + (y1 - y0) * 0.45f))
    bell(size.width * 0.8f, size.height * 0.5f, DimGrey, muted = true)
}

private fun DrawScope.drawNotif(t: Float) {
    bg()
    val w = size.width * 0.34f
    val x = size.width * 0.12f
    val y = size.height * 0.18f
    val h = size.height * 0.64f
    drawRoundRect(White.copy(alpha = 0.9f), Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
    drawRoundRect(Yellow.copy(alpha = 0.28f), Offset(x + w * 0.15f, y + h * 0.16f), Size(w * 0.7f, h * 0.3f), CornerRadius(3.dp.toPx()))
    val cx = size.width * 0.74f
    val cy = size.height * 0.5f
    val r = size.width * (0.06f + 0.12f * t)
    drawCircle(Red.copy(alpha = 0.35f), r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
    drawCircle(Red.copy(alpha = 0.7f), r * 0.7f, Offset(cx, cy), style = Stroke(2.dp.toPx()))
    drawCircle(Red, r * 0.42f, Offset(cx, cy), style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawToggles() {
    bg()
    switch(size.width * 0.26f, size.height * 0.3f, on = true)
    switch(size.width * 0.26f, size.height * 0.7f, on = false)
}

private fun DrawScope.switch(cx: Float, cy: Float, on: Boolean) {
    val w = size.width * 0.34f
    val h = size.height * 0.14f
    val color = if (on) Green else DimGrey
    drawRoundRect(color.copy(alpha = 0.35f), Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(h / 2))
    drawRoundRect(color, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(h / 2), style = Stroke(2.dp.toPx()))
    val thumbX = if (on) cx + w / 2 - h / 2 else cx - w / 2 + h / 2
    drawCircle(White, h * 0.42f, Offset(thumbX, cy))
}

private fun DrawScope.drawFollow(t: Float) {
    bg()
    val c = Offset(size.width / 2f, size.height / 2f)
    val pulse = 1f + 0.12f * t
    drawCircle(Blue.copy(alpha = 0.14f), size.width * 0.32f * pulse, c)
    drawCircle(Blue.copy(alpha = 0.24f), size.width * 0.2f * pulse, c)
    drawCircle(Blue, size.width * 0.1f * pulse, c, style = Stroke(2.dp.toPx()))
    drawCircle(Color(0xFF4FC3F7), size.width * 0.07f * pulse, c)
    drawCircle(Color(0xFFB3E5FC).copy(alpha = 0.5f), size.width * 0.045f * pulse, c)
}

private fun DrawScope.drawPin() {
    bg()
    val x0 = size.width * 0.08f
    val y0 = size.height * 0.12f
    val w = size.width * 0.5f
    val h = size.height * 0.76f
    drawRoundRect(GridGrey, Offset(x0, y0), Size(w, h), CornerRadius(5.dp.toPx()))
    repeat(3) { i -> drawLine(GridGrey, Offset(x0, y0 + h * (i + 1) / 4f), Offset(x0 + w, y0 + h * (i + 1) / 4f), 1.dp.toPx()) }
    repeat(3) { i -> drawLine(GridGrey, Offset(x0 + w * (i + 1) / 4f, y0), Offset(x0 + w * (i + 1) / 4f, y0 + h), 1.dp.toPx()) }
    val px = size.width * 0.72f
    val py = size.height * 0.42f
    val path = Path().apply {
        moveTo(px - 5.dp.toPx(), py + 6.dp.toPx())
        lineTo(px, py + 16.dp.toPx())
        lineTo(px + 5.dp.toPx(), py + 6.dp.toPx())
        close()
    }
    drawPath(path, Red)
    drawCircle(Red, 6.dp.toPx(), Offset(px, py))
    drawCircle(White, 2.5.dp.toPx(), Offset(px, py))
}

private fun DrawScope.drawCardSize(t: Float) {
    bg()
    val yBase = size.height * 0.82f
    val heights = floatArrayOf(0.4f, 0.68f)
    for (i in 0..1) {
        val cw = size.width * 0.24f
        val x = size.width * (0.16f + i * 0.44f)
        val ch = size.height * heights[i]
        val frac = 1f + 0.05f * ((t + i * 0.15f) % 1f)
        val color = if (i == 1) Yellow else DimGrey.copy(alpha = 0.6f)
        drawRoundRect(color, Offset(x, yBase - ch * frac), Size(cw, ch * frac), CornerRadius(4.dp.toPx()), style = Stroke(if (i == 1) 2.5.dp.toPx() else 1.5.dp.toPx()))
        if (i == 1) {
            drawRoundRect(Yellow.copy(alpha = 0.2f), Offset(x + cw * 0.15f, yBase - ch * frac + ch * frac * 0.14f), Size(cw * 0.7f, ch * frac * 0.16f), CornerRadius(2.dp.toPx()))
        }
    }
}

private fun DrawScope.drawCardRead() {
    bg()
    val x0 = size.width * 0.12f
    val y0 = size.height * 0.16f
    val w = size.width * 0.76f
    val h = size.height * 0.68f
    drawRoundRect(GridGrey, Offset(x0, y0), Size(w, h), CornerRadius(6.dp.toPx()))
    drawRoundRect(White.copy(alpha = 0.9f), Offset(x0, y0), Size(w, h), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    // skull
    val sx = x0 + w * 0.3f
    val sy = y0 + h * 0.4f
    val r = 6.dp.toPx()
    drawCircle(White, r, Offset(sx, sy))
    drawCircle(Color(0xFF11161F), 1.8.dp.toPx(), Offset(sx - r * 0.35f, sy - r * 0.2f))
    drawCircle(Color(0xFF11161F), 1.8.dp.toPx(), Offset(sx + r * 0.35f, sy - r * 0.2f))
    drawRoundRect(White, Offset(sx - r * 0.55f, sy + r * 0.6f), Size(r * 1.1f, r * 0.7f), CornerRadius(1.5.dp.toPx()))
    // gauge
    val gx = x0 + w * 0.62f
    val gy = sy - r
    val gw = w * 0.28f
    drawRoundRect(DimGrey.copy(alpha = 0.4f), Offset(gx, gy), Size(gw, 5.dp.toPx()), CornerRadius(3.dp.toPx()))
    drawRoundRect(Red, Offset(gx, gy), Size(gw * 0.7f, 5.dp.toPx()), CornerRadius(3.dp.toPx()))
    drawCircle(Yellow, 2.dp.toPx(), Offset(gx + gw * 0.45f, gy - 6.dp.toPx()))
    // distance + speed pills
    drawRoundRect(Green.copy(alpha = 0.8f), Offset(x0 + w * 0.3f, y0 + h * 0.72f), Size(w * 0.26f, h * 0.14f), CornerRadius(h * 0.07f))
    drawRoundRect(DimGrey.copy(alpha = 0.8f), Offset(x0 + w * 0.6f, y0 + h * 0.72f), Size(w * 0.26f, h * 0.14f), CornerRadius(h * 0.07f))
}

private fun DrawScope.drawThreatToggles() {
    bg()
    for (i in 0 until 8) {
        val col = i % 4
        val row = i / 4
        val cw = size.width * 0.17f
        val x = size.width * 0.1f + col * size.width * 0.21f
        val y = size.height * 0.22f + row * size.height * 0.4f
        val on = i % 3 != 2
        val color = if (on) Yellow else DimGrey.copy(alpha = 0.7f)
        drawRoundRect(color.copy(alpha = 0.2f), Offset(x, y), Size(cw, size.height * 0.16f), CornerRadius(3.dp.toPx()))
        drawRoundRect(color, Offset(x, y), Size(cw, size.height * 0.16f), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        if (on) drawCircle(color, 2.5.dp.toPx(), Offset(x + cw * 0.32f, y + size.height * 0.08f))
        else drawCross(x + cw * 0.32f, y + size.height * 0.08f, Red)
    }
}

private fun DrawScope.drawUpdate(t: Float) {
    bg()
    val y0 = size.height * 0.26f
    val trayY = size.height * 0.66f
    val cx = size.width * 0.5f
    drawLine(White, Offset(cx, y0), Offset(cx, trayY - 8.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
    val head = Path().apply {
        moveTo(cx, trayY - 2.dp.toPx())
        lineTo(cx - 7.dp.toPx(), trayY - 10.dp.toPx())
        moveTo(cx, trayY - 2.dp.toPx())
        lineTo(cx + 7.dp.toPx(), trayY - 10.dp.toPx())
    }
    drawPath(head, White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    drawRoundRect(White.copy(alpha = 0.9f), Offset(cx - 14.dp.toPx(), trayY), Size(28.dp.toPx(), 8.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(2.dp.toPx()))
    val bw = size.width * 0.5f
    drawRoundRect(DimGrey.copy(alpha = 0.4f), Offset(cx - bw / 2, size.height * 0.86f), Size(bw, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
    drawRoundRect(Green, Offset(cx - bw / 2, size.height * 0.86f), Size(bw * (0.3f + 0.6f * t), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
}

private fun DrawScope.drawNight() {
    bg()
    val mx = size.width * 0.72f
    val my = size.height * 0.3f
    val mr = size.width * 0.13f
    drawCircle(White, mr, Offset(mx, my))
    drawCircle(MapBlue, mr * 0.85f, Offset(mx + mr * 0.34f, my - mr * 0.14f))
    drawCircle(White.copy(alpha = 0.55f), 1.5.dp.toPx(), Offset(size.width * 0.15f, size.height * 0.18f))
    drawCircle(White.copy(alpha = 0.45f), 1.5.dp.toPx(), Offset(size.width * 0.34f, size.height * 0.4f))
    drawCircle(White.copy(alpha = 0.55f), 1.5.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.13f))
    val y0 = size.height * 0.58f
    val y1 = size.height * 0.9f
    val sx = size.width * 0.3f
    drawLine(DimGrey.copy(alpha = 0.5f), Offset(sx, y1), Offset(sx, y0), 3.dp.toPx(), StrokeCap.Round)
    drawLine(Red, Offset(sx, y1), Offset(sx, y0 + (y1 - y0) * 0.45f), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(Red, 5.dp.toPx(), Offset(sx, y0 + (y1 - y0) * 0.45f))
    val fx = size.width * 0.68f
    drawLine(DimGrey.copy(alpha = 0.5f), Offset(fx, y1), Offset(fx, y0), 3.dp.toPx(), StrokeCap.Round)
    drawLine(Yellow, Offset(fx, y1), Offset(fx, y0 + (y1 - y0) * 0.3f), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(Yellow, 5.dp.toPx(), Offset(fx, y0 + (y1 - y0) * 0.3f))
    bell(size.width * 0.5f, size.height * 0.48f, Yellow)
}

private fun DrawScope.drawShelter(t: Float) {
    bg()
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val r = size.height * 0.32f
    drawCircle(GridGrey, r, Offset(cx, cy))
    drawCircle(Green.copy(alpha = 0.22f), r * (0.85f + 0.15f * t), Offset(cx, cy))
    drawCircle(Green, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
    val arch = Path().apply {
        moveTo(cx - r * 0.5f, cy + r * 0.45f)
        lineTo(cx - r * 0.5f, cy - r * 0.1f)
        cubicTo(cx - r * 0.5f, cy - r * 0.55f, cx + r * 0.5f, cy - r * 0.55f, cx + r * 0.5f, cy - r * 0.1f)
        lineTo(cx + r * 0.5f, cy + r * 0.45f)
    }
    drawPath(arch, White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(Green, 3.dp.toPx(), Offset(cx, cy + r * 0.1f))
}

private fun DrawScope.drawWidget(t: Float) {
    bg()
    val pad = size.width * 0.14f
    val cardX = pad
    val cardY = size.height * 0.12f
    val cardW = size.width - pad * 2f
    val cardH = size.height - cardY * 2f
    val cr = 6.dp.toPx()
    drawRoundRect(GridGrey, Offset(cardX, cardY), Size(cardW, cardH), CornerRadius(cr))
    val headerH = cardH * 0.22f
    drawRoundRect(Gold, Offset(cardX, cardY), Size(cardW, headerH),
        CornerRadius(cr, cr))
    drawRoundRect(Gold, Offset(cardX, cardY + headerH * 0.5f), Size(cardW, headerH * 0.5f),
        CornerRadius(0f))
    val tx = cardX + cardW * 0.5f
    val ty = cardY + headerH * 0.55f
    val ts = headerH * 0.32f
    drawLine(Gold, Offset(tx, ty + ts), Offset(tx, ty - ts), 2.dp.toPx(), StrokeCap.Round)
    drawLine(Gold, Offset(tx - ts, ty + ts), Offset(tx - ts, ty - ts * 0.4f), 2.dp.toPx(), StrokeCap.Round)
    drawLine(Gold, Offset(tx + ts, ty + ts), Offset(tx + ts, ty - ts * 0.4f), 2.dp.toPx(), StrokeCap.Round)
    val rowY = cardY + headerH + cardH * 0.18f
    val icons = listOf(Red, Yellow, Blue, Green)
    val iw = cardW * 0.12f
    val startX = tx - iw * 1.5f
    for (i in icons.indices) {
        drawCircle(icons[i], iw * (0.7f + 0.3f * t) * (0.9f + 0.1f * i), Offset(startX + i * iw, rowY))
    }
    val chipY = cardY + headerH + cardH * 0.42f
    val chipW = cardW * 0.55f
    drawRoundRect(Yellow, Offset(cardX + cardW * 0.1f, chipY), Size(chipW, cardH * 0.14f),
        CornerRadius(3.dp.toPx()))
    val stripY = cardY + cardH - cardH * 0.2f
    drawRoundRect(Red, Offset(cardX + cardW * 0.08f, stripY),
        Size(cardW * 0.84f, cardH * 0.14f), CornerRadius(3.dp.toPx()))
    val alx = cardX + cardW * 0.16f
    val aly = stripY + cardH * 0.07f
    val aw = cardH * 0.06f
    drawPath(Path().apply {
        moveTo(alx, aly - aw)
        lineTo(alx + aw, aly + aw)
        lineTo(alx - aw, aly + aw)
        close()
    }, White)
}

