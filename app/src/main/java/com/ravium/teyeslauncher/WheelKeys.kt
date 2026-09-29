package com.ravium.teyeslauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Steering-wheel gestures. Android lets an accessibility service see hardware keys before apps do, so
 * long / double presses of «next» / «previous» can run actions, while a normal single press is passed
 * on to the player unchanged. Needs one switch in Настройки → Спец. возможности (no computer, no root).
 */
class WheelKeys : AccessibilityService() {
    companion object {
        /** Last hardware key seen — shown in settings to check that the wheel buttons reach Android. */
        @Volatile var lastKey: String? = null
        @Volatile var running = false

        val actions = listOf(
            "none" to "Ничего",
            "home" to "Маршрут домой",
            "fav1" to "Первое избранное место",
            "nav" to "Открыть навигатор",
            "source" to "Переключить музыку / Bluetooth",
            "like" to "Лайк треку",
            "launcher" to "Главный экран",
            "voice" to "Голос: вкл / выкл",
        )
        val gestures = listOf(
            "next_long" to "Долгое «следующий трек»",
            "next_double" to "Двойное «следующий трек»",
            "prev_long" to "Долгое «предыдущий трек»",
            "prev_double" to "Двойное «предыдущий трек»",
        )
        private val defaults = mapOf("next_long" to "home", "next_double" to "none", "prev_long" to "nav", "prev_double" to "none")

        fun action(ctx: Context, gesture: String) = Prefs.str(ctx, Prefs.WHEEL + gesture, defaults[gesture] ?: "none")

        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") && it.endsWith("WheelKeys") }
        }

        fun openSettings(ctx: Context) = Apps.openFirst(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))

        /** Runs [id] — also used by the tiles. */
        fun perform(ctx: Context, id: String) {
            val app = ctx.applicationContext
            val s = LauncherState.current
            when (id) {
                "home", "fav1" -> {
                    val place = if (id == "home") s?.nav?.home else s?.nav?.favorites?.firstOrNull()
                    if (s == null || place == null) { Voice.say(app, if (id == "home") "Дом не задан" else "Нет избранных мест", important = true); return }
                    s.nav.routeTo(place, s.vehicle.location)
                    Kiosk.bringHome(app)
                    Voice.say(app, if (id == "home") "Маршрут домой" else "Маршрут: ${place.name}", important = true)
                }
                "nav" -> s?.openNavigator() ?: Apps.launch(app, Apps.resolve(app, Prefs.NAV, Known.NAV))
                "source" -> s?.media?.let { it.select(if (it.source == Source.YANDEX) Source.BLUETOOTH else Source.YANDEX) }
                "like" -> s?.media?.toggleLike()
                "launcher" -> Kiosk.bringHome(app)
                "voice" -> {
                    val on = Voice.enabled(app)
                    if (on) { Prefs.put(app, "voice_level_prev", Voice.level(app)); Prefs.put(app, Prefs.VOICE_LEVEL, "off"); Apps.toast(app, "Голосовые подсказки выключены") }
                    else { Prefs.put(app, Prefs.VOICE_LEVEL, Prefs.str(app, "voice_level_prev", "important")); Voice.say(app, "Подсказки включены", important = true) }
                    s?.settingsVersion = (s?.settingsVersion ?: 0) + 1
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val longFired = HashMap<String, Boolean>()
    private val longTask = HashMap<String, Runnable>()
    private val pendingSingle = HashMap<String, Runnable>()

    override fun onServiceConnected() { running = true }
    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) lastKey = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val key = when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
            else -> return false
        }
        val longA = action(this, key + "_long")
        val dblA = action(this, key + "_double")
        if (longA == "none" && dblA == "none") return false   // nothing configured → key untouched

        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) {
                    longFired[key] = false
                    if (longA != "none") {
                        val r = Runnable { longFired[key] = true; pendingSingle.remove(key)?.let { main.removeCallbacks(it) }; perform(this, longA) }
                        longTask[key] = r
                        main.postDelayed(r, 650)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                longTask.remove(key)?.let { main.removeCallbacks(it) }
                if (longFired[key] == true) { longFired[key] = false; return true }
                if (dblA != "none") {
                    val pend = pendingSingle.remove(key)
                    if (pend != null) { main.removeCallbacks(pend); perform(this, dblA) }
                    else {
                        val r = Runnable { pendingSingle.remove(key); replay(e.keyCode) }
                        pendingSingle[key] = r
                        main.postDelayed(r, 380)
                    }
                } else replay(e.keyCode)
                return true
            }
        }
        return false
    }

    /** A plain press: hand it to the active player exactly as if we weren't here. */
    private fun replay(code: Int) {
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }
}
