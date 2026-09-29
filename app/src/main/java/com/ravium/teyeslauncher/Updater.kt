package com.ravium.teyeslauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Over-the-air updates from GitHub Releases of [BuildConfig.UPDATE_REPO] ("owner/repo", set in gradle.properties).
 * A release is newer if its tag (v0.9.0) is higher than versionName; its first .apk asset is installed.
 * APKs must be signed with the same key (keystore/minimal-drive.jks in the project).
 */
class Updater(private val ctx: Context) {
    data class Release(val version: String, val apkUrl: String, val notes: String)

    var status by mutableStateOf(if (BuildConfig.UPDATE_REPO.isBlank()) "Репозиторий не задан (gradle.properties → updateRepo)" else "")
        private set
    var available by mutableStateOf<Release?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun checkDaily() {
        val last = ctx.getSharedPreferences("minimal_drive", Context.MODE_PRIVATE).getLong("update_checked", 0)
        if (System.currentTimeMillis() - last > 6 * 3600_000L) check(silent = true)   // every 6 h
    }

    fun check(silent: Boolean = false) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank() || busy) return
        busy = true
        if (!silent) status = "Проверка…"
        io.execute {
            val result = runCatching {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val j = JSONObject(body)
                val tag = j.getString("tag_name").removePrefix("v")
                val assets = j.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                    ?: error("в релизе нет APK")
                Release(tag, apk, j.optString("body"))
            }
            main.post {
                busy = false
                ctx.getSharedPreferences("minimal_drive", Context.MODE_PRIVATE).edit().putLong("update_checked", System.currentTimeMillis()).apply()
                result.onSuccess { r ->
                    if (newer(r.version, BuildConfig.VERSION_NAME)) { available = r; status = "Доступна версия ${r.version}" }
                    else { available = null; status = "Установлена последняя версия (${BuildConfig.VERSION_NAME})" }
                }.onFailure { if (!silent) status = "Не удалось проверить: ${it.message}" }
            }
        }
    }

    fun install() {
        val r = available ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "Разрешите установку из этого источника и нажмите ещё раз"
            Apps.openFirst(ctx, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return
        }
        busy = true
        status = "Загрузка ${r.version}…"
        io.execute {
            val res = runCatching {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    var url = URL(r.apkUrl)
                    var c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 10000; c.readTimeout = 30000
                    // GitHub redirects to its CDN
                    if (c.responseCode in 300..399) { url = URL(c.getHeaderField("Location")); c.disconnect(); c = url.openConnection() as HttpURLConnection }
                    c.inputStream.use { input ->
                        session.openWrite("update.apk", 0, -1).use { out -> input.copyTo(out); session.fsync(out) }
                    }
                    c.disconnect()
                    val intent = Intent(ctx, InstallResultReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                    session.commit(pending.intentSender)
                }
            }
            main.post {
                busy = false
                status = res.fold({ "Установка ${r.version}…" }, { "Ошибка загрузки: ${it.message}" })
            }
        }
    }

    private fun newer(a: String, b: String): Boolean {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Shows the system "Install update?" confirmation when Android asks for it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Apps.toast(ctx, "Обновление не установлено: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
