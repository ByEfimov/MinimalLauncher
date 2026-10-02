package com.ravium.teyeslauncher.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ravium.teyeslauncher.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private fun ts(size: Float, color: Color = C.Text, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, color = color, fontWeight = weight, letterSpacing = spacing.sp, lineHeight = (size * 1.2f).sp)

@Composable
fun LauncherRoot(s: LauncherState) {
    val ctx = LocalContext.current
    // Background housekeeping. No Compose state is read here, so the root never recomposes because of it.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            s.vehicle.tick()
            if (n % 5 == 0) s.status.refresh()
            if (n % 30 == 0) s.weather.maybeUpdate(s.vehicle.location)
            if (n % 60 == 0) s.updateNight()
            if (n % 1800 == 900) s.updater.checkDaily()   // new release? (checks at most every 6 h)
            if (n % 60 == 20) Optimizer.maybeAutoClean(ctx, s.media.sessionPackages())   // keeps the head unit from bogging down
            // parking screen: standing still long enough and no route
            val still = SystemClock.elapsedRealtime() - s.vehicle.lastMovingAt > (Prefs.str(ctx, Prefs.PARKING_DELAY, "2").toLongOrNull() ?: 2L) * 60_000L
            if (!still) s.parkDismissed = false
            val park = Prefs.bool(ctx, Prefs.PARKING, true) && still && !s.parkDismissed && s.nav.route == null && HomeLayout.bigMap(ctx)
            if (park != s.parked) s.parked = park
            n++
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Bg, C.BgBottom)))) {
        Column(Modifier.fillMaxSize().padding(start = 33.dp, end = 34.dp)) {
            Header(s)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(540.dp)) {
                // Home stays composed underneath (the map is expensive to recreate); other pages slide over it.
                // Layout is configurable: Настройки → Экран → Главный экран
                val v = s.settingsVersion
                val blocks = remember(v) { HomeLayout.blocks(ctx) }
                val left = remember(v) { HomeLayout.sideLeft(ctx) }
                val bigMap = remember(v) { HomeLayout.bigMap(ctx) }
                val sideW = if (remember(v) { HomeLayout.wide(ctx) }) 560.dp else 442.dp
                Row(Modifier.fillMaxSize()) {
                    val side: @Composable () -> Unit = { SideColumn(s, blocks, Modifier.width(sideW).fillMaxHeight()) }
                    if (left) { side(); Spacer(Modifier.width(14.dp)) }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (!bigMap) ParkingPanel(s, Modifier.fillMaxSize(), standalone = true)
                        else {
                            MapCard(s, Modifier.fillMaxSize())
                            if (InstantUi && s.parked && s.page == Page.HOME) ParkingPanel(s, Modifier.fillMaxSize())
                            else androidx.compose.animation.AnimatedVisibility(s.parked && s.page == Page.HOME, enter = androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.fadeOut()) { ParkingPanel(s, Modifier.fillMaxSize()) }
                        }
                    }
                    if (!left) { Spacer(Modifier.width(14.dp)); side() }
                }
                if (InstantUi) { if (s.page != Page.HOME) Box(Modifier.fillMaxSize().background(C.Bg)) {
                    when (s.page) { Page.TILES -> TilesPage(s); Page.REMINDERS -> RemindersPage(s); Page.OPTIMIZE -> OptimizePage(s); Page.HOME -> {} } } }
                else androidx.compose.animation.AnimatedContent(targetState = s.page, label = "page", transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    val slideIn = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 420f)
                    val fade = androidx.compose.animation.core.tween<Float>(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    (androidx.compose.animation.slideInHorizontally(slideIn) { it * dir / 7 } + androidx.compose.animation.fadeIn(fade)) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(slideIn) { -it * dir / 7 } + androidx.compose.animation.fadeOut(fade)) using
                        androidx.compose.animation.SizeTransform(clip = false)
                }) { p ->
                    if (p != Page.HOME) Box(Modifier.fillMaxSize().background(C.Bg)) {
                        when (p) {
                            Page.TILES -> TilesPage(s)
                            Page.REMINDERS -> RemindersPage(s)
                            Page.OPTIMIZE -> OptimizePage(s)
                            Page.HOME -> {}
                        }
                    } else Box(Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(21.dp))
            Box(Modifier.fillMaxWidth().padding(start = 17.dp, end = 16.dp).height(Hairline).background(Color(0xFF1C1F21)))
            BottomBar(s, Modifier.fillMaxWidth().weight(1f))
        }
        // Night mode: soft dimming layer (does not intercept touches)
        if (s.night) {
            val dim = remember(s.settingsVersion) { Prefs.str(ctx, Prefs.NIGHT_DIM, "0.3").toFloatOrNull() ?: 0.3f }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
        if (s.volumePanel && s.overlay == null) VolumePopover(s)
        Overlays(s)
    }
}

/** Всплывающая плашка громкости под иконкой справа сверху: ползунок + «без звука». */
@Composable
private fun VolumePopover(s: LauncherState) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var frac by remember { mutableFloatStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max) }
    fun apply(f: Float) {
        val target = (f * max).roundToInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0) }
        var c = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); var guard = 0
        while (c != target && guard++ <= max + 1) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (c < target) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
            val n = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); if (n == c) break; c = n
        }
    }
    // клик вне плашки — закрыть
    val (alpha, scale, dy) = rememberAppear(rise = 10f)
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { s.volumePanel = false }) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = 112.dp, end = 30.dp)
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; translationY = dy; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.9f, 0f) }
            .width(360.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF21A1D20)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Громкость", style = ts(18f, C.Text, FontWeight.Medium))
                    Spacer(Modifier.weight(1f))
                    Text("${(frac * 100).roundToInt()}%", style = ts(18f, C.Text2, FontWeight.Medium))
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { frac = 0f; apply(0f) }, contentAlignment = Alignment.Center) {
                        Icon(if (frac <= 0f) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeMute, "Без звука", tint = C.Text2, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    // горизонтальный ползунок во всю ширину
                    Box(Modifier.weight(1f).height(44.dp).pointerInput(Unit) {
                        detectTapGestures { o -> frac = (o.x / size.width).coerceIn(0f, 1f); apply(frac) }
                    }.pointerInput(Unit) {
                        detectHorizontalDragGestures { ch, amt -> ch.consume(); frac = (frac + amt / size.width).coerceIn(0f, 1f); apply(frac) }
                    }) {
                        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2E33)))
                        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac.coerceIn(0.001f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { frac = 1f; apply(1f) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VolumeUp, "Громче", tint = C.Text2, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/** Side column of the main screen: music stretches, the other blocks are 97 units tall (or share the height when there's no music). */
@Composable
private fun SideColumn(s: LauncherState, blocks: List<String>, modifier: Modifier) {
    // выбрали точку на карте — показываем её карточку вместо плеера/панели (данные из Яндекса/OSM)
    val tap = s.nav.tapTarget
    if (tap != null && s.overlay == null && s.page == Page.HOME) { PlaceCard(s, tap, modifier); return }
    val hasMusic = "music" in blocks
    Column(modifier, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        blocks.forEach { id ->
            val m = if (id == "music" || (!hasMusic && id != "service")) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(97.dp)
            when {
                id == "music" -> MusicCard(s, m)
                id == "service" -> ServiceBar(s, m)
                id.startsWith("tile=app:") -> HomeTile(s, "app", id.removePrefix("tile=app:"), m)
                id.startsWith("tile=") -> HomeTile(s, id.removePrefix("tile="), null, m)
            }
        }
        if (blocks.all { it == "service" }) Spacer(Modifier.weight(1f))
    }
}

/** Карточка выбранной на карте точки (слева, вместо плеера): название, адрес из Яндекса, маршрут. */
@Composable
private fun PlaceCard(s: LauncherState, p: Place, modifier: Modifier) {
    val dist = s.vehicle.location?.let {
        val r = FloatArray(1); android.location.Location.distanceBetween(it.latitude, it.longitude, p.lat, p.lon, r)
        if (r[0] >= 1000) "%.1f км".format(r[0] / 1000) else "${r[0].toInt()} м"
    }
    val fav = s.nav.isFavorite(p)
    val (alpha, scale, dy) = rememberAppear(key = p.lat to p.lon, rise = 18f)
    Column(modifier.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; translationY = dy }
        .clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FF5B6B)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Place, null, tint = Color(0xFFFF6B75), modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).background(C.Card).border(Hairline, C.Stroke, CircleShape)
                .clickable { s.nav.clearPins() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Закрыть", tint = C.Text, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(p.name, style = ts(26f, C.Text, FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        // время в пути (как в карточке Яндекса); пока считается — показываем расстояние по прямой
        val eta = s.nav.placeEta
        if (eta != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Navigation, null, tint = C.Yellow, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(eta, style = ts(19f, C.Text, FontWeight.Medium))
        } else LoaderRow(dist?.let { "$it · считаю время…" } ?: "Считаю время в пути…", textColor = C.Text2)
        if (s.nav.placeOrgs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Здесь находятся", style = ts(14f, C.Muted))
            Spacer(Modifier.height(6.dp))
            s.nav.placeOrgs.take(5).forEach { org ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(C.Text2))
                    Spacer(Modifier.width(12.dp))
                    Text(org, style = ts(17f, C.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (p.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(p.description, style = ts(15f, C.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(60.dp).clip(CardShape).background(C.YellowBg).border(1.5.dp, C.YellowBorder, CardShape)
                .clickable {
                    s.nav.rememberRecent(p); val t = p; s.nav.tapTarget = null; s.nav.clearPins()
                    s.nav.routeTo(t, s.vehicle.location)
                }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, null, tint = C.Text, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp)); Text("Маршрут сюда", style = ts(18f, C.Text, FontWeight.SemiBold))
                }
            }
            Box(Modifier.size(60.dp).clip(CardShape).background(C.Card).border(Hairline, C.Stroke, CardShape)
                .clickable { s.nav.toggleFavorite(p) }, contentAlignment = Alignment.Center) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, "В избранное",
                    tint = if (fav) C.Yellow else C.Text2, modifier = Modifier.size(26.dp))
            }
        }
    }
}

// ============================ HEADER ============================

@SuppressLint("DiscouragedApi")
@Composable
private fun Header(s: LauncherState) {
    val ctx = LocalContext.current
    val logoRes = remember { ctx.resources.getIdentifier("brand_logo", "drawable", ctx.packageName) }
    Row(Modifier.fillMaxWidth().height(122.dp), verticalAlignment = Alignment.CenterVertically) {
        // Left block sits above the music column
        var dragX by remember { mutableFloatStateOf(0f) }
        Row(Modifier.width(456.dp).fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { dragX = 0f }, onDragEnd = {
                    val th = 60.dp.toPx()
                    if (dragX < -th) s.turnPage(1) else if (dragX > th) s.turnPage(-1)
                    dragX = 0f
                }, onDragCancel = { dragX = 0f }) { ch, amount -> ch.consume(); dragX += amount }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { s.turnPage(1) },
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(35.dp))
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                // Put your logo PNG at res/drawable-nodpi/brand_logo.png — it appears here automatically.
                Image(painterResource(if (logoRes != 0) logoRes else com.ravium.teyeslauncher.R.drawable.ic_logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(24.dp))
            // current screen — swipe here (or tap) to switch
            Text(s.page.title, style = ts(19f, C.Text, FontWeight.SemiBold), maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Page.entries.forEach { p ->
                    Box(Modifier.padding(horizontal = 2.5.dp).size(if (p == s.page) 7.dp else 5.dp).clip(CircleShape).background(if (p == s.page) C.Text else C.Muted.copy(alpha = 0.6f)))
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.Swipe, "Свайп — другой экран", tint = C.Muted, modifier = Modifier.size(22.dp).graphicsLayer { translationX = (dragX * 0.15f).coerceIn(-12f, 12f) })
            Spacer(Modifier.width(12.dp))
        }
        // Right block sits above the map card
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(3.dp))
            Clock()
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 44.dp)) {
                val limit = s.vehicle.limits.limit
                val tolerance = remember(s.settingsVersion) { Prefs.str(ctx, Prefs.LIMIT_TOLERANCE, "10").toIntOrNull() ?: 10 }
                val over = limit != null && s.vehicle.speedKmh > limit + tolerance
                Text("${s.vehicle.speedKmh}", style = ts(44f, if (over) C.SignRed else C.Text, FontWeight.Normal).copy(lineHeight = 46.sp))
                Text("км/ч", style = ts(13f, C.Muted).copy(lineHeight = 14.sp))
            }
            Spacer(Modifier.width(29.dp))
            Box(Modifier.width(Hairline).height(62.dp).background(Color(0xFF3A3D40)))
            Spacer(Modifier.width(19.dp))
            SpeedSign(if (Prefs.bool(ctx, Prefs.LIMITS, true)) s.vehicle.limits.limit else null)
            Spacer(Modifier.weight(0.47f))
            Box(Modifier.width(Hairline).height(62.dp).background(Color(0xFF222528)))
            Spacer(Modifier.width(44.dp))
            Icon(Icons.Filled.Bluetooth, null, tint = if (s.status.bluetoothOn) C.Text else C.Muted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(22.dp))
            SignalBars(s.status.online)
            Spacer(Modifier.width(26.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { s.volumePanel = !s.volumePanel }, contentAlignment = Alignment.Center) {
                val muted = remember(s.settingsVersion, s.volumePanel) {
                    val am = ctx.getSystemService(android.media.AudioManager::class.java)
                    am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
                }
                Icon(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, "Громкость",
                    tint = if (s.volumePanel) C.Yellow else C.Text, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(14.dp))
            Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { s.overlay = Overlay.Weather }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(s.weather.tempC?.let { "$it°" } ?: "--°", style = ts(20f, C.Text))
                Spacer(Modifier.width(14.dp))
                Icon(weatherIcon(s.weather.code), null, tint = C.Text, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** Own recomposition scope: only the clock redraws every second, not the whole screen. */
@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000L - System.currentTimeMillis() % 1000L) } }
    val time = remember(now.time / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now.time / 3_600_000) { SimpleDateFormat("EE, d MMM", Locale("ru")).format(now).replaceFirstChar { it.titlecase(Locale("ru")) } }
    Text(time, style = ts(56f, C.Text, FontWeight.Light, -1f))
    Spacer(Modifier.width(17.dp))
    Text(date, style = ts(15f, C.Muted), modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun SpeedSign(limit: Int?) {
    // Unknown limit → dimmed sign with a dash.
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (limit != null) Color.White else Color(0xFF2A2D30))
            .border(6.dp, if (limit != null) C.SignRed else Color(0xFF4A2226), CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(limit?.toString() ?: "–", style = ts(if (limit != null && limit >= 100) 15f else 18f, if (limit != null) Color(0xFF111111) else C.Muted, FontWeight.SemiBold, -0.5f)) }
}

@Composable
private fun SignalBars(on: Boolean) = Canvas(Modifier.size(24.dp, 20.dp)) {
    val w = size.width; val h = size.height
    val bw = w * 0.16f; val gap = (w - bw * 4) / 3
    for (i in 0 until 4) {
        val bh = h * (0.3f + 0.233f * i)
        val x = i * (bw + gap)
        drawRoundRect(if (on) Color.White else Color(0xFF55595C), Offset(x, h - bh), androidx.compose.ui.geometry.Size(bw, bh),
            androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
    }
}

private fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Outlined.WbSunny
    2, 3, 45, 48 -> Icons.Outlined.Cloud
    in 51..67, in 80..82 -> Icons.Outlined.WaterDrop
    in 71..77, 85, 86 -> Icons.Outlined.AcUnit
    in 95..99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}

// ============================ MUSIC ============================

@Composable
private fun MusicCard(s: LauncherState, modifier: Modifier) {
    val ctx = LocalContext.current
    val np = s.media.nowPlaying()
    val noAccess = !s.media.hasAccess
    val openAccess = {
        if (!Permissions.openNotifications(ctx)) s.overlay = Overlay.Setup
        else Apps.toast(ctx, "Включите доступ для «Minimal Drive»")
    }

    // Swipe left/right anywhere on the card → next/previous track.
    var dragX by remember { mutableFloatStateOf(0f) }
    val swipe = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { dragX = 0f },
            onDragEnd = {
                val th = 80.dp.toPx()
                if (dragX < -th) s.media.next() else if (dragX > th) s.media.previous()
                dragX = 0f
            },
            onDragCancel = { dragX = 0f },
        ) { change, amount -> change.consume(); dragX += amount }
    }
    val tapCover = { if (noAccess) openAccess() else s.media.playPause() }
    Box(modifier.clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape).then(swipe)) {
        Column(Modifier.fillMaxSize().graphicsLayer { translationX = dragX * 0.25f }) {
            // Cover + title (tap = play/pause)
            Row(Modifier.padding(start = 28.dp, top = 25.dp, end = 60.dp)) {
                Box(
                    Modifier.size(153.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0B0C))
                        .clickable { tapCover() },
                    contentAlignment = Alignment.Center
                ) {
                    val art = rememberArt(np)
                    if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
                    else if (s.media.source == Source.BLUETOOTH) Icon(Icons.Filled.Bluetooth, null, tint = C.Muted, modifier = Modifier.size(56.dp))
                    else MusicTileFallback(64.dp, C.Yellow)
                }
                Spacer(Modifier.width(17.dp))
                Column(Modifier.padding(top = 36.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tapCover() }) {
                    Text(np.title, style = ts(26f, C.Text, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Text(np.artist, style = ts(22f, C.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(5.dp))
            // Progress (tap to seek) — ticks on its own, the rest of the card stays still
            MusicProgress(s)
            Spacer(Modifier.height(10.dp))
            // Transport
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TapBox(56.dp, { s.media.previous() }) { SkipGlyph(next = false, size = 26.dp) }
                Spacer(Modifier.width(58.dp))
                Box(
                    Modifier.size(70.dp).clip(RoundedCornerShape(22.dp)).border(2.dp, Color.White, RoundedCornerShape(22.dp))
                        .clickable { if (noAccess) openAccess() else s.media.playPause() },
                    contentAlignment = Alignment.Center
                ) { if (np.playing) PauseGlyph(26.dp) else PlayGlyph(26.dp) }
                Spacer(Modifier.width(58.dp))
                TapBox(56.dp, { s.media.next() }) { SkipGlyph(next = true, size = 26.dp) }
            }
            Spacer(Modifier.weight(1f))
            // Sources
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 21.dp).fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceButton(
                    label = s.media.mainLabel, selected = s.media.source == Source.YANDEX, modifier = Modifier.weight(214f),
                    icon = { sel ->
                        val ic = remember(s.media.mainPkg) { Apps.icon(ctx, s.media.mainPkg) }
                        if (ic != null) Image(ic, null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)), filterQuality = FilterQuality.High)
                        else MusicTileFallback(34.dp, if (sel) C.Yellow else C.Muted)
                    },
                    onClick = { s.media.select(Source.YANDEX) },
                    onLongClick = { s.media.openSourceApp(Source.YANDEX) },
                )
                SourceButton(
                    label = "Bluetooth", selected = s.media.source == Source.BLUETOOTH, modifier = Modifier.weight(176f),
                    icon = { sel -> Icon(Icons.Filled.Bluetooth, null, tint = if (sel) C.Text else C.Muted, modifier = Modifier.size(28.dp)) },
                    onClick = { s.media.select(Source.BLUETOOTH) },
                    onLongClick = { s.media.openSourceApp(Source.BLUETOOTH) },
                )
            }
        }
        // Heart
        Box(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 17.dp).size(48.dp).clip(CircleShape).clickable { s.media.toggleLike() },
            contentAlignment = Alignment.Center) {
            Icon(if (np.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null,
                tint = if (np.liked) Color(0xFFFF4D5A) else C.Text2, modifier = Modifier.size(27.dp))
        }
    }
}

/** Album art: the high-resolution picture from the player's art URI when available, else the bundled bitmap. */
@Composable
private fun rememberArt(np: NowPlaying): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = LocalContext.current
    val small = remember(np.art) { np.art?.asImageBitmap() }
    var big by remember(np.artUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(ArtCache.get(np.artUri)) }
    LaunchedEffect(np.artUri) {
        val uri = np.artUri ?: return@LaunchedEffect
        if (big == null) big = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ArtCache.load(ctx, uri) }
    }
    // use the URI picture only if it is sharper than the embedded one
    val b = big
    return if (b != null && (small == null || b.width > small.width)) b else small
}

@Composable
private fun MusicProgress(s: LauncherState) {
    var tick by remember { mutableLongStateOf(0L) }
    val np = s.media.nowPlaying()
    LaunchedEffect(np.playing) { while (np.playing) { tick = SystemClock.elapsedRealtime(); delay(500) } }
    tick
    val cur = if (np.playing) s.media.nowPlaying() else np
    val progress = if (cur.durationMs > 0) cur.positionMs.toFloat() / cur.durationMs else 0f
    Canvas(
        Modifier.padding(horizontal = 29.dp).fillMaxWidth().height(20.dp)
            .pointerInput(Unit) { detectTapGestures { o -> s.media.seekTo(o.x / size.width) } }
    ) {
        val y = size.height / 2; val sw = 4.dp.toPx()
        drawLine(C.Track, Offset(sw / 2, y), Offset(size.width - sw / 2, y), sw, StrokeCap.Round)
        if (progress > 0f) drawLine(Color.White, Offset(sw / 2, y), Offset(sw / 2 + (size.width - sw) * progress, y), sw, StrokeCap.Round)
    }
    Row(Modifier.padding(horizontal = 29.dp).fillMaxWidth().padding(top = 2.dp)) {
        Text(fmt(cur.positionMs), style = ts(16f, C.Text2))
        Spacer(Modifier.weight(1f))
        Text(if (cur.durationMs > 0) fmt(cur.durationMs) else "0:00", style = ts(16f, C.Text2))
    }
}

private fun fmt(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(t / 60, t % 60) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceButton(
    label: String, selected: Boolean, modifier: Modifier,
    icon: @Composable (Boolean) -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (selected) Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(Color(0xFF16181A), C.Yellow, 0.10f), C.YellowBg)) else Brush.verticalGradient(listOf(Color(0xFF16181A), Color(0xFF131517)))
    Row(
        modifier.fillMaxHeight().clip(shape).background(bg).border(1.5.dp, if (selected) C.YellowBorder else C.Stroke, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 22.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon(selected) }
        Spacer(Modifier.width(if (selected) 20.dp else 16.dp))
        Text(label, style = ts(16f, if (selected) C.Text else C.Muted, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TapBox(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }

// ============================ SERVICE BAR ============================

@Composable
private fun ServiceBar(s: LauncherState, modifier: Modifier) {
    val ctx = LocalContext.current
    Row(modifier.clip(CardShape).background(CardBrush).border(Hairline, C.Stroke, CardShape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(97.dp).fillMaxHeight().clickable { s.overlay = Overlay.Drawer }, contentAlignment = Alignment.Center) { FourDotsIcon(28.dp) }
        VDivider()
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable { s.launchAssigned(Prefs.CARLINK, Known.CARLINK, "Выберите приложение CarPlay / CarLink") },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            CarPlayTile(55.dp)
            Spacer(Modifier.width(20.dp))
            Text("CarPlay", style = ts(16f, C.Text, FontWeight.Medium))
        }
        VDivider()
        Box(Modifier.width(98.dp).fillMaxHeight().clickable { s.overlay = Overlay.Settings }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Settings, null, tint = C.Text, modifier = Modifier.size(30.dp))
            // update available / setup incomplete → small dot
            if (s.updater.available != null) Box(Modifier.offset(x = 14.dp, y = (-14).dp).size(10.dp).clip(CircleShape).background(C.Yellow))
        }
    }
}

@Composable private fun VDivider() = Box(Modifier.width(Hairline).height(62.dp).background(C.Divider))

// ============================ CAMERA ============================

@Composable
fun NavHintChip(h: NavInfo.Hint, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xD9101315)).border(Hairline, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ic = h.icon
        if (ic != null) Image(remember(ic) { ic.asImageBitmap() }, null, Modifier.size(44.dp), filterQuality = FilterQuality.High)
        else Icon(Icons.Outlined.NearMe, null, tint = Color.White, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(h.title, style = ts(20f, C.Text, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = listOf(h.text, h.sub).filter { it.isNotBlank() }.joinToString(" · ")
            if (second.isNotEmpty()) Text(second, style = ts(15f, C.Text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CameraChip(c: SpeedLimit.CameraAhead) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6361214)).border(Hairline, C.SignRed.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("Камера через ${c.distanceM} м" + (c.limit?.let { " · $it" } ?: ""), style = ts(18f, C.Text, FontWeight.Medium))
    }
}

// ============================ BOTTOM BAR ============================

@Composable
private fun BottomBar(s: LauncherState, modifier: Modifier) {
    val ctx = LocalContext.current
    val items = remember(s.settingsVersion) { Dock.load(ctx).filter { !it.startsWith("app=") || Apps.installed(ctx, it.removePrefix("app=")) } }
    Row(modifier.padding(start = 23.dp, end = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, id ->
            if (i > 0) NavDivider()
            val sel = Dock.selected(s, id)
            // long press on any button → configure the bar
            NavItem(sel, { Dock.open(s, id) }, { s.overlay = Overlay.Dock }) {
                DockGlyph(id, if (id == "home") 32 else 30, if (sel) Color.White else C.Text2)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.NavItem(selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && !InstantUi) 0.82f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium), label = "nav")
    Box(
        Modifier.weight(1f).fillMaxHeight().combinedClickable(interactionSource = interaction, indication = null,
            onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { icon() }
        if (selected) Box(Modifier.offset(y = 25.dp).width(45.dp).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}

@Composable private fun NavDivider() = Box(Modifier.width(Hairline).height(28.dp).background(Color(0xFF26292C)))
