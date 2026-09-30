package com.ravium.teyeslauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Detailed weather: now, next hours, next days, road warnings. Primary source wttr.in (без ключа, работает в России), запасной Open-Meteo. */
data class Forecast(
    val temp: Int, val feels: Int, val code: Int, val windMs: Int, val humidity: Int,
    val hours: List<Hour>, val days: List<Day>, val warnings: List<String>,
) {
    data class Hour(val label: String, val temp: Int, val code: Int, val rainPct: Int)
    data class Day(val label: String, val min: Int, val max: Int, val code: Int, val rainMm: Double)
}

/** Shared weather fetch helpers. Weather codes are normalised to the Open-Meteo scale so icons/описания одни для всех источников. */
object Wx {
    /** HTTP GET as text. wttr.in returns JSON only to a curl-like User-Agent, so we set one. */
    fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 9000
        c.setRequestProperty("User-Agent", "curl/8.4")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) { c.disconnect(); return null }
        c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }.getOrNull()

    /** WWO code (wttr.in) → Open-Meteo-like code, so the same icons/описания подходят. */
    fun wwo(code: Int): Int = when (code) {
        113 -> 0
        116 -> 2
        119, 122 -> 3
        143, 248, 260 -> 45
        176, 263, 266, 293, 296, 353 -> 61
        299, 302, 305, 308, 356, 359 -> 65
        179, 182, 185, 281, 284, 311, 314, 317, 320, 362, 365, 374, 377 -> 66
        227, 230, 323, 326, 329, 332, 335, 338, 368, 371, 395 -> 73
        200, 386, 389, 392 -> 95
        else -> 3
    }

    /** Current temp + normalised code. Tries wttr.in, then Open-Meteo. */
    fun current(lat: Double, lon: Double): Pair<Int, Int>? {
        runCatching {
            val body = get("https://wttr.in/%.4f,%.4f?format=j1".format(Locale.US, lat, lon)) ?: return@runCatching null
            val cc = JSONObject(body).getJSONArray("current_condition").getJSONObject(0)
            return cc.getString("temp_C").toInt() to wwo(cc.getString("weatherCode").toInt())
        }
        runCatching {
            val body = get("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code".format(Locale.US, lat, lon)) ?: return@runCatching null
            val cur = JSONObject(body).getJSONObject("current")
            return cur.getDouble("temperature_2m").roundToInt() to cur.getInt("weather_code")
        }
        return null
    }
}

object ForecastRepo {
    private var cache: Pair<String, Pair<Long, Forecast>>? = null

    suspend fun load(lat: Double, lon: Double): Forecast? = withContext(Dispatchers.IO) {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        cache?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < 15 * 60_000) return@withContext v.second }
        val fc = fromWttr(lat, lon) ?: fromOpenMeteo(lat, lon)
        if (fc != null) cache = key to (System.currentTimeMillis() to fc)
        fc
    }

    private fun warningsFor(hours: List<Forecast.Hour>, windMs: Int): List<String> {
        val next12 = hours.take(12)
        return buildList {
            if (next12.any { it.temp in -3..2 } && next12.any { it.code in 51..67 || it.code in 71..77 || it.code in 80..86 })
                add("Возможен гололёд — осадки около нуля")
            if (next12.any { it.code in 71..77 || it.code in 85..86 }) add("Снег в ближайшие часы")
            if (next12.any { it.code in 95..99 }) add("Гроза")
            if (next12.any { it.code == 45 || it.code == 48 }) add("Туман — плохая видимость")
            if (windMs >= 15) add("Сильный ветер")
        }
    }

    private fun fromWttr(lat: Double, lon: Double): Forecast? = runCatching {
        val body = Wx.get("https://wttr.in/%.4f,%.4f?format=j1".format(Locale.US, lat, lon)) ?: return null
        val j = JSONObject(body)
        val cc = j.getJSONArray("current_condition").getJSONObject(0)
        val windMs = (cc.getString("windspeedKmph").toInt() / 3.6).roundToInt()
        val wDays = j.getJSONArray("weather")
        val hours = ArrayList<Forecast.Hour>()
        val nowH = SimpleDateFormat("H", Locale.US).format(java.util.Date()).toInt()
        for (di in 0 until wDays.length()) {
            val hourly = wDays.getJSONObject(di).getJSONArray("hourly")
            for (hi in 0 until hourly.length()) {
                val ho = hourly.getJSONObject(hi)
                val hh = ho.getString("time").toInt() / 100   // "0","300"→3,"1200"→12
                if (di == 0 && hh < nowH - 2) continue
                hours += Forecast.Hour("%02d:00".format(hh), ho.getString("tempC").toInt(), Wx.wwo(ho.getString("weatherCode").toInt()),
                    ho.optString("chanceofrain", "0").toIntOrNull() ?: 0)
                if (hours.size >= 16) break
            }
            if (hours.size >= 16) break
        }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = (0 until wDays.length()).map { i ->
            val o = wDays.getJSONObject(i)
            val date = runCatching { dayFmt.parse(o.getString("date"))!! }.getOrDefault(java.util.Date())
            val label = when (i) { 0 -> "Сегодня"; 1 -> "Завтра"; else -> SimpleDateFormat("EE, d MMM", Locale("ru")).format(date).replaceFirstChar { it.titlecase() } }
            // daily code from the midday slot; rain = sum of hourly precip
            val hourly = o.getJSONArray("hourly")
            var middayCode = 3; var rain = 0.0
            for (hi in 0 until hourly.length()) {
                val ho = hourly.getJSONObject(hi)
                rain += ho.optString("precipMM", "0").toDoubleOrNull() ?: 0.0
                if (ho.getString("time").toInt() / 100 == 12) middayCode = Wx.wwo(ho.getString("weatherCode").toInt())
            }
            Forecast.Day(label, o.getString("mintempC").toInt(), o.getString("maxtempC").toInt(), middayCode, rain)
        }
        Forecast(cc.getString("temp_C").toInt(), cc.getString("FeelsLikeC").toInt(), Wx.wwo(cc.getString("weatherCode").toInt()),
            windMs, cc.optString("humidity", "0").toIntOrNull() ?: 0, hours, days, warningsFor(hours, windMs))
    }.getOrNull()

    private fun fromOpenMeteo(lat: Double, lon: Double): Forecast? = runCatching {
        val body = Wx.get("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=24" +
            "&daily=temperature_2m_min,temperature_2m_max,weather_code,precipitation_sum&forecast_days=6&wind_speed_unit=ms&timezone=auto") ?: return null
        val j = JSONObject(body)
        val cur = j.getJSONObject("current")
        val h = j.getJSONObject("hourly")
        val ht = h.getJSONArray("time"); val htemp = h.getJSONArray("temperature_2m"); val hcode = h.getJSONArray("weather_code")
        val hp = h.optJSONArray("precipitation_probability")
        val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val hours = (0 until minOf(ht.length(), 24)).map { i ->
            Forecast.Hour(SimpleDateFormat("HH:mm", Locale.US).format(inFmt.parse(ht.getString(i))!!),
                htemp.getDouble(i).roundToInt(), hcode.getInt(i), hp?.optInt(i) ?: 0)
        }
        val d = j.getJSONObject("daily"); val dt = d.getJSONArray("time")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = (0 until dt.length()).map { i ->
            val date = dayFmt.parse(dt.getString(i))!!
            val label = when (i) { 0 -> "Сегодня"; 1 -> "Завтра"; else -> SimpleDateFormat("EE, d MMM", Locale("ru")).format(date).replaceFirstChar { it.titlecase() } }
            Forecast.Day(label, d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(), d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                d.getJSONArray("weather_code").getInt(i), d.getJSONArray("precipitation_sum").optDouble(i, 0.0))
        }
        val windMs = cur.optDouble("wind_speed_10m", 0.0).roundToInt()
        Forecast(cur.getDouble("temperature_2m").roundToInt(), cur.getDouble("apparent_temperature").roundToInt(), cur.getInt("weather_code"),
            windMs, cur.optInt("relative_humidity_2m"), hours, days, warningsFor(hours, windMs))
    }.getOrNull()

    fun describe(code: Int): String = when (code) {
        0 -> "Ясно"; 1 -> "Преимущественно ясно"; 2 -> "Переменная облачность"; 3 -> "Пасмурно"
        45, 48 -> "Туман"; in 51..57 -> "Морось"; in 61..67 -> "Дождь"; in 71..77 -> "Снег"
        in 80..82 -> "Ливень"; 85, 86 -> "Снегопад"; in 95..99 -> "Гроза"; else -> "Облачно"
    }
}
