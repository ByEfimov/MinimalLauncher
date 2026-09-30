package com.ravium.teyeslauncher

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Activation lock. A copied APK is useless without an activation code that only the owner can issue.
 *
 * How it stays safe:
 *  • each head unit has a device code (from its Android ID);
 *  • an activation code is an RSA signature of that device code, made with a PRIVATE key the owner keeps
 *    (on a page / on the Mac) and NEVER ships in the app;
 *  • the app only carries the PUBLIC key and checks the signature offline — it cannot mint codes itself,
 *    and a code from one head unit does not fit another.
 * After activation everything works with no internet; the network is used only to fetch the code the first time.
 */
object License {
    // Public key only (RSA-2048, X.509 DER, base64). The matching private key is the owner's secret.
    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtW3S8KUT1a6yanncplDK5OeH+E9ZBtOZhdIz9nVpuRWSmn6RW71tp1uwSGh6UHwBuCeppdhDZBNoD9SpWtdRQGYK0JHXDZv6C4gHQxh7ud6tW8WEWupwMnYIxuy20W/eQwuoUjkfH5EhYbv3naHUvdeL4bV/ypVYhXbtnRODCvZ79TzxtQ9bnzTC/sAXC0nt+P1KcMOK6sg2l9pz/Z/EYRWfjg2YoIc9HdZ0YEMWIap7fQnqzAsCEaJBjk5i1fz+FaMgVv5J1IglLgJjdBYQ+xDyf2h00fe7OrQU1ngNFB4LfCrvDi0tU15ycpesFrgR/NazFsNkGttgj0/1vg661QIDAQAB"

    var activated by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        activated = verify(deviceId(app), Prefs.str(app, Prefs.LICENSE))
    }

    /** Stable per–head-unit code, e.g. "7F3A-91C0-5E22". Shown to the owner to approve. */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "") + "|" + ctx.packageName
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return h.take(6).joinToString("") { "%02X".format(it) }
    }

    fun deviceCodePretty(ctx: Context): String = deviceId(ctx).chunked(4).joinToString("-")

    /** True when [code] (base64 RSA signature) is a valid activation for this [device]. Offline, no network. */
    private fun verify(device: String, code: String?): Boolean {
        val sig = code?.trim()?.replace("\n", "")?.takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key); update(device.toByteArray()); verify(Base64.decode(sig, Base64.DEFAULT))
            }
        }.getOrDefault(false)
    }

    /** Try an activation code the owner gave (pasted). Stores it and unlocks on success. */
    fun applyCode(code: String): Boolean {
        val ok = verify(deviceId(app), code)
        if (ok) { Prefs.putNow(app, Prefs.LICENSE, code.trim()); activated = true; status = "Активировано" }
        else status = "Код не подходит для этой магнитолы"
        return ok
    }

    /**
     * Ask the owner's activation service whether this device was approved, and if so fetch + store the code.
     * The service only returns a code the owner already signed with the private key, so this can't be spoofed.
     */
    fun checkOnline(onDone: (Boolean) -> Unit = {}) {
        val base = BuildConfig.ACTIVATION_URL
        if (base.isBlank()) { status = "Адрес активации не задан — введите код вручную"; onDone(false); return }
        if (busy) return
        busy = true; status = "Проверяю активацию…"
        Thread {
            val device = deviceId(app)
            val ok = runCatching {
                val c = (URL("$base/check?device=$device").openConnection() as HttpURLConnection)
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) { c.disconnect(); return@runCatching false }
                val body = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
                val sig = JSONObject(body).optString("sig")
                sig.isNotEmpty() && verify(device, sig).also { if (it) Prefs.putNow(app, Prefs.LICENSE, sig) }
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                activated = activated || ok
                status = if (ok) "Активировано" else "Пока не подтверждено. Попросите владельца одобрить магнитолу и нажмите ещё раз."
                onDone(ok)
            }
        }.start()
    }
}
