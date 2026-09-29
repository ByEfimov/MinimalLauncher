package com.ravium.teyeslauncher
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Screenshot harness. Every shot starts from a READY launcher (seed below): onboarding done, setup snoozed,
 * name, home, favourites, reminders, odometer — so no first-run screens. One class per shot (forkEvery=1 → clean JVM).
 * Run: tools/screenshots/shots.sh Tiles Settings …  → <out>/<Name>.png
 */
object Seed {
    fun apply(ctx: android.content.Context) {
        Prefs.put(ctx, Prefs.ONBOARDED, true)
        Prefs.put(ctx, Prefs.SETUP_SNOOZE, Long.MAX_VALUE.toString())
        Prefs.put(ctx, Prefs.USER_NAME, "Никита")
        Prefs.put(ctx, "home_place", Place("Дом", "ул. Ленина, 1", 55.75, 37.62).toJson())
        Prefs.put(ctx, Prefs.ODO_BASE, "123400"); Prefs.put(ctx, Prefs.ODO_BASE_GPS, "0")
        Prefs.put(ctx, Prefs.REMINDERS, """[{"id":1,"t":"Замена масла","km":true,"ik":10000,"lk":114000},{"id":2,"t":"ОСАГО","km":false,"due":${System.currentTimeMillis() + 9L * 86400000},"im":12}]""")
        com.ravium.teyeslauncher.ui.InstantUi = true
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
abstract class Shot(private val name: String, private val setup: (MainActivity) -> Unit) {
    @Test fun shot() {
        Seed.apply(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch(MainActivity::class.java).onActivity { a ->
            a.state.overlay = null
            repeat(2) { ShadowLooper.idleMainLooper() }
            setup(a)
            repeat(3) { ShadowLooper.idleMainLooper() }
            a.window.decorView.captureRoboImage((System.getProperty("shots.dir") ?: "build/shots") + "/$name.png")
        }
    }
}

class SHome : Shot("Home", {})
class STiles : Shot("Tiles", { it.state.go(Page.TILES) })
class STilesEdit : Shot("TilesEdit", { it.state.go(Page.TILES); it.state.tilesEdit = true })
class SReminders : Shot("Reminders", { it.state.go(Page.REMINDERS) })
class SOptimize : Shot("Optimize", { it.state.go(Page.OPTIMIZE) })
class SParking : Shot("Parking", { it.state.go(Page.HOME); it.state.parked = true })
class SSettings : Shot("Settings", { it.state.overlay = Overlay.Settings })
class SSettingsMap : Shot("SettingsMap", { it.state.overlay = Overlay.SettingsCat("map") })
class SSettingsVoice : Shot("SettingsVoice", { it.state.overlay = Overlay.SettingsCat("voice") })
class SDock : Shot("Dock", { it.state.overlay = Overlay.Dock })
class SWheel : Shot("Wheel", { it.state.overlay = Overlay.Wheel })
class SCatalog : Shot("Catalog", { it.state.overlay = Overlay.TileCatalog })
class SRemEdit : Shot("RemEdit", { it.state.overlay = Overlay.ReminderEdit(null, 0) })
class SWeather : Shot("Weather", { it.state.overlay = Overlay.Weather })
class SHomeCustom : Shot("HomeCustom", { Prefs.put(it, Prefs.HOME_BLOCKS, "music,tile=weather,tile=volume"); Prefs.put(it, Prefs.HOME_SIDE, "right"); it.state.settingsVersion++ })
class SHomeLayoutEditor : Shot("HomeLayoutEditor", { it.state.overlay = Overlay.HomeLayout })
