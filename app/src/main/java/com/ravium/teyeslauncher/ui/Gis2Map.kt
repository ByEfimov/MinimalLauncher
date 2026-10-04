package com.ravium.teyeslauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ravium.teyeslauncher.*

/**
 * 2ГИС как настоящая векторная карта (MapGL в WebView): родные пробки, маршруты строит сам 2ГИС
 * (плагин Directions) и рисует своей полоской, свой стиль. Нужен бесплатный ключ 2ГИС (MapGL);
 * без ключа используется растровый OsmLayer(provider="2gis").
 */
object Gis2 {
    fun key(ctx: Context): String =
        Prefs.str(ctx, Prefs.GIS_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.GIS_MAPKIT_KEY
    fun enabled(ctx: Context): Boolean = key(ctx).isNotBlank()
}

private class Gis2Bridge(
    private val s: LauncherState,
    private val ctl: MapController,
    private val ready: MutableState<Boolean>,
    private val err: MutableState<String?>,
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onReady() = main.post { err.value = null; ready.value = true }
    @JavascriptInterface fun onError(m: String) = main.post { err.value = m }
    @JavascriptInterface fun onGesture() = main.post { ctl.touched() }
    @JavascriptInterface fun onTap(lat: Double, lon: Double) = main.post { s.nav.tapOnMap(lat, lon, s.vehicle.location) }
}

private fun gisZoom(cur: Float, speed: Int): Float = when {
    speed > 95 -> 14.5f; speed < 80 && cur == 14.5f -> 15.5f
    speed > 50 && cur == 16.5f -> 15.5f; speed < 35 && cur == 15.5f -> 16.5f
    else -> cur
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Gis2Layer(s: LauncherState, ctl: MapController, modifier: Modifier) {
    val ctx = LocalContext.current
    val v = s.settingsVersion
    val headingUp = remember(v) { Prefs.bool(ctx, Prefs.MAP_HEADING, true) }
    val lite = remember(v) { Prefs.bool(ctx, Prefs.LITE_MAP, false) }
    val style = remember(v) { Prefs.str(ctx, Prefs.MAP_STYLE, "dark") }
    val traffic = remember(v) { Prefs.bool(ctx, Prefs.MAP_TRAFFIC, true) } && !lite

    val ready = remember { mutableStateOf(false) }
    val err = remember { mutableStateOf<String?>(null) }
    val bridge = remember { Gis2Bridge(s, ctl, ready, err) }

    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION") run { settings.databaseEnabled = true }
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setGeolocationEnabled(false)
            setBackgroundColor(0xFF111315.toInt())
            addJavascriptInterface(bridge, "AndroidGis")
            val plugin = runCatching { context.assets.open("mapgl-directions.js").readBytes().decodeToString() }.getOrDefault("")
            var html = runCatching { context.assets.open("gis2.html").readBytes().decodeToString() }.getOrDefault("")
            html = html.replace("__PLUGIN__", plugin)
                .replace("__KEY__", Gis2.key(ctx))
                .replace("__DIR__", Gis2.key(ctx))
                .replace("__DARK__", if (style == "light") "false" else "true")
                .replace("__TRAFFIC__", if (traffic) "true" else "false")
            loadDataWithBaseURL("https://mapgl.2gis.com/", html, "text/html", "utf-8", null)
        }
    }
    fun js(code: String) = web.post { runCatching { web.evaluateJavascript(code, null) } }

    // follow the car (marker всегда обновляем; камеру двигаем только когда «следуем» и пора)
    var zoom by remember { mutableStateOf(16.5f) }
    var az by remember { mutableStateOf(0f) }
    var lastCam by remember { mutableStateOf(0L) }
    var camLat by remember { mutableStateOf<Double?>(null) }
    var camLon by remember { mutableStateOf(0.0) }
    val fix = s.vehicle.fixVersion
    LaunchedEffect(ready.value, fix, ctl.following, headingUp) {
        if (!ready.value) return@LaunchedEffect
        val loc = s.vehicle.location ?: return@LaunchedEffect
        val speed = s.vehicle.speedKmh
        if (speed >= 8) az = s.vehicle.bearing
        val now = SystemClock.elapsedRealtime()
        val moved = camLat?.let { la ->
            FloatArray(1).also { android.location.Location.distanceBetween(la, camLon, loc.latitude, loc.longitude, it) }[0].toDouble()
        } ?: Double.MAX_VALUE
        val follow = ctl.following
        val due = follow && (camLat == null || (now - lastCam > (if (lite) 1500 else 900) && (speed >= 5 || moved > 25)))
        if (due) { lastCam = now; camLat = loc.latitude; camLon = loc.longitude; zoom = gisZoom(zoom, speed) }
        js("window.gisSetCar(${loc.latitude},${loc.longitude},$az,$due,$zoom,$headingUp)")
    }

    // маршрут строит сам 2ГИС: от машины к точке назначения (своя полоска + учёт пробок)
    val route = s.nav.route
    LaunchedEffect(ready.value, route?.destination?.lat, route?.destination?.lon) {
        if (!ready.value) return@LaunchedEffect
        val r = route
        val from = s.vehicle.location
        if (r != null && from != null) js("window.gisSetRoute(${from.latitude},${from.longitude},${r.destination.lat},${r.destination.lon})")
        else if (r == null) js("window.gisClearRoute()")
    }

    // метки результатов поиска + выбранная точка
    LaunchedEffect(ready.value, s.nav.pins, s.nav.selectedPin, s.nav.tapTarget) {
        if (!ready.value) return@LaunchedEffect
        val strong = s.nav.tapTarget ?: s.nav.selectedPin
        val sb = StringBuilder("[")
        s.nav.pins.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"lat\":${p.lat},\"lon\":${p.lon},\"strong\":false}")
        }
        strong?.let {
            if (s.nav.pins.isNotEmpty()) sb.append(',')
            sb.append("{\"lat\":${it.lat},\"lon\":${it.lon},\"strong\":true}")
        }
        sb.append("]")
        js("window.gisSetPins('${sb}')")
    }
    // выбрали точку (тап/адрес) — показать её
    LaunchedEffect(ready.value, s.nav.tapTarget?.lat, s.nav.tapTarget?.lon) {
        val t = s.nav.tapTarget ?: return@LaunchedEffect
        if (!ready.value) return@LaunchedEffect
        ctl.following = false
        js("window.gisCenter(${t.lat},${t.lon},16)")
    }
    // кадрируем результаты поиска
    val searching = s.overlay is com.ravium.teyeslauncher.Overlay.Search
    LaunchedEffect(ready.value, s.nav.pins, searching) {
        if (!ready.value || !searching || s.nav.pins.isEmpty()) return@LaunchedEffect
        ctl.following = false
        val first = s.nav.pins.first()
        js("window.gisCenter(${first.lat},${first.lon},15)")
    }

    LaunchedEffect(ready.value, traffic) { if (ready.value) js("window.gisSetTraffic($traffic)") }
    LaunchedEffect(ready.value, style) { if (ready.value) js("window.gisSetStyle(${style != "light"})") }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) { Lifecycle.Event.ON_RESUME -> web.onResume(); Lifecycle.Event.ON_PAUSE -> web.onPause(); else -> {} }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); runCatching { web.destroy() } }
    }
    val covered = (s.overlay != null && !searching) || s.page != Page.HOME || s.parked
    LaunchedEffect(covered) { if (covered) web.onPause() else web.onResume() }

    Box(modifier) {
        AndroidView(factory = { web }, modifier = Modifier.matchParentSize())
        err.value?.let { m ->
            Text("2ГИС не загрузился: $m\nПроверьте ключ 2ГИС и связь (нужен доступ к 2gis.com)",
                style = TextStyle(fontSize = 13.sp, color = C.Text2),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp))
        }
    }
}
