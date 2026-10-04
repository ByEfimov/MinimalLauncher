package com.ravium.teyeslauncher

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Persistent settings. */
object Prefs {
    const val NAV = "navigation_package"
    const val PHONE = "phone_package"
    const val BT_MUSIC = "bluetooth_package"
    const val CARLINK = "carlink_package"
    const val SOURCE = "music_source"
    const val LIMITS = "speed_limits"
    const val LIMIT_TOLERANCE = "limit_tolerance"   // km/h over the limit before the speed turns red
    const val KIOSK = "kiosk"                       // always bring Minimal Drive back instead of the stock launcher
    const val DOCK = "dock_"                        // dock_1..dock_3: custom app for bottom-bar slots
    const val FAVORITES = "favorites"
    const val RECENTS = "recents"
    const val NIGHT = "night"                       // auto | on | off
    const val NIGHT_DIM = "night_dim"               // 0.15 | 0.3 | 0.45
    const val SOUND_OVERSPEED = "sound_overspeed"
    const val CAMERA_WARN = "camera_warn"
    const val AUTO_PLAY = "auto_play"
    const val AUTO_NAV = "auto_nav"
    const val MAP_STYLE = "map_style"             // dark | light | auto
    const val MAP_HEADING = "map_heading"         // rotate map by travel direction
    const val MAP_TRAFFIC = "map_traffic"         // Яндекс пробки
    const val MAP_PROVIDER = "map_provider"       // osm | yandex | 2gis — какую карту показывать на главном
    const val YANDEX_KEY = "yandex_mapkit_key"    // entered in the app
    const val GIS_KEY = "gis2_mapkit_key"         // 2ГИС MapGL ключ (пробки + маршруты 2ГИС)
    const val YANDEX_WEATHER_KEY = "yandex_weather_key"  // Яндекс Погода (белый список РФ)
    const val PROXY_URL = "proxy_url"             // Yandex Cloud функция: погода + ограничения (белый список)
    const val USER_NAME = "user_name"             // greeting in the header
    const val ACCENT = "accent"                   // ARGB hex
    const val FAVORITES_PLACES = "favorite_places"
    const val RECENT_PLACES = "recent_places"     // история поиска (последние выбранные места)
    const val ONBOARDED = "onboarded"
    const val BT_OPEN_APP = "bt_open_app"         // open TEYES BT screen when switching to Bluetooth
    const val MAIN_PLAYER = "main_player"         // package of the main music tab (Яндекс Музыка by default)
    const val VOICE_LEVEL = "voice_level"         // all | important | off
    const val VOICE_NAME = "voice_name"           // TTS voice name, null = system default
    const val VOICE_RATE = "voice_rate"           // 0.8 | 1.0 | 1.25
    const val WHEEL = "wheel_"                    // wheel_next_long, wheel_next_double, wheel_prev_long, wheel_prev_double → action id
    const val DOCK_ITEMS = "dock_items"           // bottom bar: home,nav,music,phone,apps,app=pkg…
    const val TILES_V2 = "tiles_v2"               // tiles page (8-column grid): type:WxH[:arg],…
    const val REMINDERS = "reminders"             // JSON array
    const val ODO_GPS_M = "odo_gps_m"             // metres driven by GPS since install
    const val ODO_BASE = "odo_base_km"            // odometer the user entered…
    const val ODO_BASE_GPS = "odo_base_gps_m"     // …and GPS metres at that moment
    const val PARKING = "parking"                 // parking screen when standing still
    const val PARKING_DELAY = "parking_delay"     // minutes
    const val SETUP_SNOOZE = "setup_snooze"       // don't show the setup screen on start until this time (ms)
    const val HOME_BLOCKS = "home_blocks"         // side column of the main screen: music,service,tile=weather…
    const val HOME_SIDE = "home_side"             // left | right
    const val HOME_BIG = "home_big"               // map | clock
    const val HOME_WIDE = "home_wide"
    const val LICENSE = "license_code"            // activation signature; empty = locked
    const val PAGE = "page"                       // last main page
    const val AUTO_CLEAN = "auto_clean"           // free memory on ignition and every 30 min
    const val LITE_MAP = "lite_map"               // lighter map: no 3D, no traffic, fewer camera moves

    fun list(ctx: Context, key: String): List<String> = str(ctx, key)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    fun putList(ctx: Context, key: String, v: List<String>) = put(ctx, key, v.joinToString(","))

    private fun p(ctx: Context) = ctx.getSharedPreferences("minimal_drive", Context.MODE_PRIVATE)
    fun str(ctx: Context, key: String): String? = p(ctx).getString(key, null)
    fun str(ctx: Context, key: String, def: String): String = p(ctx).getString(key, def) ?: def
    fun put(ctx: Context, key: String, v: String?) = p(ctx).edit().putString(key, v).apply()
    /** Synchronous write — use before killing/restarting the process (apply() would be lost). */
    fun putNow(ctx: Context, key: String, v: String?) = p(ctx).edit().putString(key, v).commit()
    fun bool(ctx: Context, key: String, def: Boolean) = p(ctx).getBoolean(key, def)
    fun put(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
}

/** Known packages on TEYES CC3 and popular apps. First installed one is used until the user picks another. */
object Known {
    val YANDEX_MUSIC = listOf("ru.yandex.music")
    /** Candidates for the main player tab — first installed is used until the user picks one in settings. */
    val MAIN_PLAYER = listOf("ru.yandex.music", "com.uma.musicvk", "ru.mts.music.android", "com.zvooq.openplay", "com.spotify.music",
        "com.google.android.apps.youtube.music", "com.apple.android.music", "com.soundcloud.android", "com.maxmpz.audioplayer",
        "com.syu.music", "com.android.music")
    val NAV = listOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "ru.dublgis.dgismobile", "com.google.android.apps.maps", "com.waze")
    val PHONE = listOf("com.syu.bt", "com.syu.btapp", "com.syu.bluetooth")
    val BT_MUSIC = listOf("com.syu.bt", "com.syu.btapp", "com.syu.bluetooth", "com.syu.btmusic")
    // Bluetooth music sessions: FYT/TEYES bt app or the stock Android A2DP-sink service.
    val BT_SESSIONS = listOf("com.syu.bt", "com.syu.btapp", "com.syu.bluetooth", "com.syu.btmusic", "com.android.bluetooth")
    val CARLINK = listOf("com.syu.carlink", "com.zjinnova.zlink", "com.suding.carlink", "com.carlink.phonelink")
}

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

object Apps {
    private val iconCache = HashMap<String, ImageBitmap?>()

    fun installed(ctx: Context, pkg: String?): Boolean =
        pkg != null && try { ctx.packageManager.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false }

    fun firstInstalled(ctx: Context, pkgs: List<String>): String? = pkgs.firstOrNull { installed(ctx, it) }

    /** Saved choice if still installed, otherwise first known installed package. */
    fun resolve(ctx: Context, key: String, known: List<String>): String? =
        Prefs.str(ctx, key)?.takeIf { installed(ctx, it) } ?: firstInstalled(ctx, known)

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launch(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        return try {
            val i = launchIntent(ctx, pkg) ?: return false
            ctx.startActivity(i)
            Prefs.putList(ctx, Prefs.RECENTS, (listOf(pkg) + Prefs.list(ctx, Prefs.RECENTS).filter { it != pkg }).take(8))
            true
        } catch (_: Exception) { false }
    }

    fun label(ctx: Context, pkg: String?): String = if (pkg == null) "Не выбрано" else try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    fun icon(ctx: Context, pkg: String?): ImageBitmap? {
        if (pkg == null) return null
        return iconCache.getOrPut(pkg) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(192, 192).asImageBitmap() } catch (_: Exception) { null }
        }
    }

    /** All launchable apps, sorted. Heavy — call off the main thread. */
    fun all(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .map { AppEntry(it, label(ctx, it), icon(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Try settings screens in order; false if none exists on this firmware. */
    fun openFirst(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) == null) continue
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
