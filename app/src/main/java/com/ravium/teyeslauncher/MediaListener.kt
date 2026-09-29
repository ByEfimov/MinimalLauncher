package com.ravium.teyeslauncher

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.drawable.toBitmap

/** Current guidance from the navigator's ongoing notification (next manoeuvre, distance, ETA). */
object NavInfo {
    data class Hint(val pkg: String, val title: String, val text: String, val sub: String, val icon: Bitmap?)

    var hint by mutableStateOf<Hint?>(null)
        private set

    val NAV_PACKAGES = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "ru.dublgis.dgismobile", "com.google.android.apps.maps", "com.waze")

    fun update(h: Hint?) { hint = h }
}

/**
 * Lets Android hand us media sessions (Яндекс Музыка, Bluetooth) and navigator notifications.
 * Access: «Настройка магнитолы» → «Доступ к уведомлениям».
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaRepo.instance?.restart()
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isNav(sbn)) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val sub = (ex.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: ex.getCharSequence(Notification.EXTRA_INFO_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val icon: Bitmap? = runCatching {
            val ic: Icon? = n.getLargeIcon()
            ic?.loadDrawable(this)?.toBitmap(96, 96)
        }.getOrNull()
        NavInfo.update(NavInfo.Hint(sbn.packageName, title, text, sub, icon))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isNav(sbn) && NavInfo.hint?.pkg == sbn.packageName) NavInfo.update(null)
    }

    private fun isNav(sbn: StatusBarNotification): Boolean {
        val saved = Prefs.str(this, Prefs.NAV)
        return (sbn.packageName in NavInfo.NAV_PACKAGES || sbn.packageName == saved) && sbn.isOngoing
    }
}
