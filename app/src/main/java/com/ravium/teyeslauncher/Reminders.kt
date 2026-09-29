package com.ravium.teyeslauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Car maintenance reminders: by mileage (oil, filters… — counted from GPS, calibrated by the dashboard
 * odometer) or by date (ОСАГО, техосмотр, tyre change).
 */
data class Reminder(
    val id: Long,
    val title: String,
    val byKm: Boolean,
    val intervalKm: Int = 0,
    val lastKm: Double = 0.0,
    val dueAt: Long = 0L,          // date reminders: when it runs out
    val intervalMonths: Int = 0,   // date reminders: «Сделано» moves dueAt by this many months from today
) {
    fun toJson() = JSONObject().put("id", id).put("t", title).put("km", byKm).put("ik", intervalKm).put("lk", lastKm)
        .put("due", dueAt).put("im", intervalMonths)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(o.getLong("id"), o.getString("t"), o.getBoolean("km"), o.optInt("ik"),
            o.optDouble("lk", 0.0), o.optLong("due"), o.optInt("im"))
    }
}

/** How much is left, for display. */
data class ReminderState(val r: Reminder, val left: String, val fraction: Float, val due: Boolean, val soon: Boolean, val sortKey: Double)

object Reminders {
    data class Template(val title: String, val byKm: Boolean, val km: Int = 0, val months: Int = 0)

    val templates = listOf(
        Template("Замена масла", true, km = 10_000),
        Template("Воздушный фильтр", true, km = 15_000),
        Template("Салонный фильтр", true, km = 15_000),
        Template("Тормозные колодки", true, km = 30_000),
        Template("Свечи зажигания", true, km = 30_000),
        Template("Ремень ГРМ", true, km = 90_000),
        Template("ОСАГО", false, months = 12),
        Template("Техосмотр", false, months = 24),
        Template("Смена шин", false, months = 6),
        Template("Тормозная жидкость", false, months = 24),
    )

    var list by mutableStateOf<List<Reminder>>(emptyList())
        private set
    private var loaded = false

    fun load(ctx: Context): List<Reminder> {
        if (!loaded) {
            loaded = true
            list = runCatching {
                val a = JSONArray(Prefs.str(ctx, Prefs.REMINDERS) ?: "[]")
                (0 until a.length()).map { Reminder.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        return list
    }

    private fun save(ctx: Context, l: List<Reminder>) {
        list = l
        Prefs.put(ctx, Prefs.REMINDERS, JSONArray(l.map { it.toJson() }).toString())
    }

    fun upsert(ctx: Context, r: Reminder) { load(ctx); save(ctx, list.filter { it.id != r.id } + r) }
    fun remove(ctx: Context, id: Long) { load(ctx); save(ctx, list.filter { it.id != id }) }

    fun done(ctx: Context, r: Reminder, odoKm: Double) = upsert(ctx,
        if (r.byKm) r.copy(lastKm = odoKm)
        else r.copy(dueAt = Calendar.getInstance().apply { add(Calendar.MONTH, r.intervalMonths.coerceAtLeast(1)) }.timeInMillis))

    fun state(r: Reminder, odoKm: Double): ReminderState {
        return if (r.byKm) {
            val left = r.lastKm + r.intervalKm - odoKm
            val frac = if (r.intervalKm > 0) (1 - left / r.intervalKm).toFloat().coerceIn(0f, 1f) else 0f
            ReminderState(r, if (left >= 0) "через ${fmtKm(left)}" else "просрочено на ${fmtKm(-left)}", frac, left <= 0, left in 0.0..1000.0,
                left / 50.0)   // 50 km ≈ 1 day of driving for sorting against dates
        } else {
            val days = ((r.dueAt - System.currentTimeMillis()) / 86_400_000.0)
            val total = (r.intervalMonths.coerceAtLeast(1) * 30.4)
            val frac = (1 - days / total).toFloat().coerceIn(0f, 1f)
            val d = days.toInt()
            ReminderState(r, when {
                d < 0 -> "просрочено ${fmtDate(r.dueAt)}"
                d == 0 -> "сегодня"
                d <= 60 -> "через ${plural(d, "день", "дня", "дней")} · ${fmtDate(r.dueAt)}"
                else -> "до ${fmtDate(r.dueAt)}"
            }, frac, d < 0, d in 0..14, days)
        }
    }

    fun states(ctx: Context, odoKm: Double) = load(ctx).map { state(it, odoKm) }.sortedBy { it.sortKey }

    fun fmtKm(km: Double) = "%,d км".format(Locale("ru"), km.toLong()).replace(',', ' ')
    fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale("ru")).format(ms)

    /** "31.12.2026" → millis, or null. */
    fun parseDate(s: String): Long? = runCatching {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { isLenient = false }.parse(s.trim())?.time
    }.getOrNull()

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val w = when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
        return "$n $w"
    }
}
