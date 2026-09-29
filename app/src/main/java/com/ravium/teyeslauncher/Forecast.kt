package com.ravium.teyeslauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Detailed weather (Open-Meteo, free, no key, works in Russia): now, next hours, next days, road warnings. */
data class Forecast(
    val temp: Int, val feels: Int, val code: Int, val windMs: Int, val humidity: Int,
    val hours: List<Hour>, val days: List<Day>, val warnings: List<String>,
) {
    data class Hour(val label: String, val temp: Int, val code: Int, val rainPct: Int)
    data class Day(val label: String, val min: Int, val max: Int, val code: Int, val rainMm: Double)
}

object ForecastRepo {
    private var cache: Pair<String, Pair<Long, Forecast>>? = null

    suspend fun load(lat: Double, lon: Double): Forecast? = withContext(Dispatchers.IO) {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        cache?.let { (k, v) -> if (k == key && System.currentTimeMillis() - v.first < 15 * 60_000) return@withContext v.second }
        runCatching {
            val url = URL(("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=24" +
                "&daily=temperature_2m_min,temperature_2m_max,weather_code,precipitation_sum&forecast_days=6" +
                "&wind_speed_unit=ms&timezone=auto").format(Locale.US, lat, lon))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val cur = j.getJSONObject("current")
            val h = j.getJSONObject("hourly")
            val ht = h.getJSONArray("time"); val htemp = h.getJSONArray("temperature_2m"); val hcode = h.getJSONArray("weather_code")
            val hp = h.optJSONArray("precipitation_probability")
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            val hours = (0 until minOf(ht.length(), 24)).map { i ->
                val time = inFmt.parse(ht.getString(i))
                Forecast.Hour(SimpleDateFormat("HH:mm", Locale.US).format(time!!), htemp.getDouble(i).roundToInt(), hcode.getInt(i),
                    hp?.optInt(i) ?: 0)
            }
            val d = j.getJSONObject("daily")
            val dt = d.getJSONArray("time")
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val days = (0 until dt.length()).map { i ->
                val date = dayFmt.parse(dt.getString(i))!!
                val label = when (i) { 0 -> "Сегодня"; 1 -> "Завтра"; else -> SimpleDateFormat("EE, d MMM", Locale("ru")).format(date).replaceFirstChar { it.titlecase() } }
                Forecast.Day(label, d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(), d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                    d.getJSONArray("weather_code").getInt(i), d.getJSONArray("precipitation_sum").optDouble(i, 0.0))
            }
            val next12 = hours.take(12)
            val warnings = buildList {
                if (next12.any { it.temp in -3..2 } && next12.any { it.code in 51..67 || it.code in 71..77 || it.code in 80..86 })
                    add("Возможен гололёд — осадки около нуля")
                if (next12.any { it.code in 71..77 || it.code in 85..86 }) add("Снег в ближайшие часы")
                if (next12.any { it.code in 95..99 }) add("Гроза")
                if (next12.any { it.code == 45 || it.code == 48 }) add("Туман — плохая видимость")
                if (cur.optDouble("wind_speed_10m", 0.0) >= 15) add("Сильный ветер")
            }
            Forecast(cur.getDouble("temperature_2m").roundToInt(), cur.getDouble("apparent_temperature").roundToInt(), cur.getInt("weather_code"),
                cur.optDouble("wind_speed_10m", 0.0).roundToInt(), cur.optInt("relative_humidity_2m"), hours, days, warnings)
        }.getOrNull()?.also { cache = key to (System.currentTimeMillis() to it) }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Ясно"; 1 -> "Преимущественно ясно"; 2 -> "Переменная облачность"; 3 -> "Пасмурно"
        45, 48 -> "Туман"; in 51..57 -> "Морось"; in 61..67 -> "Дождь"; in 71..77 -> "Снег"
        in 80..82 -> "Ливень"; 85, 86 -> "Снегопад"; in 95..99 -> "Гроза"; else -> "Облачно"
    }
}
