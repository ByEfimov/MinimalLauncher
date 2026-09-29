package com.ravium.teyeslauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Special accesses the launcher needs. Each has several ways to open its screen (firmwares differ —
 * e.g. Android Automotive has no "Display over other apps" page) and an adb fallback.
 */
object Permissions {
    data class Step(val id: String, val title: String, val why: String, val done: Boolean, val adb: String, val open: (Context) -> Boolean)

    private const val PKG = "com.ravium.teyeslauncher"
    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun openOverlay(ctx: Context): Boolean = Apps.openFirst(ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(ctx)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)),
    )

    fun openUsage(ctx: Context): Boolean = Apps.openFirst(ctx,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri(ctx)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openNotifications(ctx: Context): Boolean = Apps.openFirst(ctx,
        Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", "$PKG/$PKG.MediaListener"),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    fun openAppDetails(ctx: Context): Boolean = Apps.openFirst(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)))

    fun steps(ctx: Context): List<Step> {
        val perms = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return listOf(
            Step("home", "Лаунчер по умолчанию", "Кнопка «Домой» всегда открывает Minimal Drive", Kiosk.isDefaultHome(ctx),
                "adb shell cmd package set-home-activity $PKG/.MainActivity") { Kiosk.openHomeSettings(it); true },
            Step("usage", "Доступ к истории использования", "Не даёт уйти в штатный лаунчер", Kiosk.hasUsageAccess(ctx),
                "adb shell appops set $PKG GET_USAGE_STATS allow") { openUsage(it) },
            Step("overlay", "Поверх других окон", "Автозапуск и возврат в лаунчер", Settings.canDrawOverlays(ctx),
                "adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow") { openOverlay(it) },
            Step("notif", "Доступ к уведомлениям", "Музыка и подсказки навигатора", MediaRepo.instance?.isListenerEnabled() == true,
                "adb shell cmd notification allow_listener $PKG/$PKG.MediaListener") { openNotifications(it) },
            Step("perm", "Геопозиция", "Карта, скорость и ограничения скорости", perms,
                "adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION") { c -> requestLocation(c) },
        )
    }

    /** System permission dialog first; app settings if the user already refused with "don't ask again". */
    private fun requestLocation(ctx: Context): Boolean {
        val act = ctx as? android.app.Activity ?: return openAppDetails(ctx)
        val asked = Prefs.bool(ctx, "loc_asked", false)
        if (asked && !act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) return openAppDetails(ctx)
        Prefs.put(ctx, "loc_asked", true)
        act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        return true
    }

    fun missing(ctx: Context) = steps(ctx).filter { !it.done }

    /** Everything in one paste for a terminal. */
    fun allAdb(ctx: Context) = steps(ctx).filter { !it.done }.joinToString("\n") { it.adb }
}
