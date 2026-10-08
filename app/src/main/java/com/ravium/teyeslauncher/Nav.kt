package com.ravium.teyeslauncher

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class Place(val name: String, val description: String, val lat: Double, val lon: Double) {
    fun toJson() = JSONObject().put("n", name).put("d", description).put("la", lat).put("lo", lon).toString()
    companion object {
        fun fromJson(s: String?): Place? = runCatching {
            val j = JSONObject(s!!); Place(j.getString("n"), j.optString("d"), j.getDouble("la"), j.getDouble("lo"))
        }.getOrNull()
    }
}

enum class Turn { STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, EXIT_LEFT, EXIT_RIGHT, FORK_LEFT, FORK_RIGHT, FINISH }

/** One manoeuvre of OUR route: where it is on the route line, what to do, onto which street. */
data class Maneuver(val pointIndex: Int, val turn: Turn, val text: String, val street: String)

/** Where the car is on the route right now. */
data class Progress(val next: Maneuver?, val toNextM: Double, val remainingM: Double, val remainingS: Double)

fun fmtDistance(m: Double): String = when {
    m >= 10_000 -> "${(m / 1000).roundToInt()} км"
    m >= 1000 -> "%.1f км".format(m / 1000)
    m >= 100 -> "${(m / 50).roundToInt() * 50} м"
    else -> "${(m / 10).roundToInt().coerceAtLeast(1) * 10} м"
}

fun turnText(t: Turn): String = when (t) {
    Turn.STRAIGHT -> "Прямо"; Turn.SLIGHT_LEFT -> "Держитесь левее"; Turn.SLIGHT_RIGHT -> "Держитесь правее"
    Turn.LEFT -> "Поверните налево"; Turn.RIGHT -> "Поверните направо"
    Turn.SHARP_LEFT -> "Резко налево"; Turn.SHARP_RIGHT -> "Резко направо"; Turn.UTURN -> "Разворот"
    Turn.ROUNDABOUT -> "Круговое движение"; Turn.EXIT_LEFT -> "Съезд налево"; Turn.EXIT_RIGHT -> "Съезд направо"
    Turn.FORK_LEFT -> "На развилке левее"; Turn.FORK_RIGHT -> "На развилке правее"; Turn.FINISH -> "Финиш"
}

data class Route(
    val destination: Place,
    /** lat, lon pairs */
    val points: List<DoubleArray>,
    val distanceM: Double,
    val durationS: Double,
    val provider: String,
    val maneuvers: List<Maneuver> = emptyList(),
    val builtAt: Long = SystemClock.elapsedRealtime(),
) {
    /** cumulative distance (m) from the start to every point */
    val cum: DoubleArray by lazy {
        val c = DoubleArray(points.size); val r = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1], r)
            c[i] = c[i - 1] + r[0]
        }
        c
    }
    val distanceText: String get() = if (distanceM >= 1000) "%.1f км".format(distanceM / 1000).replace(".0 ", " ") else "${(distanceM / 10).roundToInt() * 10} м"
    val durationText: String get() {
        val m = (durationS / 60).roundToInt().coerceAtLeast(1)
        return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
    }
    val arrivalText: String get() {
        val t = System.currentTimeMillis() + (durationS * 1000).toLong()
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}

/** Search + routing backend. */
interface NavProvider {
    val name: String
    fun search(query: String, near: Location?, cb: (List<Place>) -> Unit, err: (String) -> Unit)
    fun route(from: Location, to: Place, cb: (Route) -> Unit, err: (String) -> Unit)
    /** Данные о точке на карте (нажали по карте): название + адрес. Best-effort. */
    fun reverse(lat: Double, lon: Double, cb: (name: String, address: String) -> Unit) {}
    /** Организации рядом/в этой точке (как в карточке Яндекс.Карт). Best-effort; пусто если нет. */
    fun nearbyOrgs(lat: Double, lon: Double, cb: (List<String>) -> Unit) {}
}

/**
 * Keyless backend on OpenStreetMap: Photon (search as you type, fallback Nominatim) and OSRM (car routing).
 * Both are public free services — fine for one car, not for mass use.
 */
object OsmNav : NavProvider {
    override val name = "OpenStreetMap"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val UA = "MinimalDrive/1.0 (personal car launcher)"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "ru")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * Сначала пробуем через прокси на Yandex Cloud (белый список РФ) — так поиск и маршруты OSM
     * работают даже при ограничениях интернета; если прокси не задан или не ответил — напрямую.
     */
    private fun getVia(url: String): String {
        Wx.proxyUrl()?.let { p ->
            runCatching { return get("$p?action=get&url=" + URLEncoder.encode(url, "UTF-8")) }
        }
        return get(url)
    }

    override fun search(query: String, near: Location?, cb: (List<Place>) -> Unit, err: (String) -> Unit) {
        io.execute {
            val q = URLEncoder.encode(query, "UTF-8")
            val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: ""
            val r = runCatching {
                val j = JSONObject(getVia("https://photon.komoot.io/api/?q=$q$bias&limit=10"))
                val f = j.getJSONArray("features")
                (0 until f.length()).map { i ->
                    val o = f.getJSONObject(i); val p = o.getJSONObject("properties"); val c = o.getJSONObject("geometry").getJSONArray("coordinates")
                    val street = listOfNotNull(p.optString("street").ifEmpty { null }, p.optString("housenumber").ifEmpty { null }).joinToString(", ")
                    val name = p.optString("name").ifEmpty { street.ifEmpty { p.optString("city") } }
                    val desc = listOf(if (name == street) "" else street, p.optString("city"), p.optString("state"))
                        .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
                    Place(name, desc, c.getDouble(1), c.getDouble(0))
                }
            }.recoverCatching {
                // fallback: Nominatim (1 request/second policy — search is only on submit/debounced)
                val vb = near?.let { "&viewbox=${it.longitude - 1},${it.latitude + 1},${it.longitude + 1},${it.latitude - 1}" } ?: ""
                val a = JSONArray(getVia("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=10&accept-language=ru$vb"))
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i); val full = o.getString("display_name")
                    Place(full.substringBefore(","), full.substringAfter(", ", ""), o.getDouble("lat"), o.getDouble("lon"))
                }
            }
            main.post { r.fold({ cb(it) }, { err("Поиск недоступен: ${it.message}") }) }
        }
    }

    override fun reverse(lat: Double, lon: Double, cb: (name: String, address: String) -> Unit) {
        io.execute {
            runCatching {
                val j = JSONObject(getVia("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=ru&zoom=18"))
                val a = j.optJSONObject("address")
                val road = a?.optString("road").orEmpty()
                val house = a?.optString("house_number").orEmpty()
                val full = j.optString("display_name")
                val name = listOf(road, house).filter { it.isNotBlank() }.joinToString(", ").ifBlank { full.substringBefore(",") }
                if (name.isNotBlank()) main.post { cb(name, full) }
            }
        }
    }

    override fun route(from: Location, to: Place, cb: (Route) -> Unit, err: (String) -> Unit) {
        io.execute {
            val coords = "${from.longitude},${from.latitude};${to.lon},${to.lat}"
            val servers = listOf(
                "https://router.project-osrm.org/route/v1/driving/",
                "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
            )
            var last: Throwable? = null
            for (s in servers) {
                val r = runCatching {
                    val j = JSONObject(getVia("$s$coords?overview=full&geometries=geojson&steps=true"))
                    val route = j.getJSONArray("routes").getJSONObject(0)
                    val c = route.getJSONObject("geometry").getJSONArray("coordinates")
                    val pts = (0 until c.length()).map { val p = c.getJSONArray(it); doubleArrayOf(p.getDouble(1), p.getDouble(0)) }
                    Route(to, pts, route.getDouble("distance"), route.getDouble("duration"), "OSRM", osrmManeuvers(route, pts))
                }
                if (r.isSuccess) { main.post { cb(r.getOrThrow()) }; return@execute }
                last = r.exceptionOrNull()
            }
            main.post { err("Маршрут не построен: ${last?.message}") }
        }
    }
}

/** OSRM steps → our manoeuvres (only real decisions, not "continue straight"). */
internal fun osrmManeuvers(route: JSONObject, pts: List<DoubleArray>): List<Maneuver> {
    val out = ArrayList<Maneuver>()
    var from = 0
    val legs = route.getJSONArray("legs")
    for (l in 0 until legs.length()) {
        val steps = legs.getJSONObject(l).getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            val m = st.getJSONObject("maneuver")
            val type = m.optString("type"); val mod = m.optString("modifier")
            fun side(left: Turn, right: Turn, straight: Turn? = null) = when {
                mod.contains("left") -> left; mod.contains("right") -> right; else -> straight
            }
            val turnOrNull: Turn? = when (type) {
                "depart" -> null
                "arrive" -> Turn.FINISH
                "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> if (type.startsWith("exit")) null else Turn.ROUNDABOUT
                "off ramp" -> side(Turn.EXIT_LEFT, Turn.EXIT_RIGHT, Turn.EXIT_RIGHT)
                "fork" -> side(Turn.FORK_LEFT, Turn.FORK_RIGHT)
                "new name", "continue", "notification" -> if (mod == "uturn") Turn.UTURN else when (mod) {
                    "slight left" -> Turn.SLIGHT_LEFT; "slight right" -> Turn.SLIGHT_RIGHT
                    "left" -> Turn.LEFT; "right" -> Turn.RIGHT; else -> null
                }
                else -> when (mod) {  // turn, end of road, merge, on ramp …
                    "uturn" -> Turn.UTURN; "sharp left" -> Turn.SHARP_LEFT; "sharp right" -> Turn.SHARP_RIGHT
                    "left" -> Turn.LEFT; "right" -> Turn.RIGHT; "slight left" -> Turn.SLIGHT_LEFT; "slight right" -> Turn.SLIGHT_RIGHT
                    else -> null
                }
            }
            val turn = turnOrNull ?: continue
            val loc = m.getJSONArray("location"); val lat = loc.getDouble(1); val lon = loc.getDouble(0)
            // nearest route point, searching forward (routes can pass the same place twice)
            var best = from; var bestD = Double.MAX_VALUE
            for (k in from until pts.size) {
                val d = Math.hypot((pts[k][0] - lat) * 110_540.0, (pts[k][1] - lon) * 111_320.0 * Math.cos(Math.toRadians(lat)))
                if (d < bestD) { bestD = d; best = k }
                if (d > bestD + 3000) break
            }
            from = best
            val exit = m.optInt("exit", 0)
            val text = if (turn == Turn.ROUNDABOUT && exit > 0) "На кольце — $exit-й съезд" else turnText(turn)
            out += Maneuver(best, turn, text, st.optString("name"))
        }
    }
    return out
}

/** Active route, home address, rerouting. */
class NavRepo(private val ctx: Context) {
    var route by mutableStateOf<Route?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var home by mutableStateOf(Place.fromJson(Prefs.str(ctx, "home_place")))
        private set
    /** next manoeuvre + remaining distance/time on OUR route */
    var progress by mutableStateOf<Progress?>(null)
        private set
    private var lastIdx = 0

    val provider: NavProvider get() = if (YandexMaps.enabled) YandexMaps.nav else OsmNav
    private var lastReroute = 0L

    /** Избранные места (работа, дача…) — рядом с «Домой». */
    var favorites by mutableStateOf(loadFavorites())
        private set

    private fun loadFavorites(): List<Place> = runCatching {
        val a = JSONArray(Prefs.str(ctx, Prefs.FAVORITES_PLACES) ?: "[]")
        (0 until a.length()).mapNotNull { Place.fromJson(a.getString(it)) }
    }.getOrDefault(emptyList())

    private fun saveFavorites(list: List<Place>) {
        favorites = list
        Prefs.put(ctx, Prefs.FAVORITES_PLACES, JSONArray(list.map { it.toJson() }).toString())
    }

    // ---- Поиск на карте: маркеры результатов, нажатая точка, история ----
    /** Результаты поиска — показываются маркерами на карте, пока открыт поиск. */
    var pins by mutableStateOf<List<Place>>(emptyList())
    /** Выделенный результат (нажали в списке или на маркере). */
    var selectedPin by mutableStateOf<Place?>(null)
    /** Точка, по которой нажали на карте, — ждёт подтверждения «Маршрут сюда». */
    var tapTarget by mutableStateOf<Place?>(null)

    /** История поиска (последние выбранные места). */
    var recents by mutableStateOf(loadRecents())
        private set

    private fun loadRecents(): List<Place> = runCatching {
        val a = JSONArray(Prefs.str(ctx, Prefs.RECENT_PLACES) ?: "[]")
        (0 until a.length()).mapNotNull { Place.fromJson(a.getString(it)) }
    }.getOrDefault(emptyList())

    fun rememberRecent(p: Place) {
        val list = (listOf(p) + recents.filterNot { it.lat == p.lat && it.lon == p.lon }).take(10)
        recents = list
        Prefs.put(ctx, Prefs.RECENT_PLACES, JSONArray(list.map { it.toJson() }).toString())
    }

    fun clearRecents() { recents = emptyList(); Prefs.put(ctx, Prefs.RECENT_PLACES, "[]") }

    /** Идёт ли загрузка данных о точке (для карточки слева). */
    var tapLoading by mutableStateOf(false)
    /** Время в пути до точки («12 км · 25 мин») — как в карточке Яндекса. */
    var placeEta by mutableStateOf<String?>(null)
    /** Организации в/рядом с точкой (кратко). */
    var placeOrgs by mutableStateOf<List<String>>(emptyList())

    private fun samePoint(lat: Double, lon: Double) = tapTarget?.let { it.lat == lat && it.lon == lon } == true

    /** Нажали на карту: показать точку и подтянуть её данные (адрес, время в пути, организации). */
    fun tapOnMap(lat: Double, lon: Double, from: Location?) {
        tapTarget = Place("Точка на карте", "", lat, lon)
        tapLoading = true; placeEta = null; placeOrgs = emptyList()
        runCatching {
            provider.reverse(lat, lon) { name, address ->
                if (samePoint(lat, lon)) tapTarget = tapTarget?.copy(name = name.ifBlank { "Точка на карте" }, description = address)
                tapLoading = false
            }
        }.onFailure { tapLoading = false }
        loadPlaceInfo(lat, lon, from)
    }

    /** Открыть карточку уже известного места (из поиска) + подтянуть время в пути и организации. */
    fun showPlace(p: Place, from: Location?) {
        tapTarget = p; tapLoading = false; placeEta = null; placeOrgs = emptyList()
        loadPlaceInfo(p.lat, p.lon, from)
    }

    private fun loadPlaceInfo(lat: Double, lon: Double, from: Location?) {
        val dest = Place("", "", lat, lon)
        if (from != null) runCatching {
            provider.route(from, dest, { r ->
                if (samePoint(lat, lon)) {
                    val mins = (r.durationS / 60).toInt().coerceAtLeast(1)
                    val dur = if (mins >= 60) "${mins / 60} ч ${mins % 60} мин" else "$mins мин"
                    placeEta = "${fmtDistance(r.distanceM)} · $dur"
                }
            }, {})
        }
        runCatching { provider.nearbyOrgs(lat, lon) { orgs -> if (samePoint(lat, lon)) placeOrgs = orgs } }
    }

    /** Скрыть маркеры результатов и нажатую точку (поиск закрыт). */
    fun clearPins() { pins = emptyList(); selectedPin = null; tapTarget = null; placeEta = null; placeOrgs = emptyList() }

    fun isFavorite(p: Place) = favorites.any { it.lat == p.lat && it.lon == p.lon }
    fun toggleFavorite(p: Place) =
        saveFavorites(if (isFavorite(p)) favorites.filterNot { it.lat == p.lat && it.lon == p.lon } else (favorites + p).takeLast(12))
    fun removeFavorite(p: Place) = saveFavorites(favorites.filterNot { it.lat == p.lat && it.lon == p.lon })

    fun saveHome(p: Place) { home = p; Prefs.put(ctx, "home_place", p.toJson()) }

    /**
     * Build a route from where the car IS. A stale or rough position (last known fix from before parking,
     * network location) would start the route somewhere else — then we wait a few seconds for a fresh GPS fix.
     */
    fun routeTo(dest: Place, from: Location?) {
        if (from == null || !fresh(from)) {
            message = "Нет позиции — маршрут построится, когда появится точка на карте"
            pending = dest; pendingSince = SystemClock.elapsedRealtime(); return
        }
        pending = null
        busy = true
        provider.route(from, dest, { r -> busy = false; setRoute(r, from); message = null }, { e -> busy = false; message = e })
    }

    private var pending: Place? = null
    private var pendingSince = 0L

    /**
     * Годится ли точка как СТАРТ маршрута. Роутер всё равно привяжет её к ближайшей дороге, а по мере
     * движения маршрут пересчитается — поэтому строим сразу от той точки, что уже видно на карте
     * (восстановленной/примерной), не дожидаясь «идеального» GPS. Отклоняем только совсем мусорную позицию.
     */
    private fun fresh(l: Location): Boolean = !l.hasAccuracy() || l.accuracy <= 1500f

    fun clear() { route = null; progress = null; pending = null; message = null; onRoute?.invoke(null) }

    /** Called with every new route (the launcher pre-loads speed limits along it). */
    var onRoute: ((Route?) -> Unit)? = null

    private fun setRoute(r: Route, at: Location) { route = r; lastIdx = 0; Voice.resetRoute(); updateProgress(r, at); onRoute?.invoke(r) }

    /** Project the car onto the route line → along-route distance → next manoeuvre. */
    private fun updateProgress(r: Route, l: Location) {
        val pts = r.points
        if (pts.size < 2) { progress = null; return }
        val k = 111_320.0 * Math.cos(Math.toRadians(l.latitude))
        // search near the previous position first (routes may overlap themselves)
        val lo = (lastIdx - 5).coerceAtLeast(0); val hi = (lastIdx + 80).coerceAtMost(pts.size - 1)
        fun scan(a: Int, b: Int): Triple<Int, Double, Double> {
            var bi = a; var bt = 0.0; var bd = Double.MAX_VALUE
            for (i in a until b) {
                val ax = (pts[i][1] - l.longitude) * k; val ay = (pts[i][0] - l.latitude) * 110_540.0
                val bx = (pts[i + 1][1] - l.longitude) * k; val by = (pts[i + 1][0] - l.latitude) * 110_540.0
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = Math.hypot(ax + t * dx, ay + t * dy)
                if (d < bd) { bd = d; bi = i; bt = t }
            }
            return Triple(bi, bt, bd)
        }
        var (i, t, d) = scan(lo, hi)
        if (d > 60) { val g = scan(0, pts.size - 1); i = g.first; t = g.second; d = g.third }
        lastIdx = i
        val cum = r.cum
        val along = cum[i] + t * (cum[i + 1] - cum[i])
        val total = cum.last().coerceAtLeast(1.0)
        val next = r.maneuvers.firstOrNull { cum[it.pointIndex.coerceIn(0, cum.size - 1)] > along + 8 }
        val toNext = next?.let { cum[it.pointIndex.coerceIn(0, cum.size - 1)] - along } ?: (total - along)
        val remaining = (total - along).coerceAtLeast(0.0)
        progress = Progress(next, toNext, remaining, r.durationS * remaining / total)
    }

    /** Every GPS fix: build a pending route, reroute when off the line, finish on arrival. */
    fun onLocation(l: Location) {
        pending?.let { if (!busy && fresh(l)) routeTo(it, l) }
        val r = route ?: return
        val dest = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, r.destination.lat, r.destination.lon, dest)
        if (dest[0] < 40) { message = "Вы приехали"; route = null; progress = null; Voice.say(ctx, "Вы приехали", important = false); return }
        updateProgress(r, l)
        Voice.onProgress(ctx, progress, (if (l.hasSpeed()) l.speed * 3.6f else 0f).toInt())
        val now = SystemClock.elapsedRealtime()
        val off = distanceToLine(l, r.points)
        val stale = now - r.builtAt > 3 * 60_000L   // refresh ETA every few minutes
        if ((off > 70 || stale) && !busy && now - lastReroute > 15_000) {
            lastReroute = now
            busy = true
            provider.route(l, r.destination, { nr -> busy = false; setRoute(nr, l) }, { busy = false })
        }
    }

    private fun distanceToLine(l: Location, pts: List<DoubleArray>): Double {
        if (pts.size < 2) return 0.0
        val k = 111_320.0 * Math.cos(Math.toRadians(l.latitude))
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ax = (pts[i][1] - l.longitude) * k; val ay = (pts[i][0] - l.latitude) * 110_540.0
            val bx = (pts[i + 1][1] - l.longitude) * k; val by = (pts[i + 1][0] - l.latitude) * 110_540.0
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, Math.hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    /** Hand the current destination to Яндекс Навигатор (or just open the chosen navigator). */
    /**
     * «Развернуть»: open the chosen navigator WITH the current destination, so the trip continues there
     * (Яндекс Навигатор / Карты, 2ГИС, Google Maps, Waze; any other app gets a geo: link). No route → just open it.
     */
    fun openInNavigator(from: Location?, fallback: () -> Unit) {
        val d = route?.destination ?: return fallback()
        val navi = Apps.resolve(ctx, Prefs.NAV, Known.NAV) ?: return fallback()
        val lat = "%.6f".format(java.util.Locale.US, d.lat); val lon = "%.6f".format(java.util.Locale.US, d.lon)
        val fromQ = from?.let { "&lat_from=%.6f&lon_from=%.6f".format(java.util.Locale.US, it.latitude, it.longitude) } ?: ""
        val fromR = from?.let { "%.6f,%.6f".format(java.util.Locale.US, it.latitude, it.longitude) } ?: ""
        val uris = when (navi) {
            "ru.yandex.yandexnavi" -> listOf("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon$fromQ")
            "ru.yandex.yandexmaps" -> listOf("yandexmaps://maps.yandex.ru/?rtext=$fromR~$lat,$lon&rtt=auto")
            "ru.dublgis.dgismobile" -> listOf("dgis://2gis.ru/routeSearch/rsType/car/to/$lon,$lat")
            "com.google.android.apps.maps" -> listOf("google.navigation:q=$lat,$lon&mode=d")
            "com.waze" -> listOf("waze://?ll=$lat,$lon&navigate=yes")
            else -> emptyList()
        } + "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(d.name)})"
        for (u in uris) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(navi).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        fallback()
    }
}

/** Reachability of every online service the launcher uses — run from Диагностика on the head unit itself. */
object ServiceCheck {
    val targets = listOf(
        "Карта OpenStreetMap" to "https://tile.openstreetmap.org/3/4/2.png",
        "Поиск Photon" to "https://photon.komoot.io/api/?q=Moscow&limit=1",
        "Поиск Nominatim" to "https://nominatim.openstreetmap.org/search?q=Moscow&format=json&limit=1",
        "Маршруты OSRM" to "https://router.project-osrm.org/route/v1/driving/37.62,55.75;37.60,55.76?overview=false",
        "Маршруты OSRM (запасной)" to "https://routing.openstreetmap.de/routed-car/route/v1/driving/37.62,55.75;37.60,55.76?overview=false",
        "Ограничения (Overpass)" to "https://overpass-api.de/api/status",
        "Погода Open-Meteo" to "https://api.open-meteo.com/v1/forecast?latitude=55.75&longitude=37.62&current=temperature_2m",
        "Яндекс Карты" to "https://api-maps.yandex.ru/",
        "Обновления GitHub" to "https://api.github.com",
    )

    fun run(cb: (List<String>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val out = targets.map { (name, url) ->
                val t0 = SystemClock.elapsedRealtime()
                val code = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 6000; c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "MinimalDrive/1.0")
                    c.responseCode.also { c.disconnect() }
                }
                val ms = SystemClock.elapsedRealtime() - t0
                code.fold({ if (it in 200..399) "✓ $name — $ms мс" else "✗ $name — HTTP $it" }, { "✗ $name — ${it.javaClass.simpleName}" })
            }
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }
}
