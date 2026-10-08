package com.ravium.teyeslauncher

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings

/** Helpers for "real launcher" mode. */
object Kiosk {
    fun enabled(ctx: Context) = Prefs.bool(ctx, Prefs.KIOSK, true)

    fun isDefaultHome(ctx: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Other HOME apps (stock TEYES launcher etc.). Settings' FallbackHome is ignored. */
    fun otherLaunchers(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
            .filter { it != ctx.packageName && it != "com.android.settings" }.toSet()
    }

    fun openHomeSettings(ctx: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    fun bringHome(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    fun startGuard(ctx: Context) {
        runCatching { ctx.startForegroundService(Intent(ctx, LauncherGuard::class.java)) }
    }

    /**
     * Remove the launcher and return the head unit to its stock behaviour:
     *  1. stop kiosk + the guard service and turn off autostart, so nothing pulls Minimal Drive back;
     *  2. open the Home-app chooser so the user can pick the stock launcher as default;
     *  3. start the system uninstall dialog for Minimal Drive.
     * (Android does not let an app silently uninstall itself — the user confirms the last step.)
     */
    fun uninstallAndRevert(ctx: Context) {
        Prefs.put(ctx, Prefs.KIOSK, false)
        Prefs.put(ctx, Prefs.AUTO_NAV, false)
        Prefs.put(ctx, Prefs.AUTO_PLAY, false)
        runCatching { ctx.stopService(Intent(ctx, LauncherGuard::class.java)) }
        // let the user set the stock launcher as home first
        runCatching { openHomeSettings(ctx) }
        // then ask to uninstall
        val i = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}

/** What runs when the head unit wakes up (ACC on) or boots. */
object Autostart {
    const val EXTRA_OPEN_NAV = "open_nav"
    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context) {
        val app = ctx.applicationContext
        if (Kiosk.enabled(app)) Kiosk.bringHome(app)
        LauncherState.current?.vehicle?.resetTrip()   // new trip starts with the ignition
        if (Prefs.bool(app, Prefs.AUTO_PLAY, false)) main.postDelayed({
            // Give Bluetooth / the player a few seconds to come back, then continue what was playing.
            val am = app.getSystemService(android.media.AudioManager::class.java)
            if (Prefs.str(app, Prefs.SOURCE, "YANDEX") == "BLUETOOTH" && Prefs.bool(app, Prefs.BT_OPEN_APP, false)) { BtAudio.activate(app); return@postDelayed }
            if (!am.isMusicActive) runCatching {
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            }
        }, 6000)
        // after ignition: once the player has resumed, clear what piled up in the background
        if (Prefs.bool(app, Prefs.AUTO_CLEAN, true)) main.postDelayed({ Optimizer.clean(app, emptySet()) }, 15000)
        if (Prefs.bool(app, Prefs.AUTO_NAV, false)) main.postDelayed({
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java).putExtra(EXTRA_OPEN_NAV, true).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }, 3000)
    }
}

/**
 * Background service:
 *  • keeps Minimal Drive in front — if the stock launcher appears, Minimal Drive comes back within ~0.5 s;
 *  • runs [Autostart] when the screen wakes after ACC off.
 * Needs "Usage access" to see the foreground app and "Display over other apps" to start from background.
 */
class LauncherGuard : Service() {
    companion object {
        /** Last foreground packages, newest first — shown in diagnostics. */
        val history = ArrayDeque<String>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastQuery = 0L
    private var foreground: String? = null
    private var others: Set<String> = emptySet()
    private var othersAt = 0L
    private var screenOffAt = 0L

    private val loop = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 500)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = System.currentTimeMillis()
                Intent.ACTION_SCREEN_ON -> if (screenOffAt != 0L && System.currentTimeMillis() - screenOffAt > 30_000) Autostart.run(c)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guard", "Minimal Drive", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "guard")
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle("Minimal Drive работает")
            .setOngoing(true)
            .build()
        startForeground(1, n)
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        lastQuery = System.currentTimeMillis() - 2000
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun check() {
        if (!Kiosk.hasUsageAccess(this)) return
        val now = System.currentTimeMillis()
        if (now - othersAt > 60_000) { others = Kiosk.otherLaunchers(this); othersAt = now }
        val usm = getSystemService(UsageStatsManager::class.java)
        val events = runCatching { usm.queryEvents(lastQuery, now) }.getOrNull() ?: return
        lastQuery = now
        val e = UsageEvents.Event()
        var changed = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != foreground) {
                foreground = e.packageName; changed = true
                history.remove(e.packageName); history.addFirst(e.packageName)
                while (history.size > 8) history.removeLast()
            }
        }
        val fg = foreground ?: return
        if (Kiosk.enabled(this) && fg in others) {
            foreground = packageName
            Kiosk.bringHome(this)
        }
    }
}

/** Start after reboot / quick-boot (TEYES), and after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Kiosk.startGuard(ctx)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { if (Kiosk.enabled(ctx)) Kiosk.bringHome(ctx) }
        else Autostart.run(ctx)
    }
}
