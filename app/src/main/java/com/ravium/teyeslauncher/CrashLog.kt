package com.ravium.teyeslauncher

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crashes in a small file, so a user can send them from Диагностика
 * («Отправить отчёт») — you see why it failed on someone else's head unit.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crashes.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(app, "CRASH in ${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** Also for handled errors worth reporting. */
    fun write(ctx: Context, title: String, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "=== $stamp · v${BuildConfig.VERSION_NAME} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n$title\n${sw.toString().take(6000)}\n"
        val f = file(ctx)
        val old = runCatching { f.readText() }.getOrDefault("")
        f.writeText((entry + old).take(40_000))   // newest first, capped
    }

    fun read(ctx: Context): String = runCatching { file(ctx).readText() }.getOrDefault("")
    fun count(ctx: Context): Int = read(ctx).split("\n=== ").count { it.isNotBlank() }.let { if (read(ctx).isBlank()) 0 else it }
    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }
}
