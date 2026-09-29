package com.ravium.teyeslauncher

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.ravium.teyeslauncher.ui.DesignHeight
import com.ravium.teyeslauncher.ui.LauncherRoot

/** Main screens under the header — switched by swiping the greeting. */
enum class Page(val title: String) { HOME("Главная"), TILES("Плитки"), REMINDERS("Напоминания"), OPTIMIZE("Оптимизация") }

sealed interface Overlay {
    data object Drawer : Overlay
    data object Settings : Overlay
    data class SettingsCat(val id: String) : Overlay
    data object Setup : Overlay
    data object Diagnostics : Overlay
    data class Search(val setHome: Boolean = false) : Overlay
    data object ApiKey : Overlay
    data object Welcome : Overlay
    data object Favorites : Overlay
    data object Weather : Overlay
    data object Dock : Overlay
    data object HomeLayout : Overlay
    data object Wheel : Overlay
    data object TileCatalog : Overlay
    data object Odometer : Overlay
    data class ReminderEdit(val id: Long? = null, val template: Int = -1) : Overlay
    data class Picker(val title: String, val onReset: (() -> Unit)? = null, val onPick: (String) -> Unit) : Overlay
}

/** Everything the UI needs, owned by the activity. @Stable: lets Compose skip cards whose inputs didn't change. */
@androidx.compose.runtime.Stable
class LauncherState(val activity: MainActivity) {
    companion object { var current: LauncherState? = null }
    val media = MediaRepo(activity.applicationContext)
    val vehicle = Vehicle(activity.applicationContext)
    val weather = Weather()
    val status = Status(activity.applicationContext)
    val updater = Updater(activity.applicationContext)
    val nav = NavRepo(activity.applicationContext)
    init { vehicle.onFix = { nav.onLocation(it) }; current = this }   // routing follows GPS without touching the UI tree
    /** Night dimming is on right now (auto by sunset, or forced in settings). */
    var night by mutableStateOf(false)
    var overlay by mutableStateOf<Overlay?>(null)
    /** Current main screen; remembered across restarts. */
    var page by mutableStateOf(runCatching { Page.valueOf(Prefs.str(activity, Prefs.PAGE, "HOME")) }.getOrDefault(Page.HOME))
        private set
    fun go(p: Page) { page = p; Prefs.put(activity, Prefs.PAGE, p.name); if (p != Page.TILES) tilesEdit = false }
    fun turnPage(delta: Int) { val all = Page.entries; go(all[(all.indexOf(page) + delta + all.size) % all.size]) }
    /** Tiles page is in edit mode (resize / move / remove / add). */
    var tilesEdit by mutableStateOf(false)
    /** Parking screen is shown instead of the map (standing still for a while, no route). */
    var parked by mutableStateOf(false)
    var parkDismissed = false
    /** Bumped when settings change so rows re-read Prefs. */
    var settingsVersion by mutableStateOf(0)

    fun pick(title: String, onReset: (() -> Unit)? = null, onPick: (String) -> Unit) { overlay = Overlay.Picker(title, onReset, onPick) }

    fun updateNight() {
        night = when (Prefs.str(activity, Prefs.NIGHT, "auto")) {
            "on" -> true
            "off" -> false
            else -> vehicle.location?.let { Sun.isNight(it.latitude, it.longitude) } ?: false
        }
    }

    /** Launch saved app for [key]; if none saved/installed — ask once and remember. */
    fun launchAssigned(key: String, known: List<String>, title: String) {
        val pkg = Apps.resolve(activity, key, known)
        if (pkg != null && Apps.launch(activity, pkg)) return
        pick(title) { p -> Prefs.put(activity, key, p); settingsVersion++; Apps.launch(activity, p) }
    }

    /** Full-screen navigator (map card button, navigator hint). */
    fun openNavigator() = launchAssigned(Prefs.NAV, Known.NAV, "Выберите навигатор")
}

class MainActivity : ComponentActivity() {
    lateinit var state: LauncherState

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.vehicle.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        state = LauncherState(this)
        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent {
            // The whole UI is laid out in the units of the 1280×800 mockup: 800 units = screen height.
            // Width is flexible (1280×720 CC3 → ~1422 units), so proportions never break.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                CompositionLocalProvider(LocalDensity provides Density(h / DesignHeight, 1f)) {
                    // Launcher never exits on Back: it only closes drawers/settings.
                    BackHandler(enabled = true) {
                        when {
                            state.overlay != null -> state.overlay = null
                            state.tilesEdit -> state.tilesEdit = false
                            else -> state.go(Page.HOME)
                        }
                    }
                    LauncherRoot(state)
                }
            }
        }
        handleExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        Kiosk.startGuard(this)
        YandexMaps.onStart()
        state.media.start()
        state.vehicle.start()
        state.updater.checkDaily()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        state.media.start() // picks up freshly granted media access
        state.settingsVersion++
        if (!setupShown && state.overlay == null) {
            if (!Prefs.bool(this, Prefs.ONBOARDED, false)) { setupShown = true; state.overlay = Overlay.Welcome }
            // «Позже» on the setup screen snoozes the reminder for a day instead of nagging on every start
            else if (Permissions.missing(this).isNotEmpty() && System.currentTimeMillis() > (Prefs.str(this, Prefs.SETUP_SNOOZE, "0").toLongOrNull() ?: 0L)) {
                setupShown = true; state.overlay = Overlay.Setup
            }
        }
    }
    private var setupShown = false

    override fun onStop() {
        YandexMaps.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        state.media.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home key while already home → close drawers/settings (but not the first-run wizard).
        if (intent.hasCategory(Intent.CATEGORY_HOME) && state.overlay !is Overlay.Welcome) { state.overlay = null; state.go(Page.HOME) }
        handleExtras(intent)
    }

    private fun handleExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(Autostart.EXTRA_OPEN_NAV, false) == true) {
            intent.removeExtra(Autostart.EXTRA_OPEN_NAV)
            window.decorView.postDelayed({ state.openNavigator() }, 1500)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
