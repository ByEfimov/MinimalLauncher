package com.ravium.teyeslauncher

import android.app.Application
import android.content.Context
import android.location.Location
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouter
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.runtime.Error

/** Application: Yandex MapKit must be initialised once per process, before any map is created. */
class MinimalDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        License.init(this)
        com.ravium.teyeslauncher.ui.Accent.load(this)
        YandexMaps.init(this)
        OfflineMaps.start()
        Voice.init(this)
        Wx.yandexWeatherKey = Prefs.str(this, Prefs.YANDEX_WEATHER_KEY)
        // Прокси: сохранённый в настройках имеет приоритет, иначе — вшитый по умолчанию (Yandex Cloud).
        Wx.proxy = Prefs.str(this, Prefs.PROXY_URL, "").ifBlank { BuildConfig.PROXY_URL }
    }
}

/**
 * Яндекс Карты (MapKit SDK): map with traffic, driving routes with traffic, Yandex search.
 * Enabled only when an API key is set in gradle.properties → yandexMapKitKey (free key:
 * https://developer.tech.yandex.ru → «MapKit Mobile SDK»). Without a key the launcher uses OpenStreetMap.
 */
object YandexMaps {
    var enabled = false
        private set
    var error: String? = null
        private set

    fun init(app: Context) {
        // key entered in Настройки → Карта (each user has their own), or baked in via gradle.properties
        val key = Prefs.str(app, Prefs.YANDEX_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.YANDEX_MAPKIT_KEY
        if (key.isBlank()) { error = "ключ не задан"; return }
        try {
            MapKitFactory.setApiKey(key)
            MapKitFactory.setLocale("ru_RU")
            MapKitFactory.initialize(app)
            enabled = true
        } catch (t: Throwable) {
            error = t.javaClass.simpleName + ": " + t.message
            enabled = false
        }
    }

    /** MapKit reads the key only at process start → restart the launcher after changing it. */
    fun restartApp(ctx: Context) {
        val i = android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(ctx, 42, i, android.app.PendingIntent.FLAG_CANCEL_CURRENT)
        ctx.getSystemService(android.app.AlarmManager::class.java).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun onStart() { if (enabled) runCatching { MapKitFactory.getInstance().onStart() } }
    fun onStop() { if (enabled) runCatching { MapKitFactory.getInstance().onStop() } }

    val nav: NavProvider by lazy { YandexNav() }
}

private class YandexNav : NavProvider {
    override val name = "Яндекс"
    private val search: SearchManager by lazy { SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED) }
    private val router: DrivingRouter by lazy { DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED) }
    // sessions must be kept referenced, otherwise MapKit cancels them
    private var searchSession: Session? = null
    private var drivingSession: DrivingSession? = null

    override fun search(query: String, near: Location?, cb: (List<Place>) -> Unit, err: (String) -> Unit) {
        val center = near?.let { Point(it.latitude, it.longitude) } ?: Point(55.7539, 37.6208)
        val opts = SearchOptions().setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value).setResultPageSize(12)
            .setUserPosition(center)
        searchSession = search.submit(query, Geometry.fromPoint(center), opts, object : Session.SearchListener {
            override fun onSearchResponse(r: Response) {
                cb(r.collection.children.mapNotNull { item ->
                    val o = item.obj ?: return@mapNotNull null
                    val p = o.geometry.firstOrNull()?.point ?: return@mapNotNull null
                    Place(o.name ?: "", o.descriptionText ?: "", p.latitude, p.longitude)
                })
            }
            override fun onSearchError(e: Error) { err("Поиск Яндекса: ${e.javaClass.simpleName}") }
        })
    }

    private var reverseSession: Session? = null
    override fun reverse(lat: Double, lon: Double, cb: (name: String, address: String) -> Unit) {
        reverseSession = search.submit(Point(lat, lon), 18, SearchOptions().setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value), object : Session.SearchListener {
            override fun onSearchResponse(r: Response) {
                val o = r.collection.children.firstOrNull()?.obj
                val name = o?.name ?: ""
                val addr = o?.descriptionText ?: ""
                if (name.isNotBlank() || addr.isNotBlank()) cb(name.ifBlank { addr }, addr)
            }
            override fun onSearchError(e: Error) {}
        })
    }

    override fun route(from: Location, to: Place, cb: (Route) -> Unit, err: (String) -> Unit) {
        val pts = listOf(
            RequestPoint(Point(from.latitude, from.longitude), RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(Point(to.lat, to.lon), RequestPointType.WAYPOINT, null, null, null),
        )
        val opts = DrivingOptions().setRoutesCount(1).apply { if (from.hasBearing()) setInitialAzimuth(from.bearing.toDouble()) }
        drivingSession = router.requestRoutes(pts, opts, VehicleOptions(), object : DrivingSession.DrivingRouteListener {
            override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                val r = routes.firstOrNull() ?: run { err("Маршрут не найден"); return }
                val w = r.metadata.weight
                cb(Route(to, r.geometry.points.map { doubleArrayOf(it.latitude, it.longitude) },
                    w.distance.value, (w.timeWithTraffic ?: w.time).value, "Яндекс", yandexManeuvers(r)))
            }
            override fun onDrivingRoutesError(e: Error) { err("Маршрут Яндекса: ${e.javaClass.simpleName}") }
        })
    }
}

/** Each Yandex route section starts with a manoeuvre (annotation.action) — same data the Яндекс Карты app shows. */
private fun yandexManeuvers(r: DrivingRoute): List<Maneuver> = r.sections.mapIndexedNotNull { idx, sec ->
    val a = sec.metadata.annotation
    val turn = when (a.action) {
        com.yandex.mapkit.directions.driving.Action.SLIGHT_LEFT -> Turn.SLIGHT_LEFT
        com.yandex.mapkit.directions.driving.Action.SLIGHT_RIGHT -> Turn.SLIGHT_RIGHT
        com.yandex.mapkit.directions.driving.Action.LEFT -> Turn.LEFT
        com.yandex.mapkit.directions.driving.Action.RIGHT -> Turn.RIGHT
        com.yandex.mapkit.directions.driving.Action.HARD_LEFT -> Turn.SHARP_LEFT
        com.yandex.mapkit.directions.driving.Action.HARD_RIGHT -> Turn.SHARP_RIGHT
        com.yandex.mapkit.directions.driving.Action.FORK_LEFT -> Turn.FORK_LEFT
        com.yandex.mapkit.directions.driving.Action.FORK_RIGHT -> Turn.FORK_RIGHT
        com.yandex.mapkit.directions.driving.Action.UTURN_LEFT, com.yandex.mapkit.directions.driving.Action.UTURN_RIGHT -> Turn.UTURN
        com.yandex.mapkit.directions.driving.Action.ENTER_ROUNDABOUT -> Turn.ROUNDABOUT
        com.yandex.mapkit.directions.driving.Action.EXIT_LEFT -> Turn.EXIT_LEFT
        com.yandex.mapkit.directions.driving.Action.EXIT_RIGHT -> Turn.EXIT_RIGHT
        com.yandex.mapkit.directions.driving.Action.FINISH -> Turn.FINISH
        else -> null   // STRAIGHT, LEAVE_ROUNDABOUT, ferries, waypoints
    } ?: return@mapIndexedNotNull null
    if (idx == 0 && turn != Turn.FINISH) return@mapIndexedNotNull null
    val point = if (turn == Turn.FINISH) sec.geometry.end.segmentIndex + 1 else sec.geometry.begin.segmentIndex
    Maneuver(point.coerceIn(0, r.geometry.points.size - 1), turn, turnText(turn), a.toponym ?: "")
}
