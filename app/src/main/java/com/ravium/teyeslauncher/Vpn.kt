package com.ravium.teyeslauncher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

/**
 * «Обход белых списков» через стороннее приложение-VPN (Happ, v2rayNG, Hiddify…).
 *
 * Собственный туннель лаунчер не поднимает — это отдельное большое приложение. Мы делаем удобно:
 * кнопка на главном открывает приложение-обход (и показывает, включён ли VPN), а подписку можно
 * один раз импортировать в Happ. Android не разрешает одному приложению включать VPN другого —
 * поэтому само подключение пользователь делает в приложении-обходе (один тап).
 *
 * Ссылка-подписка хранится ТОЛЬКО на устройстве (Prefs), в исходники/гит не попадает.
 */
object Vpn {
    fun app(ctx: Context): String? = Apps.resolve(ctx, Prefs.VPN_APP, Known.VPN)
    fun installed(ctx: Context): Boolean = app(ctx) != null
    fun sub(ctx: Context): String = Prefs.str(ctx, Prefs.VPN_SUB)?.trim().orEmpty()

    /** Включён ли сейчас какой-либо VPN на магнитоле. */
    fun active(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    /** Открыть приложение-обход. true — получилось. */
    fun open(ctx: Context): Boolean {
        val pkg = app(ctx) ?: return false
        return runCatching { Apps.launchIntent(ctx, pkg)?.let { ctx.startActivity(it); true } ?: false }.getOrDefault(false)
    }

    /** Импорт подписки: пробуем deep link Happ, иначе кладём ссылку в буфер и открываем приложение. */
    fun importSub(ctx: Context) {
        val s = sub(ctx)
        if (s.isBlank()) { Apps.toast(ctx, "Сначала вставьте ссылку-подписку в настройках обхода"); return }
        val pkg = app(ctx)
        if (pkg != null) {
            val tries = listOf("happ://add/" + Uri.encode(s), "happ://addsub/" + Uri.encode(s), s)
            for (u in tries) {
                val ok = runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
                if (ok) { Apps.toast(ctx, "Открываю приложение для импорта подписки…"); return }
            }
        }
        runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sub", s)) }
        Apps.toast(ctx, "Ссылка скопирована — вставьте её в приложении (＋ → из буфера)")
        open(ctx)
    }

    /** Тап по кнопке на главном: открыть приложение-обход; если не выбрано — открыть настройки обхода. */
    fun onTap(s: LauncherState) {
        if (installed(s.activity)) open(s.activity) else s.overlay = Overlay.Vpn
    }

    fun openSite(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://happ.su/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
