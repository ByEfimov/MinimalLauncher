package com.ravium.teyeslauncher

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Speed limit of the road you are on, from OpenStreetMap (maxspeed tags) via Overpass.
 * Roads within ~350 m are downloaded once, then every GPS fix is matched to the nearest road locally
 * (distance + driving direction), so the network is used only every few hundred metres.
 */
class SpeedLimit {
    /** Current limit in km/h, null = unknown. */
    var limit by mutableStateOf<Int?>(null)
        private set

    data class CameraAhead(val distanceM: Int, val limit: Int?)
    /** Nearest speed camera ahead within 500 m (OSM highway=speed_camera). */
    var cameraAhead by mutableStateOf<CameraAhead?>(null)
        private set
    /** Called once per camera when it gets within ~400 m ahead. */
    var onCameraApproach: ((CameraAhead) -> Unit)? = null
    private val warned = HashSet<Long>()

    private class Cam(val id: Long, val x: Double, val y: Double, val limit: Int?)
    private class Way(val xs: DoubleArray, val ys: DoubleArray, val fwd: Int?, val bwd: Int?, val both: Int?, val oneway: Boolean)
    private class Area(val lat0: Double, val lon0: Double, val ways: List<Way>, val cams: List<Cam> = emptyList()) {
        // bounding box in local metres (+ margin) — used to pick a pre-loaded route area for the car's position
        val minX = (ways.minOfOrNull { it.xs.min() } ?: 0.0) - 150; val maxX = (ways.maxOfOrNull { it.xs.max() } ?: 0.0) + 150
        val minY = (ways.minOfOrNull { it.ys.min() } ?: 0.0) - 150; val maxY = (ways.maxOfOrNull { it.ys.max() } ?: 0.0) + 150
    }
    /** Roads along the whole active route, loaded while online — limits and cameras keep working without internet. */
    @Volatile private var routeAreas: List<Area> = emptyList()
    /** «Ограничения на маршруте: загружено 120 из 340 км» */
    var routeStatus by mutableStateOf<String?>(null)
        private set
    private var routeKey = ""

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var area: Area? = null
    private var fetching = false
    private var lastFetchAt = 0L
    private var lastMatchAt = 0L
    private var endpoint = 0
    // Порядок важен для России: сначала российские/ближние зеркала, потом мировые.
    private val endpoints = listOf(
        "https://overpass.openstreetmap.ru/cgi/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    var lastError: String? = null
        private set
    /** Area around the car looks like a town (many residential streets) — affects default limits. */
    var urban: Boolean? = null
        private set

    private var speedKmh = 0
    private var bearing = 0f

    fun onLocation(l: Location, speedKmh: Int, bearing: Float) {
        this.speedKmh = speedKmh; this.bearing = bearing
        val a = area
        val now = SystemClock.elapsedRealtime()
        val retryAfter = if (lastError != null) 20_000 else 8000
        if ((a == null || meters(a.lat0, a.lon0, l.latitude, l.longitude) > 180) && !fetching && now - lastFetchAt > retryAfter) fetch(l)
        // live area when it's near; otherwise a pre-loaded piece of the route (e.g. no internet)
        val use = a?.takeIf { meters(it.lat0, it.lon0, l.latitude, l.longitude) < 450 }
            ?: routeAreas.firstOrNull { r -> project(r, l.latitude, l.longitude).let { (x, y) -> x in r.minX..r.maxX && y in r.minY..r.maxY } }
            ?: a
        if (use != null) { match(use, l, now); cameras(use, l) }
        if (now - lastMatchAt > 30_000) limit = null
    }

    private fun match(a: Area, l: Location, now: Long) {
        val (px, py) = project(a, l.latitude, l.longitude)
        val useBearing = speedKmh > 12
        val maxDist = 25.0 + (if (l.hasAccuracy()) l.accuracy.coerceAtMost(30f) else 10f)
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        for (w in a.ways) {
            for (i in 0 until w.xs.size - 1) {
                val d = segDist(px, py, w.xs[i], w.ys[i], w.xs[i + 1], w.ys[i + 1])
                if (d > maxDist) continue
                var score = d
                var forward = true
                if (useBearing) {
                    val segBearing = Math.toDegrees(Math.atan2(w.xs[i + 1] - w.xs[i], w.ys[i + 1] - w.ys[i]))
                    val diff = angleDiff(segBearing, bearing.toDouble())
                    forward = diff < 90
                    val undirected = if (w.oneway) diff else minOf(diff, 180 - diff)
                    if (w.oneway && !forward) continue
                    score += undirected / 3.0 // 30° off ≈ 10 m penalty
                }
                val v = if (forward) (w.fwd ?: w.both) else (w.bwd ?: w.both)
                if (v != null && score < bestScore) { bestScore = score; best = v }
            }
        }
        if (best != null) { limit = best; lastMatchAt = now }
    }

    private fun cameras(a: Area, l: Location) {
        if (speedKmh < 15) { if (speedKmh == 0) cameraAhead = null; return }
        val (px, py) = project(a, l.latitude, l.longitude)
        var best: Cam? = null; var bestD = Double.MAX_VALUE
        for (c in a.cams) {
            val d = hypot(c.x - px, c.y - py)
            if (d > 500 || d < 5) continue
            val brg = Math.toDegrees(Math.atan2(c.x - px, c.y - py))
            if (angleDiff(brg, bearing.toDouble()) > 35) continue
            if (d < bestD) { bestD = d; best = c }
        }
        val b = best
        cameraAhead = b?.let { CameraAhead(((bestD / 10).roundToInt() * 10), it.limit) }
        if (b != null && bestD < 420 && warned.add(b.id)) cameraAhead?.let { onCameraApproach?.invoke(it) }
    }

    fun debug(): String {
        val err = lastError?.let { " · ошибка: $it" } ?: ""
        val a = area ?: return "дороги не загружены" + (if (fetching) " (загрузка…)" else "") + err
        return "дорог рядом: ${a.ways.size}, камер: ${a.cams.size}, ${if (urban == true) "город" else "трасса"}, ограничение: ${limit ?: "—"}, сервер: ${endpoints[endpoint].substringAfter("//").substringBefore("/")}$err"
    }

    private fun fetch(l: Location) {
        fetching = true
        lastFetchAt = SystemClock.elapsedRealtime()
        val lat = l.latitude; val lon = l.longitude
        io.execute {
            val q = "[out:json][timeout:10];(way(around:350,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];node(around:1500,%.6f,%.6f)[highway=speed_camera];);out tags geom;"
                .format(java.util.Locale.US, lat, lon, lat, lon)
            var result: Area? = null
            var err: String? = null
            for (attempt in endpoints.indices) {
                val url = endpoints[(endpoint + attempt) % endpoints.size]
                val r = runCatching { download(url, q, lat, lon) }
                result = r.getOrNull()
                if (result != null) { endpoint = (endpoint + attempt) % endpoints.size; break }
                err = r.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + (it.message ?: "") }?.take(80)
            }
            main.post {
                fetching = false
                if (result != null) { area = result; lastError = null } else lastError = err
            }
        }
    }

    /**
     * Pre-load speed limits and cameras along the route (in ~25 km pieces, roads within 60 m of the line).
     * Overpass «around» accepts a whole polyline, so one request covers one piece.
     */
    fun prefetchRoute(points: List<DoubleArray>, key: String) {
        if (key == routeKey || points.size < 2) return
        routeKey = key
        routeAreas = emptyList()
        // thin the line to a point every ~150 m, then cut into ~25 km chunks (max ~800 km)
        val thin = ArrayList<DoubleArray>(); var acc = 0.0; var total = 0.0
        thin += points.first()
        for (i in 1 until points.size) {
            val d = meters(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]); acc += d; total += d
            if (acc >= 150 || i == points.size - 1) { thin += points[i]; acc = 0.0 }
            if (total > 800_000) break
        }
        val chunks = ArrayList<List<DoubleArray>>(); var cur = ArrayList<DoubleArray>(); var len = 0.0
        for (i in thin.indices) {
            if (cur.isNotEmpty()) len += meters(cur.last()[0], cur.last()[1], thin[i][0], thin[i][1])
            cur += thin[i]
            if (len >= 25_000) { chunks += cur; cur = arrayListOf(thin[i]); len = 0.0 }
        }
        if (cur.size > 1) chunks += cur
        val totalKm = (total / 1000).roundToInt()
        routeStatus = "Ограничения на маршруте: загрузка…"
        io.execute {
            val got = ArrayList<Area>()
            var doneKm = 0.0
            for (ch in chunks) {
                if (routeKey != key) return@execute   // a newer route replaced this one
                val line = ch.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.US, it[0], it[1]) }
                val q = "[out:json][timeout:25];(way(around:60,$line)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];" +
                    "node(around:120,$line)[highway=speed_camera];);out tags geom;"
                var area: Area? = null
                for (attempt in endpoints.indices) {
                    area = runCatching { download(endpoints[(endpoint + attempt) % endpoints.size], q, ch[0][0], ch[0][1], updateUrban = false) }.getOrNull()
                    if (area != null) break
                }
                if (area != null) { got += area; routeAreas = got.toList() }
                for (k in 1 until ch.size) doneKm += meters(ch[k - 1][0], ch[k - 1][1], ch[k][0], ch[k][1]) / 1000
                val n = got.size; val d = doneKm.roundToInt()
                main.post { if (routeKey == key) routeStatus = "Ограничения на маршруте: $d из $totalKm км" + if (n < chunks.size && d >= totalKm) " (часть не загрузилась)" else "" }
            }
        }
    }

    fun clearRoute() { routeKey = ""; routeAreas = emptyList(); routeStatus = null }

    private fun download(url: String, query: String, lat: Double, lon: Double, updateUrban: Boolean = true): Area {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 15000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", "MinimalDrive/0.9 (personal TEYES launcher)")
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) { c.disconnect(); error("HTTP ${c.responseCode}") }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val tmp = Area(lat, lon, emptyList())
        val elements = JSONObject(body).getJSONArray("elements")
        val cams = ArrayList<Cam>()
        val raw = ArrayList<org.json.JSONObject>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") == "node") {
                val (x, y) = project(tmp, e.getDouble("lat"), e.getDouble("lon"))
                cams += Cam(e.optLong("id"), x, y, parse(e.optJSONObject("tags")?.optString("maxspeed", "") ?: ""))
            } else raw += e
        }
        // Town or open road? Decides the default limit for streets without a maxspeed tag (RU rules: 60 / 90).
        val isUrban = raw.count { it.optJSONObject("tags")?.optString("highway") in setOf("residential", "living_street") } >= 3
        val ways = ArrayList<Way>()
        for (e in raw) {
            val tags = e.optJSONObject("tags") ?: continue
            val geom = e.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val hw = tags.optString("highway")
            val both = parse(tags.optString("maxspeed", "")) ?: parse(tags.optString("maxspeed:type", ""))
                ?: parse(tags.optString("source:maxspeed", "")) ?: parse(tags.optString("zone:maxspeed", "")) ?: defaultFor(hw, isUrban)
            val fwd = parse(tags.optString("maxspeed:forward", ""))
            val bwd = parse(tags.optString("maxspeed:backward", ""))
            if (both == null && fwd == null && bwd == null) continue
            val oneway = tags.optString("oneway") in setOf("yes", "1", "true") || hw == "motorway" || tags.optString("junction") == "roundabout"
            val xs = DoubleArray(geom.length()); val ys = DoubleArray(geom.length())
            for (k in 0 until geom.length()) {
                val p = geom.getJSONObject(k)
                val (x, y) = project(tmp, p.getDouble("lat"), p.getDouble("lon"))
                xs[k] = x; ys[k] = y
            }
            ways += Way(xs, ys, fwd, bwd, both, oneway)
        }
        if (updateUrban) main.post { urban = isUrban }
        return Area(lat, lon, ways, cams)
    }

    companion object {
        /** "60", "RU:urban", "40 mph", "60;40" → km/h. */
        fun parse(v: String): Int? {
            val s = v.trim().lowercase()
            if (s.isEmpty() || s == "none" || s == "signals" || s == "variable" || s == "walk") return null
            if (s.contains(":")) return when (s.substringAfter(":")) {
                "urban" -> 60; "rural" -> 90; "motorway" -> 110; "living_street" -> 20; "trunk" -> 90; else -> null
            }
            val n = s.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            return if (s.contains("mph")) (n * 1.609).roundToInt() else n
        }

        /** Russian traffic rules when the road has no maxspeed tag: 20 living street, 60 in town, 90 outside, 110 motorway. */
        fun defaultFor(highway: String, urban: Boolean): Int? = when (highway) {
            "living_street" -> 20
            "motorway" -> 110
            "motorway_link" -> if (urban) 60 else 90
            "residential" -> 60
            "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "unclassified" -> if (urban) 60 else 90
            else -> null
        }
    }

    // ---------- geometry (local metres around area centre) ----------
    private fun project(a: Area, lat: Double, lon: Double): Pair<Double, Double> =
        ((lon - a.lon0) * 111_320.0 * cos(Math.toRadians(a.lat0))) to ((lat - a.lat0) * 110_540.0)

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        hypot((lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1)), (lat2 - lat1) * 110_540.0)

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = abs(((a - b) % 360 + 360) % 360); return if (d > 180) 360 - d else d }
}
