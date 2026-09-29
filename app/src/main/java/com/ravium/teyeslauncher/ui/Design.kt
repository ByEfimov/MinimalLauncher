package com.ravium.teyeslauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravium.teyeslauncher.R

/** Mockup height. All sizes in the UI are mockup pixels (1280×800). */
const val DesignHeight = 800f

object C {
    val Bg = Color(0xFF0C0E0F)
    val BgBottom = Color(0xFF08090A)
    val Card = Color(0xFF141618)
    val CardBottom = Color(0xFF111315)
    val Stroke = Color(0xFF26292C)
    val Divider = Color(0xFF2A2D30)
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xFFC9CCCE)
    val Muted = Color(0xFF8C9195)
    val Track = Color(0xFF3A3D40)
    // Accent colour (user-selectable in Настройки → Экран). Names kept for history: "Yellow" = accent.
    val Yellow: Color get() = Accent.color
    val YellowBorder: Color get() = androidx.compose.ui.graphics.lerp(Color(0xFF141618), Accent.color, 0.38f)
    val YellowBg: Color get() = androidx.compose.ui.graphics.lerp(Color(0xFF121416), Accent.color, 0.07f)
    val SignRed = Color(0xFFE5262B)
    val GuideRed = Color(0xFFF0342A)
    val GuideYellow = Color(0xFFF2D32A)
    val GuideGreen = Color(0xFF5CD14A)
    val CarPlayTop = Color(0xFF5BD66A)
    val CarPlayBottom = Color(0xFF34B84A)
}

/** Accent colour. Compose state → every screen repaints instantly when it changes. */
object Accent {
    val options = listOf(
        0xFFF5C518 to "Жёлтый", 0xFFFF9F0A to "Оранжевый", 0xFFFF453A to "Красный", 0xFFBF5AF2 to "Фиолетовый",
        0xFF3D8BFF to "Синий", 0xFF40C8E0 to "Бирюзовый", 0xFF34C759 to "Зелёный", 0xFFE5E5EA to "Белый",
    )
    var color by androidx.compose.runtime.mutableStateOf(Color(0xFFF5C518))
    fun load(ctx: android.content.Context) {
        color = Color(com.ravium.teyeslauncher.Prefs.str(ctx, com.ravium.teyeslauncher.Prefs.ACCENT, "FFF5C518").toLong(16).toInt())
    }
    fun set(ctx: android.content.Context, argb: Long) {
        com.ravium.teyeslauncher.Prefs.put(ctx, com.ravium.teyeslauncher.Prefs.ACCENT, argb.toString(16).uppercase())
        color = Color(argb.toInt())
    }
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val CardShape = RoundedCornerShape(16.dp)

/** Exactly one physical pixel. The UI is scaled to the screen, so "1.dp" would be a blurry 0.9–1.2 px line. */
val Hairline: androidx.compose.ui.unit.Dp
    @Composable get() = with(androidx.compose.ui.platform.LocalDensity.current) { 1f.toDp() }
val CardBrush = Brush.verticalGradient(listOf(C.Card, C.CardBottom))

// ---------- custom glyphs (drawn to match the mockup) ----------

@Composable
fun FourDotsIcon(size: Dp = 28.dp, color: Color = C.Text) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val r = s * 0.19f
    val st = Stroke(width = s * 0.08f)
    for (x in listOf(s * 0.25f, s * 0.75f)) for (y in listOf(s * 0.25f, s * 0.75f))
        drawCircle(color, r, Offset(x, y), style = st)
}

@Composable
fun GridIcon(size: Dp = 28.dp, color: Color = C.Text2) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val cell = s * 0.40f
    val st = Stroke(width = s * 0.075f)
    for (x in listOf(s * 0.04f, s * 0.56f)) for (y in listOf(s * 0.04f, s * 0.56f))
        drawRoundRect(color, Offset(x, y), Size(cell, cell), CornerRadius(s * 0.09f), style = st)
}

@Composable
fun PauseGlyph(size: Dp = 26.dp, color: Color = C.Text) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val w = s * 0.26f
    val r = CornerRadius(s * 0.07f)
    drawRoundRect(color, Offset(s * 0.16f, s * 0.04f), Size(w, s * 0.92f), r)
    drawRoundRect(color, Offset(s * 0.58f, s * 0.04f), Size(w, s * 0.92f), r)
}

@Composable
fun PlayGlyph(size: Dp = 26.dp, color: Color = C.Text) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val p = Path().apply { moveTo(s * 0.2f, s * 0.06f); lineTo(s * 0.92f, s * 0.5f); lineTo(s * 0.2f, s * 0.94f); close() }
    drawPath(p, color)
    drawPath(p, color, style = Stroke(width = s * 0.08f, join = StrokeJoin.Round))
}

@Composable
fun SkipGlyph(next: Boolean, size: Dp = 26.dp, color: Color = C.Text) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val bar = s * 0.13f
    fun x(v: Float) = if (next) v else s - v
    val tri = Path().apply { moveTo(x(s * 0.12f), s * 0.1f); lineTo(x(s * 0.76f), s * 0.5f); lineTo(x(s * 0.12f), s * 0.9f); close() }
    drawPath(tri, color)
    drawPath(tri, color, style = Stroke(width = s * 0.06f, join = StrokeJoin.Round))
    val bx = if (next) s * 0.78f else s * 0.22f - bar
    drawRoundRect(color, Offset(bx, s * 0.1f), Size(bar, s * 0.8f), CornerRadius(bar / 3))
}

@Composable
fun MusicNotesIcon(size: Dp = 28.dp, color: Color = C.Text2) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val sw = s * 0.075f
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // stems + beam
    val beam = Path().apply {
        moveTo(s * 0.36f, s * 0.76f); lineTo(s * 0.36f, s * 0.18f); lineTo(s * 0.86f, s * 0.06f); lineTo(s * 0.86f, s * 0.66f)
    }
    drawPath(beam, color, style = st)
    drawLine(color, Offset(s * 0.36f, s * 0.32f), Offset(s * 0.86f, s * 0.20f), sw, StrokeCap.Round)
    drawOval(color, Offset(s * 0.10f, s * 0.66f), Size(s * 0.27f, s * 0.21f), style = st)
    drawOval(color, Offset(s * 0.60f, s * 0.56f), Size(s * 0.27f, s * 0.21f), style = st)
}

/** Generic green projection tile (play-in-circle). */
@Composable
fun CarPlayTile(size: Dp = 55.dp) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    drawRoundRect(Brush.verticalGradient(listOf(C.CarPlayTop, C.CarPlayBottom)), cornerRadius = CornerRadius(s * 0.23f))
    val c = Offset(s / 2, s / 2)
    drawCircle(Color.White, s * 0.27f, c, style = Stroke(width = s * 0.055f))
    val p = Path().apply {
        moveTo(c.x - s * 0.08f, c.y - s * 0.13f); lineTo(c.x + s * 0.14f, c.y); lineTo(c.x - s * 0.08f, c.y + s * 0.13f); close()
    }
    drawPath(p, Color.White)
}

@Composable
fun MusicTileFallback(size: Dp, color: Color) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension
    val sw = s * 0.09f
    drawLine(color, Offset(s * 0.62f, s * 0.14f), Offset(s * 0.62f, s * 0.70f), sw, StrokeCap.Round)
    drawLine(color, Offset(s * 0.62f, s * 0.14f), Offset(s * 0.84f, s * 0.24f), sw, StrokeCap.Round)
    drawOval(color, Offset(s * 0.30f, s * 0.60f), Size(s * 0.34f, s * 0.26f))
}
