package com.ravium.teyeslauncher

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Short driver alerts: over the limit, speed camera ahead. Uses the notification stream so it ducks music. */
class Alerts(private val ctx: Context) {
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }
    private var overSince = 0L
    private var lastOverBeep = 0L

    /** Call on every GPS fix. Beeps when you go over (limit + tolerance), then every 30 s while still over. */
    fun onSpeed(speed: Int, limit: Int?) {
        if (!Prefs.bool(ctx, Prefs.SOUND_OVERSPEED, true) || limit == null) { overSince = 0; return }
        val tol = Prefs.str(ctx, Prefs.LIMIT_TOLERANCE, "10").toIntOrNull() ?: 10
        val now = SystemClock.elapsedRealtime()
        if (speed > limit + tol) {
            if (overSince == 0L) overSince = now
            // 2 s of hysteresis against GPS jitter
            if (now - overSince > 2000 && now - lastOverBeep > 30_000) { lastOverBeep = now; beep(2) }
        } else overSince = 0
    }

    fun camera() { if (Prefs.bool(ctx, Prefs.CAMERA_WARN, true)) beep(3) }

    private fun beep(times: Int) {
        val t = tone ?: return
        Thread {
            repeat(times) { t.startTone(ToneGenerator.TONE_PROP_BEEP, 140); Thread.sleep(260) }
        }.start()
    }
}

/** Sunrise/sunset (NOAA simplified) — night mode without extra permissions. */
object Sun {
    /** true if the sun is below the horizon at [lat],[lon] right now. */
    fun isNight(lat: Double, lon: Double, now: Calendar = Calendar.getInstance()): Boolean {
        val rise = eventUtcHours(lat, lon, now, true)
        val set = eventUtcHours(lat, lon, now, false)
        if (rise == null || set == null) {
            // polar day/night: decide by season
            val summer = (now.get(Calendar.MONTH) in 3..8) == (lat > 0)
            return !summer
        }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now.timeInMillis }
        val h = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0
        return if (rise < set) h < rise || h > set else h > set && h < rise
    }

    private fun eventUtcHours(lat: Double, lon: Double, day: Calendar, sunrise: Boolean): Double? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1 || cosH < -1) return null
        var h = if (sunrise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
        h /= 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return ((localT - lngHour) % 24 + 24) % 24
    }
}
