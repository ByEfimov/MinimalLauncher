package com.ravium.teyeslauncher

import android.location.Location
import android.os.SystemClock
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.location.Purpose
import com.yandex.mapkit.location.SubscriptionSettings
import com.yandex.mapkit.location.UseInBackground
import java.lang.ref.WeakReference

/**
 * Пока Android LocationManager ждёт первый фикс GPS (на холодную — десятки секунд), у Яндекс MapKit
 * позиция обычно уже есть (fused: сеть + GPS) — именно её видно стрелкой на карте. Берём эту позицию и
 * отдаём в [Vehicle], чтобы маршрут строился сразу, а не висел на «Поиск GPS».
 *
 * Работает, когда задан ключ Яндекса (MapKit инициализирован), независимо от того, какая карта выбрана.
 * MapKit хранит слушателя по слабой ссылке — поэтому держим strong-ссылку на него здесь.
 */
object MapkitLocation {
    private var lm: com.yandex.mapkit.location.LocationManager? = null
    private var listener: LocationListener? = null
    private var ref: WeakReference<LocationListener>? = null
    @Volatile private var active = false

    fun start(vehicle: Vehicle) {
        if (active || !YandexMaps.enabled) return
        runCatching {
            val m = MapKitFactory.getInstance().createLocationManager()
            val l = object : LocationListener {
                override fun onLocationUpdated(loc: com.yandex.mapkit.location.Location) {
                    val a = Location("mapkit").apply {
                        latitude = loc.position.latitude
                        longitude = loc.position.longitude
                        time = loc.absoluteTimestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        loc.accuracy?.let { accuracy = it.toFloat() }
                        loc.speed?.let { speed = it.toFloat() }
                        loc.heading?.let { bearing = it.toFloat() }
                    }
                    vehicle.submitExternal(a)
                }
                override fun onLocationStatusUpdated(status: LocationStatus) {}
            }
            val r = WeakReference(l)
            m.subscribeForLocationUpdates(SubscriptionSettings(UseInBackground.DISALLOW, Purpose.AUTOMOTIVE_NAVIGATION), r)
            runCatching { m.requestSingleUpdate(r) }   // сразу запросить текущую позицию
            lm = m; listener = l; ref = r; active = true
        }
    }

    fun stop() {
        runCatching { ref?.let { r -> lm?.unsubscribe(r) } }
        listener = null; ref = null; active = false
    }
}
