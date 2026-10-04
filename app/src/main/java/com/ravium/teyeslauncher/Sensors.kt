package com.ravium.teyeslauncher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Speed and position. TEYES has no Android Car API, so everything comes from the GNSS receiver:
 *  • listens to every provider (gps, network, passive — passive also gets fixes Яндекс Навигатор requests);
 *  • if the receiver doesn't report speed (some FYT modules), speed is computed from consecutive fixes;
 *  • retries until location permission is granted.
 */
class Vehicle(private val ctx: Context) {
    var speedKmh by mutableIntStateOf(0)
        private set
    var location: Location? = null
        private set
    /** Bumped on every accepted fix (map follows it). */
    var fixVersion by mutableIntStateOf(0)
        private set
    /** Travel direction in degrees, kept while standing still. */
    var bearing = 0f
        private set
    val limits = SpeedLimit()
    val alerts = Alerts(ctx)
    var onFix: ((Location) -> Unit)? = null
    var lastFixAt = 0L
        private set
    var satellites = 0
        private set
    var providers: List<String> = emptyList()
        private set
    // ---- odometer / trip (for reminders, the trip tile and the parking screen)
    private var odoM = Prefs.str(ctx, Prefs.ODO_GPS_M, "0").toDoubleOrNull() ?: 0.0
    private var odoSaved = odoM
    /** metres since ignition (reset in [resetTrip]) */
    var tripM by mutableStateOf(0.0)
        private set
    var tripStartAt = SystemClock.elapsedRealtime()
        private set
    var movingMs = 0L
        private set
    /** last moment the car was really moving (> 5 km/h) */
    var lastMovingAt = SystemClock.elapsedRealtime()
        private set
    fun resetTrip() { tripM = 0.0; tripStartAt = SystemClock.elapsedRealtime(); movingMs = 0 }

    /** Odometer in km: the value the user entered from the dashboard + what GPS has counted since. */
    val odometerKm: Double get() {
        val base = Prefs.str(ctx, Prefs.ODO_BASE)?.toDoubleOrNull() ?: return odoM / 1000
        val at = Prefs.str(ctx, Prefs.ODO_BASE_GPS, "0").toDoubleOrNull() ?: 0.0
        return base + (odoM - at) / 1000
    }
    fun setOdometer(km: Double) {
        Prefs.put(ctx, Prefs.ODO_BASE, km.toString()); Prefs.put(ctx, Prefs.ODO_BASE_GPS, odoM.toString()); fixVersion++
    }
    val odometerSet get() = Prefs.str(ctx, Prefs.ODO_BASE) != null

    private var lastFix = 0L
    private var lastGpsFix = 0L
    private var prev: Location? = null
    private var smooth = 0f
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val gnss = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private var lastSaved = 0L

    private fun onFix(l: Location) {
        val now = SystemClock.elapsedRealtime()
        val isGps = l.provider == LocationManager.GPS_PROVIDER
        // Prefer real GPS; other providers only when GPS has been silent for 5 s.
        if (!isGps && now - lastGpsFix < 5000) return
        if (isGps) lastGpsFix = now
        // ВАЖНО: грубые фиксы (большая погрешность) раньше отбрасывались целиком — из-за этого позиция
        // могла не появиться никогда («Поиск GPS…» навсегда, погода не грузилась). Теперь позицию принимаем
        // всегда (карте и погоде хватает примерной), а точные вычисления (одометр/скорость) считаем только по точным фиксам.
        val precise = !l.hasAccuracy() || l.accuracy <= 50f
        val usableForSpeed = !l.hasAccuracy() || l.accuracy <= 150f

        var mps: Float? = if (l.hasSpeed() && l.speed > 0.3f) l.speed else null
        val p = prev
        if (mps == null && p != null && usableForSpeed) {
            val dt = (l.time - p.time) / 1000f
            if (dt in 0.4f..10f) mps = l.distanceTo(p) / dt
        }
        if (l.hasSpeed() && l.speed <= 0.3f && mps == null) mps = 0f
        if (mps != null && usableForSpeed) {
            smooth = if (smooth == 0f) mps else smooth * 0.5f + mps * 0.5f
            val kmh = (smooth * 3.6f).roundToInt()
            speedKmh = if (kmh < 3) 0 else kmh.coerceAtMost(260)
        }
        if (p != null && speedKmh >= 5 && (!l.hasAccuracy() || l.accuracy <= 35f)) {
            val d = l.distanceTo(p).toDouble()
            val dt = l.time - p.time
            if (d < 400 && dt in 1..15_000) {
                odoM += d; tripM += d; movingMs += dt
                if (odoM - odoSaved > 300) { odoSaved = odoM; Prefs.put(ctx, Prefs.ODO_GPS_M, odoM.toString()) }
            }
        }
        if (speedKmh > 5) lastMovingAt = now
        // direction only while really moving — standing still, GPS noise would spin the map around
        if (l.hasBearing() && (mps ?: 0f) > 2.5f) bearing = l.bearing
        else if (p != null && speedKmh >= 8 && l.distanceTo(p) > 8) bearing = p.bearingTo(l).let { if (it < 0) it + 360 else it }

        if (precise || prev == null) prev = l
        location = l
        lastFix = now
        lastFixAt = now
        fixVersion++
        // запоминаем последнюю позицию — чтобы погода/карта работали сразу после запуска, до фикса GPS
        if (now - lastSaved > 20_000) {
            lastSaved = now
            Prefs.put(ctx, "last_lat", l.latitude.toString()); Prefs.put(ctx, "last_lon", l.longitude.toString())
        }
        onFix?.invoke(l)
        if (Prefs.bool(ctx, Prefs.LIMITS, true)) {
            limits.onLocation(l, speedKmh, bearing)
            alerts.onSpeed(speedKmh, limits.limit)
        }
    }

    init { limits.onCameraApproach = { alerts.camera(it) } }

    val hasPermission get() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Safe to call repeatedly (every few seconds) — starts once permission is there. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasPermission) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val wanted = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused")
        val ok = mutableListOf<String>()
        for (pr in wanted) {
            if (pr !in lm.allProviders) continue
            runCatching { lm.requestLocationUpdates(pr, if (pr == LocationManager.GPS_PROVIDER) 500L else 1000L, 0f, listener, Looper.getMainLooper()); ok += pr }
        }
        runCatching { lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper())) }
        providers = ok
        started = ok.isNotEmpty()
        if (location == null) location = ok.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        // ещё нет фикса — берём последнюю сохранённую позицию, чтобы погода/карта работали сразу (не «Поиск GPS…»)
        if (location == null) {
            val la = Prefs.str(ctx, "last_lat")?.toDoubleOrNull(); val lo = Prefs.str(ctx, "last_lon")?.toDoubleOrNull()
            if (la != null && lo != null) location = Location("restored").apply { latitude = la; longitude = lo }
        }
        if (location != null) fixVersion++
    }

    /** Called every second: drop to 0 when fixes stop (tunnel, parking); retry start. */
    fun tick() {
        if (!started) start()
        if (speedKmh != 0 && SystemClock.elapsedRealtime() - lastFix > 4000) { speedKmh = 0; smooth = 0f }
    }

    fun debug(): String {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val gpsOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        return "разрешение: ${if (hasPermission) "да" else "НЕТ"} · GPS включён: ${if (gpsOn) "да" else "НЕТ"} · источники: ${providers.joinToString().ifEmpty { "—" }} · спутников: $satellites"
    }
}

/** Temperature + weather code from Open-Meteo (free, no key). */
class Weather {
    var tempC by mutableStateOf<Int?>(null)
        private set
    var code by mutableIntStateOf(3)
        private set
    private var lastUpdate = 0L

    suspend fun maybeUpdate(loc: Location?) {
        if (loc == null) return
        val now = SystemClock.elapsedRealtime()
        if (lastUpdate != 0L && now - lastUpdate < 20 * 60_000L) return
        val result = withContext(Dispatchers.IO) { Wx.current(loc.latitude, loc.longitude) }
        if (result != null) { tempC = result.first; code = result.second; lastUpdate = now }
        else lastUpdate = now - 15 * 60_000L // retry in ~5 min
        }
}

class Status(private val ctx: Context) {
    var bluetoothOn by mutableStateOf(true)
        private set
    var online by mutableStateOf(true)
        private set

    @SuppressLint("MissingPermission")
    fun refresh() {
        // TEYES uses its own BT module; if the Android adapter is absent we just show the icon as on.
        bluetoothOn = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: true }.getOrDefault(true)
        online = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }
}
