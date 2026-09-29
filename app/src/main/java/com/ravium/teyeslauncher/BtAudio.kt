package com.ravium.teyeslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Bluetooth music on TEYES / FYT head units.
 *
 * The phone's A2DP audio is played by the head unit's own Bluetooth app, and the car only switches its
 * audio channel to Bluetooth when that app's music screen is opened (this is exactly what the stock TEYES
 * launcher does). So "activate" briefly opens the BT-music screen and brings Minimal Drive back.
 * The screen is found automatically (by name/label); it can be overridden in Диагностика.
 */
object BtAudio {
    data class Target(val pkg: String, val cls: String?, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private var lastActivation = 0L

    private val BT_WORD = Regex("bluetooth|блютуз|bt|a2dp", RegexOption.IGNORE_CASE)
    private val MUSIC_WORD = Regex("music|музык|audio|аудио|a2dp|av\\b|плеер", RegexOption.IGNORE_CASE)
    private val MUSIC_CLASS = Regex("(av|music|a2dp|audio|player)", RegexOption.IGNORE_CASE)
    private val VENDOR = Regex("^(com\\.syu|com\\.fyt|com\\.teyes|com\\.spd|com\\.sprd|com\\.android\\.bluetooth)", RegexOption.IGNORE_CASE)

    /** Best guess for the BT-music screen on this unit. */
    fun find(ctx: Context): Target? {
        val pm = ctx.packageManager
        // 0. user override (package or package/class)
        Prefs.str(ctx, Prefs.BT_MUSIC)?.let { saved ->
            val pkg = saved.substringBefore('/'); val cls = saved.substringAfter('/', "").ifEmpty { null }
            if (Apps.installed(ctx, pkg)) return Target(pkg, cls, Apps.label(ctx, pkg))
        }
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        // 1. a launcher icon like "BT Music" / "Bluetooth музыка"
        launchers.firstOrNull {
            val label = it.loadLabel(pm).toString()
            BT_WORD.containsMatchIn(label) && MUSIC_WORD.containsMatchIn(label)
        }?.let { return Target(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString()) }
        // 2. a music activity inside a vendor Bluetooth package
        val btPkgs = (Known.BT_MUSIC + launchers.map { it.activityInfo.packageName }
            .filter { VENDOR.containsMatchIn(it) && BT_WORD.containsMatchIn(it + " " + Apps.label(ctx, it)) }).distinct()
        for (pkg in btPkgs) {
            val acts = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities }.getOrNull() ?: continue
            acts.firstOrNull { it.exported && MUSIC_CLASS.containsMatchIn(it.name.substringAfterLast('.')) }
                ?.let { return Target(pkg, it.name, Apps.label(ctx, pkg)) }
        }
        // 3. just the Bluetooth app
        btPkgs.firstOrNull { Apps.installed(ctx, it) }?.let { return Target(it, null, Apps.label(ctx, it)) }
        return null
    }

    /** Candidates for the diagnostics screen. */
    fun describe(ctx: Context): String = find(ctx)?.let { "${it.label} (${it.pkg}${it.cls?.let { c -> "/" + c.substringAfterLast('.') } ?: ""})" } ?: "не найдено"

    private fun intentFor(ctx: Context, t: Target): Intent? =
        (if (t.cls != null) Intent(Intent.ACTION_MAIN).setComponent(ComponentName(t.pkg, t.cls)) else Apps.launchIntent(ctx, t.pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /**
     * Switch the car's audio to Bluetooth: open the BT-music screen for a moment, then return home.
     * Skipped if done in the last few seconds. Returns false if no BT app was found.
     */
    fun activate(ctx: Context, stayInApp: Boolean = false): Boolean {
        val t = find(ctx) ?: return false
        val now = SystemClock.elapsedRealtime()
        if (!stayInApp && now - lastActivation < 4000) return true
        val i = intentFor(ctx, t) ?: return false
        if (runCatching { ctx.startActivity(i) }.isFailure) {
            // explicit activity not startable → fall back to the app itself
            val li = Apps.launchIntent(ctx, t.pkg) ?: return false
            if (runCatching { ctx.startActivity(li) }.isFailure) return false
        }
        lastActivation = now
        if (!stayInApp) main.postDelayed({
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }, 1200)
        return true
    }

    fun packageName(ctx: Context): String? = find(ctx)?.pkg
}
