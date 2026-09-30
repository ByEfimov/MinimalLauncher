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

    override fun search(query: String, near: Location?, cb: (List<Place>) -> Unit, err: (String) -> Unit) {
        io.execute {
            val q = URLEncoder.encode(query, "UTF-8")
            val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: ""
            val r = runCatching {
                val j = JSONObject(get("https://photon.komoot.io/api/?q=$q$bias&limit=10"))
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
                val a = JSONArray(get("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=10&accept-language=ru$vb"))
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i); val full = o.getString("display_name")
                    Place(full.substringBefore(","), full.substringAfter(", ", ""), o.getDouble("lat"), o.getDouble("lon"))
                }
            }
            main.post { r.fold({ cb(it) }, { err("Поиск недоступен: ${it.message}") }) }
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
                    val j = JSONObject(get("$s$coords?overview=full&geometries=geojson&steps=true"))
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

    fun isFavorite(p: Place) = favorites.any { it.lat == p.lat && it.lon == p.lon }
    fun toggleFavorite(p: Place) =
        saveFavorites(if (isFavorite(p)) favorites.filterNot { it.lat == p.lat && it.lon == p.lon } else (favorites + p).takeLast(12))
    fun removeFavorite(p: Place) = saveFavorites(favorites.filterNot { it.lat == p.lat && it.lon == p.lon })

    fun saveHome(p: Place) { home = p; Prefs.put(ctx, "home_place", p.toJson()) }

    fun routeTo(dest: Place, from: Location?) {
        if (from == null) { message = "Нет GPS — маршрут построится, когда появится позиция"; pending = dest; return }
        busy = true
        provider.route(from, dest, { r -> busy = false; setRoute(r, from); message = null; pending = null }, { e -> busy = false; message = e })
    }

    private var pending: Place? = null

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
        pending?.let { if (!busy) routeTo(it, l) }
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
    fun openInNavigator(fallback: () -> Unit) {
        val d = route?.destination
        val navi = Apps.resolve(ctx, Prefs.NAV, Known.NAV)
        if (d != null && navi == "ru.yandex.yandexnavi") {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://build_route_on_map?lat_to=${d.lat}&lon_to=${d.lon}"))
                .setPackage("ru.yandex.yandexnavi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        if (d != null && navi == "ru.yandex.yandexmaps") {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?rtext=~${d.lat},${d.lon}&rtt=auto"))
                .setPackage("ru.yandex.yandexmaps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
