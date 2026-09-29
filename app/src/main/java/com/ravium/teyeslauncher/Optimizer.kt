package com.ravium.teyeslauncher

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps the head unit responsive without root or a computer:
 *  • frees memory by closing background processes of other apps (never the player, navigator, phone, keyboard…);
 *  • finds apps that start by themselves or haven't been opened for a month — one tap opens their Android page to stop/disable;
 *  • shows the system animation speed and leads to the place where it can be lowered to 0.5×.
 */
object Optimizer {
    data class Mem(val totalMb: Long, val availMb: Long, val low: Boolean) {
        val usedMb get() = totalMb - availMb
        val usedFraction get() = if (totalMb > 0) usedMb.toFloat() / totalMb else 0f
    }
    data class Candidate(val pkg: String, val label: String, val system: Boolean, val reason: String)

    /** Result of the last cleanup, shown in the Оптимизация screen. */
    @Volatile var lastResult: String? = null
        private set
    private var lastCleanAt = 0L
    private val main = Handler(Looper.getMainLooper())

    fun mem(ctx: Context): Mem {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Mem(mi.totalMem / 1_048_576, mi.availMem / 1_048_576, mi.lowMemory)
    }

    /** Free / total internal storage in GB. A nearly full eMMC is a classic cause of lag. */
    fun storage(): Pair<Float, Float> = runCatching {
        val st = StatFs(Environment.getDataDirectory().path)
        st.availableBytes / 1e9f to st.totalBytes / 1e9f
    }.getOrDefault(0f to 0f)

    /** Packages that must survive a cleanup: what the driver is using right now. */
    private fun keep(ctx: Context, sessions: Set<String>): Set<String> {
        val k = HashSet<String>()
        k += ctx.packageName
        k += sessions
        listOf(Prefs.NAV to Known.NAV, Prefs.PHONE to Known.PHONE, Prefs.CARLINK to Known.CARLINK, Prefs.BT_MUSIC to Known.BT_MUSIC)
            .forEach { (key, known) -> Apps.resolve(ctx, key, known)?.let { k += it } }
        k += Known.BT_SESSIONS
        k += Known.YANDEX_MUSIC
        Apps.resolve(ctx, Prefs.MAIN_PLAYER, Known.MAIN_PLAYER)?.let { k += it }
        (1..3).forEach { i -> Prefs.str(ctx, Prefs.DOCK + i)?.let { k += it } }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { k += it }
        LauncherGuard.history.firstOrNull()?.let { k += it }   // app on screen right now
        return k
    }

    /** Core parts of Android and the head unit — never touched, even though they are "system" apps. */
    private fun protectedPkg(p: String) = p == "android" || p.startsWith("com.android.") || p.startsWith("com.google.android.gms") ||
        p.startsWith("com.syu.") || p.startsWith("com.fyt.") || p.startsWith("com.teyes.") || p.contains("launcher") ||
        p.contains("inputmethod") || p.contains("bluetooth")

    /**
     * Close background processes of user apps (and non-core system apps). Android only allows closing
     * processes that aren't on screen and aren't playing, so nothing the driver sees is affected.
     */
    fun clean(ctx: Context, sessions: Set<String>, onDone: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        Thread {
            val before = mem(app).availMb
            val am = app.getSystemService(ActivityManager::class.java)
            val keep = keep(app, sessions)
            var n = 0
            app.packageManager.getInstalledApplications(0).forEach { ai ->
                val p = ai.packageName
                if (p in keep || protectedPkg(p)) return@forEach
                if (ai.flags and ApplicationInfo.FLAG_PERSISTENT != 0) return@forEach
                runCatching { am.killBackgroundProcesses(p); n++ }
            }
            System.gc()
            SystemClock.sleep(1500)
            val freed = (mem(app).availMb - before).coerceAtLeast(0)
            val msg = if (freed >= 5) "Освобождено $freed МБ" else "Память уже свободна"
            lastResult = msg; lastCleanAt = SystemClock.elapsedRealtime()
            main.post { onDone(msg) }
        }.start()
    }

    /** Called by the housekeeping loop: every 30 min, or sooner if memory is really short. */
    fun maybeAutoClean(ctx: Context, sessions: Set<String>) {
        if (!Prefs.bool(ctx, Prefs.AUTO_CLEAN, true)) return
        val since = SystemClock.elapsedRealtime() - lastCleanAt
        val m = mem(ctx)
        val due = lastCleanAt == 0L || since > 30 * 60_000L || (m.low || m.usedFraction > 0.88f) && since > 3 * 60_000L
        if (due) clean(ctx, sessions)
    }

    /** User-visible apps that start on boot by themselves. Heavy — call off the main thread. */
    fun autostartApps(ctx: Context): List<Candidate> {
        val pm = ctx.packageManager
        val keep = keep(ctx, emptySet())
        val launchable = launchable(ctx)
        return pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it in launchable && it !in keep && !protectedPkg(it) }
            .mapNotNull { p -> info(ctx, p)?.let { Candidate(p, Apps.label(ctx, p), it.isSystem(), "запускается при включении") } }
            .sortedBy { it.label.lowercase() }
    }

    /** Apps with an icon that weren't opened for 30 days (needs usage access). */
    fun unusedApps(ctx: Context): List<Candidate> {
        if (!Kiosk.hasUsageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val used = usm.queryAndAggregateUsageStats(now - 30L * 86_400_000, now)
            .filterValues { it.totalTimeInForeground > 0 || it.lastTimeUsed > now - 30L * 86_400_000 }.keys
        val keep = keep(ctx, emptySet())
        return launchable(ctx)
            .filter { it !in used && it !in keep && !protectedPkg(it) }
            .mapNotNull { p ->
                val ai = info(ctx, p) ?: return@mapNotNull null
                val installedLongAgo = runCatching { ctx.packageManager.getPackageInfo(p, 0).firstInstallTime < now - 7L * 86_400_000 }.getOrDefault(true)
                if (!installedLongAgo) null else Candidate(p, Apps.label(ctx, p), ai.isSystem(), "не открывалось 30 дней")
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    private fun launchable(ctx: Context): Set<String> = ctx.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.toSet()

    private fun info(ctx: Context, p: String): ApplicationInfo? = runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.getOrNull()
    private fun ApplicationInfo.isSystem() = flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    /** Android page of an app: «Остановить», «Отключить» / «Удалить». */
    fun openApp(ctx: Context, pkg: String) =
        Apps.openFirst(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    /** Current system animation speed (1 = default). 0.5 makes every screen feel faster. */
    fun animationScale(ctx: Context): Float = runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
    }.getOrDefault(1f)

    fun developerOptionsOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun openDeveloperOptions(ctx: Context): Boolean = Apps.openFirst(ctx,
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openAboutPhone(ctx: Context): Boolean = Apps.openFirst(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
