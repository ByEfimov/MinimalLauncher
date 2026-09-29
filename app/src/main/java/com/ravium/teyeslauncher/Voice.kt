package com.ravium.teyeslauncher

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voice hints through Android text-to-speech (works offline with the built-in Russian voice).
 *  • «Все» — повороты по маршруту + ограничения, камеры, превышение;
 *  • «Важные» — только ограничения, камеры, превышение;
 *  • «Нет» — молчит (остаются короткие звуковые сигналы, если включены).
 * Audio goes to the navigation-guidance stream, so music is ducked, not stopped.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private lateinit var app: Context
    private var lastText = ""
    private var lastAt = 0L

    fun init(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) { apply(); pending?.let { speak(it) }; pending = null }
        }
    }

    /** off | important | all */
    fun level(ctx: Context) = Prefs.str(ctx, Prefs.VOICE_LEVEL, "important")
    fun enabled(ctx: Context) = level(ctx) != "off"

    /** Re-read voice / speed from settings. */
    fun apply() {
        val t = tts ?: return
        if (!ready) return
        runCatching {
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            val ru = Locale("ru", "RU")
            if (t.isLanguageAvailable(ru) >= TextToSpeech.LANG_AVAILABLE) t.language = ru
            val name = Prefs.str(app, Prefs.VOICE_NAME)
            if (name != null) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
            t.setSpeechRate(Prefs.str(app, Prefs.VOICE_RATE, "1.0").toFloatOrNull() ?: 1f)
        }
    }

    /** Russian voices installed on the head unit: name → human label. */
    fun voices(): List<Pair<String, String>> {
        val t = tts ?: return emptyList()
        if (!ready) return emptyList()
        return runCatching {
            t.voices.orEmpty().filter { it.locale.language == "ru" }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                .mapIndexed { i, v -> v.name to ("Голос ${i + 1}" + if (v.isNetworkConnectionRequired) " (онлайн)" else "") }
        }.getOrDefault(emptyList())
    }

    val isReady get() = ready

    /** [important] = limits, cameras, overspeed; otherwise it's a turn prompt (only at level «Все»). */
    fun say(ctx: Context, text: String, important: Boolean) {
        val lvl = level(ctx)
        if (lvl == "off" || (!important && lvl != "all")) return
        speak(text)
    }

    fun test(ctx: Context) { init(ctx); apply(); speak("Через триста метров поверните направо. Ограничение шестьдесят.") }

    private fun speak(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < 8000) return   // no stutter on GPS jitter
        lastText = text; lastAt = now
        val t = tts
        if (t == null || !ready) { pending = text; return }
        t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "md$now")
    }

    // ---------- turn prompts for OUR route ----------
    private var announcedFar = -1
    private var announcedNear = -1

    fun onProgress(ctx: Context, p: Progress?, speedKmh: Int) {
        val m = p?.next ?: return
        val far = if (speedKmh > 80) 900.0 else if (speedKmh > 50) 500.0 else 300.0
        val near = if (speedKmh > 80) 250.0 else 90.0
        val street = m.street.takeIf { it.isNotBlank() }?.let { " на $it" } ?: ""
        when {
            p.toNextM <= near && announcedNear != m.pointIndex -> {
                announcedNear = m.pointIndex; announcedFar = m.pointIndex
                say(ctx, if (m.turn == Turn.FINISH) "Вы почти приехали" else turnText(m.turn) + street, important = false)
            }
            p.toNextM in (near + 60)..far && announcedFar != m.pointIndex -> {
                announcedFar = m.pointIndex
                val what = if (m.turn == Turn.FINISH) "финиш" else turnText(m.turn).replaceFirstChar { it.lowercase() } + street
                say(ctx, "Через ${spokenDistance(p.toNextM)} $what", important = false)
            }
        }
    }

    fun resetRoute() { announcedFar = -1; announcedNear = -1 }

    private fun spokenDistance(m: Double): String = when {
        m >= 1000 -> if (m < 1500) "километр" else "${(m / 1000).toInt()} километра"
        m >= 100 -> "${(m / 100).toInt() * 100} метров"
        else -> "${(m / 10).toInt() * 10} метров"
    }
}
