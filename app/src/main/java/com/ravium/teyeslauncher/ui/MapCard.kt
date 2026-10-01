package com.ravium.teyeslauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Shared between the map engine and the buttons on top of it. */
class MapController {
    var following by mutableStateOf(true)
    var lastTouch = 0L
    fun touched() { lastTouch = SystemClock.elapsedRealtime(); following = false }
    fun recenter() { following = true; lastTouch = 0 }
    /** Show the whole route for a few seconds, then follow the car again. */
    fun overview() { following = false; lastTouch = SystemClock.elapsedRealtime() - 7_000 }
}

private val RouteColor = 0xFF3D8BFF.toInt()

/**
 * Car marker: a rounded navigation arrow (blue gradient, white rim, soft shadow) on a faint halo — used by both map engines.
 * Drawn once into a bitmap; the map rotates it by the travel direction.
 */
fun carArrowBitmap(ctx: Context, sizeDp: Float = 54f): Bitmap {
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(40)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val s = px / 2f
    // halo
    c.drawCircle(s, s, s * 0.98f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(s, s, s * 0.98f, intArrayOf(0x553D8BFF, 0x223D8BFF, 0x003D8BFF), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
    })
    // arrow: tip, right wing, notch, left wing — corners rounded
    val path = Path().apply {
        moveTo(s, s * 0.30f)
        lineTo(s + s * 0.44f, s * 1.52f)
        lineTo(s, s * 1.27f)
        lineTo(s - s * 0.44f, s * 1.52f)
        close()
    }
    val round = android.graphics.CornerPathEffect(px * 0.06f)
    // soft shadow + white rim (drawn wider, so it shows as an outline around the fill)
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL_AND_STROKE; strokeWidth = px * 0.075f
        strokeJoin = Paint.Join.ROUND; pathEffect = round
        setShadowLayer(px * 0.07f, 0f, px * 0.03f, 0x66000000)
    })
    // blue gradient fill
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; pathEffect = round
        shader = android.graphics.LinearGradient(s, s * 0.3f, s, s * 1.5f, 0xFF5AAEFF.toInt(), 0xFF1A6CF0.toInt(), android.graphics.Shader.TileMode.CLAMP)
    })
    // subtle highlight on the left half — gives the arrow a little volume
    c.drawPath(Path().apply { moveTo(s, s * 0.36f); lineTo(s, s * 1.24f); lineTo(s - s * 0.38f, s * 1.45f); close() },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF; pathEffect = round })
    return b
}

fun flagBitmap(ctx: Context): Bitmap {
    val px = (30 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.drawCircle(px / 2f, px / 2f, px * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RouteColor })
    c.drawCircle(px / 2f, px / 2f, px * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = px * 0.1f })
    return b
}

/**
 * Чистая «капля»-метка: круглая голова с белым кольцом и остриём точно в координате.
 * [strong] = выбранная/нажатая точка (красная, крупнее); иначе синий маркер результата поиска.
 */
fun pinBitmap(ctx: Context, strong: Boolean = false): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val scale = if (strong) 1.18f else 1f
    val w = (30 * d * scale).toInt().coerceAtLeast(24)
    val h = (40 * d * scale).toInt().coerceAtLeast(32)
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val cx = w / 2f
    val r = w * 0.34f                  // радиус головы
    val cy = r + h * 0.04f             // центр головы
    val tipY = h - h * 0.05f           // остриё (на координате)
    val top = if (strong) 0xFFFF5B6B.toInt() else 0xFF4E9BFF.toInt()
    val bot = if (strong) 0xFFE8323F.toInt() else 0xFF2F7BF5.toInt()
    // тень-«пятно» под остриём
    c.drawOval(cx - r * 0.5f, tipY - r * 0.16f, cx + r * 0.5f, tipY + r * 0.10f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 })
    // форма капли: голова-круг + сходящийся к острию «хвост»
    val path = Path().apply {
        val k = r * 0.78f
        moveTo(cx, tipY)
        cubicTo(cx - k, cy + r * 0.78f, cx - r, cy + r * 0.35f, cx - r, cy)
        cubicTo(cx - r, cy - r * 1.33f, cx + r, cy - r * 1.33f, cx + r, cy)
        cubicTo(cx + r, cy + r * 0.35f, cx + k, cy + r * 0.78f, cx, tipY)
        close()
    }
    // белая обводка
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL_AND_STROKE; strokeWidth = w * 0.11f
        strokeJoin = Paint.Join.ROUND; setShadowLayer(w * 0.09f, 0f, w * 0.03f, 0x4D000000)
    })
    // цветная заливка (вертикальный градиент)
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = android.graphics.LinearGradient(cx, cy - r, cx, cy + r, top, bot, android.graphics.Shader.TileMode.CLAMP)
    })
    // белая серединка
    c.drawCircle(cx, cy, r * 0.40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    return b
}

/**
 * Always-on map in the big card.
 *  • Яндекс Карты (with traffic) when a MapKit key is configured, otherwise OpenStreetMap — both work in Russia.
 *  • Follows the car, rotates by travel direction, route line, search and "home" in the bottom-right pill.
 */
@Composable
fun MapCard(s: LauncherState, modifier: Modifier) {
    val ctx = LocalContext.current
    val ctl = remember { MapController() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (!ctl.following && SystemClock.elapsedRealtime() - ctl.lastTouch > 12_000) ctl.following = true
        }
    }
    // new destination → short overview of the whole route
    val dest = s.nav.route?.destination
    LaunchedEffect(dest) { if (dest != null) ctl.overview() }

    Box(modifier.clip(CardShape).background(Color(0xFF111315)).border(Hairline, C.Stroke, CardShape)) {
        if (YandexMaps.enabled) YandexLayer(s, ctl, Modifier.fillMaxSize()) else OsmLayer(s, ctl, Modifier.fillMaxSize())

        // top-left: next manoeuvre of OUR route (like Яндекс Карты); without a route — hint from a running navigator
        Column(Modifier.align(Alignment.TopStart).padding(16.dp).widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val route = s.nav.route
            if (route != null) ManeuverPanel(s.nav.progress)
            else NavInfo.hint?.let { NavHintChip(it) { s.openNavigator() } }
            val cam = s.vehicle.limits.cameraAhead
            if (cam != null && remember(s.settingsVersion) { Prefs.bool(ctx, Prefs.CAMERA_WARN, true) }) CameraChip(cam)
            s.updater.available?.let { rel ->
                if (!s.updater.busy) StatusChip("Доступно обновление ${rel.version} — установить") { s.updater.install() }
                else StatusChip(s.updater.status)
            }
            when {
                s.nav.busy && s.nav.route == null -> StatusChip("Строю маршрут…")
                s.nav.message != null -> StatusChip(s.nav.message!!) { s.nav.message = null }
                s.vehicle.location == null -> StatusChip(if (!s.vehicle.hasPermission) "Нет доступа к геопозиции" else "Поиск GPS…")
                route != null && !s.status.online -> StatusChip("Нет интернета — веду по GPS, маршрут сохранён")
            }
        }

        // top-right: full-screen navigator (with the current destination if there is one)
        RoundButton(Icons.Outlined.OpenInFull, "Открыть навигатор", Modifier.align(Alignment.TopEnd).padding(top = 15.dp, end = 14.dp)) {
            s.openNavigator()
        }

        // bottom-right: [к машине] above one pill with [домой | поиск]
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ctl.following) RoundButton(Icons.Outlined.MyLocation, "К машине") { ctl.recenter() }
            Row(
                Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xB3101315)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(30.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillIcon(Icons.Outlined.Home, "Домой") {
                    val home = s.nav.home
                    when {
                        home == null -> s.overlay = com.ravium.teyeslauncher.Overlay.Search(setHome = true)
                        s.nav.route?.destination == home -> s.nav.clear()
                        else -> s.nav.routeTo(home, s.vehicle.location)
                    }
                }
                Box(Modifier.width(Hairline).height(30.dp).background(Color(0x33FFFFFF)))
                PillIcon(Icons.Outlined.StarOutline, "Избранное") {
                    s.overlay = if (s.nav.favorites.isEmpty()) com.ravium.teyeslauncher.Overlay.Search() else com.ravium.teyeslauncher.Overlay.Favorites
                }
                Box(Modifier.width(Hairline).height(30.dp).background(Color(0x33FFFFFF)))
                PillIcon(Icons.Outlined.Search, "Поиск") { s.overlay = com.ravium.teyeslauncher.Overlay.Search() }
                Box(Modifier.width(Hairline).height(30.dp).background(Color(0x33FFFFFF)))
                PillIcon(Icons.Outlined.DownloadForOffline, "Карты без интернета") { s.overlay = com.ravium.teyeslauncher.Overlay.Offline }
                // dot while a region is downloading
                if (OfflineMaps.available && OfflineMaps.anyDownloading)
                    Box(Modifier.offset(x = (-22).dp, y = (-12).dp).size(8.dp).clip(CircleShape).background(C.Yellow))
            }
        }

        // bottom-left: time · distance · arrival (remaining), like the Яндекс Карты bottom bar
        s.nav.route?.let { r ->
            Box(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 26.dp, end = 230.dp)) { RouteChip(s, r, s.nav.progress) }
        }

        // tapped a point on the map → во время поиска показываем плашку на карте;
        // на главном экране данные показываются слева (вместо плеера), поэтому здесь не дублируем
        s.nav.tapTarget?.let { t ->
            if (s.overlay != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { TapTargetChip(s, t) }
        }

        if (!YandexMaps.enabled) Text("© OpenStreetMap", style = TextStyle(fontSize = 10.sp, color = Color(0x99FFFFFF)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(56.dp).clip(CircleShape).background(Color(0xB3101315)).border(Hairline, Color(0x33FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun PillIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 62.dp, height = 56.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun RouteChip(s: LauncherState, r: Route, p: Progress?) {
    val isHome = r.destination == s.nav.home
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6101315)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .clickable { s.openNavigator() }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isHome) Icons.Outlined.Home else Icons.Outlined.Flag, null, tint = Color(0xFF6FA8FF), modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            val secs = p?.remainingS ?: r.durationS
            val mins = (secs / 60).toInt().coerceAtLeast(1)
            val dur = if (mins >= 60) "${mins / 60} ч ${mins % 60} мин" else "$mins мин"
            val arrive = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + (secs * 1000).toLong()))
            Text("$dur · ${fmtDistance(p?.remainingM ?: r.distanceM)} · $arrive", style = TextStyle(fontFamily = Inter, fontSize = 19.sp, color = C.Text, fontWeight = FontWeight.SemiBold))
            Text(if (isHome) "Домой" else r.destination.name, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = C.Text2),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { s.nav.clear() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Сбросить маршрут", tint = C.Text2, modifier = Modifier.size(22.dp))
        }
    }
}

/** Tapped a point on the map: show it and offer to route there. */
@Composable
private fun TapTargetChip(s: LauncherState, t: Place) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xF0101315)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Place, null, tint = Color(0xFFFF6B75), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 320.dp)) {
            Text("Точка на карте", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = C.Muted))
            Text(t.name, style = TextStyle(fontFamily = Inter, fontSize = 17.sp, color = C.Text, fontWeight = FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(C.YellowBg).border(1.5.dp, C.YellowBorder, RoundedCornerShape(14.dp))
                .clickable {
                    s.nav.rememberRecent(t); s.nav.clearPins(); s.overlay = null
                    s.nav.routeTo(t, s.vehicle.location)
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Flag, null, tint = C.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Маршрут сюда", style = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = C.Text, fontWeight = FontWeight.SemiBold))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { s.nav.tapTarget = null }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Отмена", tint = C.Text2, modifier = Modifier.size(22.dp))
        }
    }
}

/** Big manoeuvre arrow + distance + street — the same layout as Яндекс Карты in navigation mode. */
@Composable
private fun ManeuverPanel(p: Progress?) {
    val next = p?.next
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xF0101315)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(18.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2F7BF5)), contentAlignment = Alignment.Center) {
            Icon(turnIcon(next?.turn ?: Turn.STRAIGHT), null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.padding(end = 10.dp)) {
            Text(if (p == null) "…" else fmtDistance(p.toNextM), style = TextStyle(fontFamily = Inter, fontSize = 30.sp, color = C.Text, fontWeight = FontWeight.SemiBold))
            val street = next?.street?.takeIf { it.isNotBlank() }
            Text(street ?: (next?.text ?: "Прямо"), style = TextStyle(fontFamily = Inter, fontSize = 17.sp, color = C.Text2),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (street != null) Text(next.text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = C.Muted), maxLines = 1)
        }
    }
}

private fun turnIcon(t: Turn): ImageVector = when (t) {
    Turn.STRAIGHT -> Icons.Filled.Straight
    Turn.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft; Turn.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Turn.LEFT -> Icons.Filled.TurnLeft; Turn.RIGHT -> Icons.Filled.TurnRight
    Turn.SHARP_LEFT -> Icons.Filled.TurnSharpLeft; Turn.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Turn.UTURN -> Icons.Filled.UTurnLeft
    Turn.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Turn.EXIT_LEFT -> Icons.Filled.RampLeft; Turn.EXIT_RIGHT -> Icons.Filled.RampRight
    Turn.FORK_LEFT -> Icons.Filled.ForkLeft; Turn.FORK_RIGHT -> Icons.Filled.ForkRight
    Turn.FINISH -> Icons.Filled.SportsScore
}

@Composable
private fun StatusChip(text: String, onClick: (() -> Unit)? = null) {
    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xD9101315))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = C.Text2))
    }
}

// ============================ OpenStreetMap engine ============================

private object OsmTiles {
    /** invert + hue-rotate(180°) + slight desaturation: a dark theme from standard OSM tiles */
    val darkFilter = ColorMatrixColorFilter(floatArrayOf(
        0.127f, -0.933f, -0.094f, 0f, 237.5f,
        -0.278f, -0.528f, -0.094f, 0f, 237.5f,
        -0.278f, -0.933f, 0.311f, 0f, 237.5f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

private fun initOsm(ctx: Context) {
    val c = Configuration.getInstance()
    c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    c.userAgentValue = "MinimalDrive/${BuildConfig.VERSION_NAME} (${ctx.packageName})"
    c.osmdroidBasePath = File(ctx.filesDir, "osmdroid")
    c.osmdroidTileCache = File(ctx.cacheDir, "tiles")
    c.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
}

private class CarOverlay(private val arrow: Bitmap) : Overlay() {
    var point: GeoPoint? = null
    var bearing = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val p = point ?: return
        val px = mapView.projection.toPixels(p, null)
        c.save()
        c.rotate(bearing, px.x.toFloat(), px.y.toFloat()) // canvas already rotates with the map
        c.drawBitmap(arrow, px.x - arrow.width / 2f, px.y - arrow.height / 2f, paint)
        c.restore()
    }
}

private class FlagOverlay(private val flag: Bitmap) : Overlay() {
    var point: GeoPoint? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val p = point ?: return
        val px = mapView.projection.toPixels(p, null)
        c.drawBitmap(flag, px.x - flag.width / 2f, px.y - flag.height / 2f, paint)
    }
}

/** Search-result pins + the tapped point. Teardrop tip sits exactly on the coordinate. */
private class PinsOverlay(private val pin: Bitmap, private val pinStrong: Bitmap) : Overlay() {
    var points: List<GeoPoint> = emptyList()
    var strong: GeoPoint? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        for (p in points) {
            val px = mapView.projection.toPixels(p, null)
            c.drawBitmap(pin, px.x - pin.width / 2f, (px.y - pin.height).toFloat(), paint)
        }
        strong?.let { p ->
            val px = mapView.projection.toPixels(p, null)
            c.drawBitmap(pinStrong, px.x - pinStrong.width / 2f, (px.y - pinStrong.height).toFloat(), paint)
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun OsmLayer(s: LauncherState, ctl: MapController, modifier: Modifier) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    val style = remember(v) { Prefs.str(ctx, Prefs.MAP_STYLE, "dark") }
    val headingUp = remember(v) { Prefs.bool(ctx, Prefs.MAP_HEADING, true) }
    val lite = remember(v) { Prefs.bool(ctx, Prefs.LITE_MAP, false) }
    var lastCam by remember { mutableStateOf(0L) }
    var camAt by remember { mutableStateOf<GeoPoint?>(null) }
    var zoomLvl by remember { mutableStateOf(16.0) }
    val car = remember { CarOverlay(carArrowBitmap(ctx)) }
    val flag = remember { FlagOverlay(flagBitmap(ctx)) }
    val pins = remember { PinsOverlay(pinBitmap(ctx), pinBitmap(ctx, strong = true)) }
    val line = remember {
        Polyline().apply {
            outlinePaint.color = RouteColor
            outlinePaint.strokeWidth = 11f * ctx.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            isGeodesic = false
        }
    }
    val map = remember {
        initOsm(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // sharp tiles: 1:1 (or exactly 2× on high-density screens)
            isTilesScaledToDpi = false
            tilesScaleFactor = if (ctx.resources.displayMetrics.density >= 1.75f) 2f else 1f
            isHorizontalMapRepetitionEnabled = false
            minZoomLevel = 4.0; maxZoomLevel = 19.0
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(55.7539, 37.6208))
            setBackgroundColor(0xFF111315.toInt())
            // light placeholder: the dark-theme colour filter turns it into near-black (#121212) while tiles load
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFFF3F3F3.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFFE9E9E9.toInt()
            // tap on the map → offer a route to that point
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { s.nav.tapOnMap(p.latitude, p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { s.nav.tapOnMap(p.latitude, p.longitude); return true }
            }))
            overlays.add(line); overlays.add(flag); overlays.add(pins); overlays.add(car)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_MOVE || e.pointerCount > 1) ctl.touched()
                false
            }
        }
    }
    // search-result pins + the tapped point
    LaunchedEffect(s.nav.pins, s.nav.selectedPin, s.nav.tapTarget) {
        pins.points = s.nav.pins.map { GeoPoint(it.lat, it.lon) }
        pins.strong = (s.nav.tapTarget ?: s.nav.selectedPin)?.let { GeoPoint(it.lat, it.lon) }
        map.invalidate()
    }
    // when results arrive while searching, frame them
    val searching = s.overlay is com.ravium.teyeslauncher.Overlay.Search
    LaunchedEffect(s.nav.pins, searching) {
        if (searching && s.nav.pins.size >= 1) {
            ctl.following = false
            val gps = s.nav.pins.map { GeoPoint(it.lat, it.lon) } + listOfNotNull(s.vehicle.location?.let { GeoPoint(it.latitude, it.longitude) })
            if (gps.size >= 2) map.post { runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(gps).increaseByScale(1.4f), true, 80) } }
            else map.post { runCatching { map.controller.animateTo(gps.first()); if (map.zoomLevelDouble < 14.0) map.controller.setZoom(15.0) } }
        }
    }
    LaunchedEffect(style) { map.overlayManager.tilesOverlay.setColorFilter(if (style == "light") null else OsmTiles.darkFilter); map.invalidate() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> {} }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); map.onDetach() }
    }
    // a full-screen panel (settings, apps…) covers the map → stop loading tiles until it closes.
    // Поиск — исключение: карта остаётся живой рядом со списком.
    val covered = (s.overlay != null && !searching) || s.page != Page.HOME || s.parked
    LaunchedEffect(covered) {
        if (covered) map.onPause() else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
    }

    // route line
    val route = s.nav.route
    LaunchedEffect(route) {
        line.setPoints(route?.points?.map { GeoPoint(it[0], it[1]) } ?: emptyList())
        flag.point = route?.let { GeoPoint(it.destination.lat, it.destination.lon) }
        if (route != null && route.points.size > 1) {
            map.mapOrientation = 0f
            runCatching { map.setMapCenterOffset(0, 0) }
            val bb = BoundingBox.fromGeoPoints(route.points.map { GeoPoint(it[0], it[1]) })
            map.post { runCatching { map.zoomToBoundingBox(bb.increaseByScale(1.25f), true, 60) } }
        }
        map.invalidate()
    }

    // follow the car
    val fix = s.vehicle.fixVersion
    LaunchedEffect(fix, ctl.following, headingUp) {
        if (!ctl.following) camAt = null   // recenter → jump to the car at once
        val loc = s.vehicle.location ?: return@LaunchedEffect
        val gp = GeoPoint(loc.latitude, loc.longitude)
        car.point = gp
        car.bearing = s.vehicle.bearing
        val now = SystemClock.elapsedRealtime()
        val speed = s.vehicle.speedKmh
        // calm camera: standing / crawling → move only after a real displacement; never faster than once a second
        val moved = camAt?.let { gp.distanceToAsDouble(it) } ?: Double.MAX_VALUE
        val due = now - lastCam > (if (lite) 1500 else 900) && (speed >= 5 || moved > 25)
        if (ctl.following && (due || camAt == null)) {
            lastCam = now; camAt = gp
            // zoom with hysteresis — no pumping in and out around the thresholds
            zoomLvl = when {
                speed > 95 -> 14.0; speed < 80 && zoomLvl == 14.0 -> 15.0
                speed > 50 && zoomLvl == 16.0 -> 15.0; speed < 35 && zoomLvl == 15.0 -> 16.0
                else -> zoomLvl
            }
            if (headingUp && speed >= 8) map.mapOrientation = -s.vehicle.bearing
            else if (!headingUp) map.mapOrientation = 0f
            runCatching { map.setMapCenterOffset(0, if (headingUp) (map.height * 0.22).toInt() else 0) }
            if (lite || speed < 5) { map.controller.setZoom(zoomLvl); map.controller.setCenter(gp) } else map.controller.animateTo(gp, zoomLvl, 850L)
        }
        map.invalidate()
    }

    AndroidView(factory = { map }, modifier = modifier)
}

// ============================ Яндекс Карты engine ============================

@Composable
private fun YandexLayer(s: LauncherState, ctl: MapController, modifier: Modifier) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    val style = remember(v) { Prefs.str(ctx, Prefs.MAP_STYLE, "dark") }
    val headingUp = remember(v) { Prefs.bool(ctx, Prefs.MAP_HEADING, true) }
    val lite = remember(v) { Prefs.bool(ctx, Prefs.LITE_MAP, false) }
    val traffic = remember(v) { Prefs.bool(ctx, Prefs.MAP_TRAFFIC, true) } && !lite
    var lastCam by remember { mutableStateOf(0L) }
    var yCamAt by remember { mutableStateOf<com.yandex.mapkit.geometry.Point?>(null) }
    var yZoom by remember { mutableStateOf(16.5f) }
    var yAzimuth by remember { mutableStateOf(0f) }

    val mv = remember { com.yandex.mapkit.mapview.MapView(ctx) }
    val map = mv.mapWindow.map
    val trafficLayer = remember { com.yandex.mapkit.MapKitFactory.getInstance().createTrafficLayer(mv.mapWindow) }
    val car = remember {
        map.mapObjects.addPlacemark().apply {
            setIcon(com.yandex.runtime.image.ImageProvider.fromBitmap(carArrowBitmap(ctx)),
                com.yandex.mapkit.map.IconStyle().setAnchor(PointF(0.5f, 0.5f)).setFlat(true)
                    .setRotationType(com.yandex.mapkit.map.RotationType.ROTATE).setZIndex(20f))
            isVisible = false
        }
    }
    val flag = remember {
        map.mapObjects.addPlacemark().apply {
            setIcon(com.yandex.runtime.image.ImageProvider.fromBitmap(flagBitmap(ctx)),
                com.yandex.mapkit.map.IconStyle().setAnchor(PointF(0.5f, 0.5f)).setZIndex(15f))
            isVisible = false
        }
    }
    var line by remember { mutableStateOf<com.yandex.mapkit.map.PolylineMapObject?>(null) }
    val pinsCollection = remember { map.mapObjects.addCollection() }
    val pinImage = remember { com.yandex.runtime.image.ImageProvider.fromBitmap(pinBitmap(ctx)) }
    val pinStrongImage = remember { com.yandex.runtime.image.ImageProvider.fromBitmap(pinBitmap(ctx, strong = true)) }
    val pinStyle = remember { com.yandex.mapkit.map.IconStyle().setAnchor(PointF(0.5f, 1f)).setZIndex(16f) }
    // MapKit keeps listeners as weak references — hold a strong one here
    val cameraListener = remember {
        com.yandex.mapkit.map.CameraListener { _, _, reason, _ ->
            if (reason == com.yandex.mapkit.map.CameraUpdateReason.GESTURES) ctl.touched()
        }
    }
    // tap on the map → offer a route to that point
    val inputListener = remember {
        object : com.yandex.mapkit.map.InputListener {
            override fun onMapTap(map: com.yandex.mapkit.map.Map, p: com.yandex.mapkit.geometry.Point) { s.nav.tapOnMap(p.latitude, p.longitude) }
            override fun onMapLongTap(map: com.yandex.mapkit.map.Map, p: com.yandex.mapkit.geometry.Point) { s.nav.tapOnMap(p.latitude, p.longitude) }
        }
    }
    DisposableEffect(Unit) {
        map.addCameraListener(java.lang.ref.WeakReference(cameraListener))
        map.addInputListener(java.lang.ref.WeakReference(inputListener))
        onDispose { map.removeCameraListener(java.lang.ref.WeakReference(cameraListener)); map.removeInputListener(java.lang.ref.WeakReference(inputListener)) }
    }
    val searching = s.overlay is com.ravium.teyeslauncher.Overlay.Search
    // render search-result pins + the tapped point
    LaunchedEffect(s.nav.pins, s.nav.selectedPin, s.nav.tapTarget) {
        pinsCollection.clear()
        s.nav.pins.forEach { pl ->
            runCatching { pinsCollection.addPlacemark().apply {
                geometry = com.yandex.mapkit.geometry.Point(pl.lat, pl.lon); setIcon(pinImage, pinStyle)
            } }
        }
        (s.nav.tapTarget ?: s.nav.selectedPin)?.let { pl ->
            runCatching { pinsCollection.addPlacemark().apply {
                geometry = com.yandex.mapkit.geometry.Point(pl.lat, pl.lon); setIcon(pinStrongImage, pinStyle)
            } }
        }
    }
    // frame the results when they arrive while searching
    LaunchedEffect(s.nav.pins, searching) {
        if (searching && s.nav.pins.isNotEmpty()) {
            ctl.following = false
            val pts = s.nav.pins.map { com.yandex.mapkit.geometry.Point(it.lat, it.lon) } +
                listOfNotNull(s.vehicle.location?.let { com.yandex.mapkit.geometry.Point(it.latitude, it.longitude) })
            runCatching {
                if (pts.size >= 2) {
                    val pos = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromPolyline(com.yandex.mapkit.geometry.Polyline(pts)))
                    map.move(com.yandex.mapkit.map.CameraPosition(pos.target, (pos.zoom - 0.6f).coerceAtMost(16f), 0f, 0f),
                        com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, 0.6f), null)
                } else map.move(com.yandex.mapkit.map.CameraPosition(pts.first(), 15.5f, 0f, 0f),
                    com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, 0.6f), null)
            }
        }
    }
    LaunchedEffect(style, traffic) {
        map.isNightModeEnabled = style != "light"
        trafficLayer.isTrafficVisible = traffic
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) { Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_STOP -> mv.onStop(); else -> {} }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mv.onStart()
        onDispose { owner.lifecycle.removeObserver(obs); mv.onStop() }
    }
    // a full-screen panel covers the map → stop rendering it (saves CPU/GPU while in settings or the app list).
    // Поиск — исключение: карта остаётся рядом со списком.
    val covered = (s.overlay != null && !searching) || s.page != Page.HOME || s.parked
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        if (covered && !paused) { mv.onStop(); paused = true }
        else if (!covered && paused) { paused = false; if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mv.onStart() }
    }

    // route
    val route = s.nav.route
    LaunchedEffect(route) {
        line?.let { runCatching { map.mapObjects.remove(it) } }
        line = null
        if (route != null && route.points.size > 1) {
            val pl = com.yandex.mapkit.geometry.Polyline(route.points.map { com.yandex.mapkit.geometry.Point(it[0], it[1]) })
            line = map.mapObjects.addPolyline(pl).apply {
                setStrokeColor(RouteColor)
                strokeWidth = 7f
                outlineColor = 0xFF0B2A55.toInt()
                outlineWidth = 1.5f
                zIndex = 10f
            }
            flag.geometry = com.yandex.mapkit.geometry.Point(route.destination.lat, route.destination.lon)
            flag.isVisible = true
            runCatching {
                val pos = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromPolyline(pl))
                map.move(com.yandex.mapkit.map.CameraPosition(pos.target, pos.zoom - 0.6f, 0f, 0f),
                    com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, 0.8f), null)
            }
        } else flag.isVisible = false
    }

    // follow
    val fix = s.vehicle.fixVersion
    LaunchedEffect(fix, ctl.following, headingUp) {
        if (!ctl.following) yCamAt = null
        val loc = s.vehicle.location ?: return@LaunchedEffect
        val p = com.yandex.mapkit.geometry.Point(loc.latitude, loc.longitude)
        car.geometry = p
        car.direction = s.vehicle.bearing
        car.isVisible = true
        val now = SystemClock.elapsedRealtime()
        val speed = s.vehicle.speedKmh
        val moved = yCamAt?.let { a -> FloatArray(1).also { r -> android.location.Location.distanceBetween(a.latitude, a.longitude, p.latitude, p.longitude, r) }[0] } ?: Float.MAX_VALUE
        val due = now - lastCam > (if (lite) 1500 else 900) && (speed >= 5 || moved > 25f)
        if (ctl.following && (due || yCamAt == null)) {
            lastCam = now; yCamAt = p
            yZoom = when {
                speed > 95 -> 14.5f; speed < 80 && yZoom == 14.5f -> 15.5f
                speed > 50 && yZoom == 16.5f -> 15.5f; speed < 35 && yZoom == 15.5f -> 16.5f
                else -> yZoom
            }
            val zoom = yZoom
            if (speed >= 8) yAzimuth = s.vehicle.bearing
            runCatching {
                val w = mv.mapWindow.width(); val h = mv.mapWindow.height()
                if (w > 0 && h > 0) mv.mapWindow.focusPoint = com.yandex.mapkit.ScreenPoint(w / 2f, if (headingUp) h * 0.7f else h / 2f)
            }
            val tilt = if (headingUp && !lite) 35f else 0f   // 3D tilt costs GPU — off in the light mode
            map.move(com.yandex.mapkit.map.CameraPosition(p, zoom, if (headingUp) yAzimuth else 0f, tilt),
                com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, if (lite) 0.4f else 0.8f), null)
        }
    }

    AndroidView(factory = { mv }, modifier = modifier)
}
