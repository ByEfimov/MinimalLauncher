package com.ravium.teyeslauncher

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Activation lock. A copied APK is useless without a 6-digit activation code that only the owner can issue.
 *
 *  • each head unit shows a 6-digit DEVICE code (from its Android ID);
 *  • the ACTIVATION code is a short HMAC of that device code, made with a secret only the owner has
 *    (Mac script / activation page);
 *  • the app checks it offline — no internet after activation, and a code from one head unit does not fit another.
 *
 * Trade-off vs. the previous RSA scheme: a 6-digit code needs a shared secret, so the secret lives in the app
 * (lightly obfuscated). A determined attacker who unpacks the APK could extract it — good enough to stop casual
 * copying, weaker than a server-only key. The activation service (tools/license) also holds the same secret.
 */
object License {
    // Shared secret, XOR-obfuscated so it isn't a plain string in the APK. Owner keeps the raw secret out of the repo.
    private const val OBF_B64 = "VOBG/0HVZT3p02dIxKhhW69Z103AH1hNMQi13yHKwP8="
    private val PAD = byteArrayOf(0x9e.toByte(), 0x3c, 0x7f, 0xa1.toByte(), 0x5b, 0x2d, 0x84.toByte(), 0xc6.toByte())
    private val secret: ByteArray by lazy {
        Base64.decode(OBF_B64, Base64.DEFAULT).mapIndexed { i, b -> (b.toInt() xor PAD[i % PAD.size].toInt()).toByte() }.toByteArray()
    }

    var activated by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        activated = check(deviceId(app), Prefs.str(app, Prefs.LICENSE))
    }

    /** 6-digit code of this head unit, e.g. "373039". */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "") + "|" + ctx.packageName
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val n = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xff) }
        return "%06d".format((n % 1_000_000 + 1_000_000) % 1_000_000)
    }

    /** The 6-digit activation code that matches [device] (same formula the Mac script / page uses). */
    fun codeFor(device: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val d = mac.doFinal(device.toByteArray())
        val n = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (d[i].toLong() and 0xff) }
        return "%06d".format((n % 1_000_000 + 1_000_000) % 1_000_000)
    }

    private fun check(device: String, code: String?): Boolean {
        val c = code?.trim()?.filter { it.isDigit() } ?: return false
        return c.length == 6 && constEq(c, codeFor(device))
    }

    /** Constant-time compare, so timing doesn't leak the code. */
    private fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0; for (i in a.indices) r = r or (a[i].code xor b[i].code); return r == 0
    }

    /** Apply a 6-digit code the owner gave. */
    fun applyCode(code: String): Boolean {
        val c = code.trim().filter { it.isDigit() }
        val ok = check(deviceId(app), c)
        if (ok) { Prefs.putNow(app, Prefs.LICENSE, c); activated = true; status = "Активировано" }
        else status = "Код не подходит для этой магнитолы"
        return ok
    }

    /** Ask the owner's activation service whether this device was approved, then store its code. */
    fun checkOnline(onDone: (Boolean) -> Unit = {}) {
        val base = BuildConfig.ACTIVATION_URL
        if (base.isBlank()) { status = "Введите код активации, полученный у владельца"; onDone(false); return }
        if (busy) return
        busy = true; status = "Проверяю активацию…"
        Thread {
            val device = deviceId(app)
            val ok = runCatching {
                val c = (URL("$base/check?device=$device").openConnection() as HttpURLConnection)
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) { c.disconnect(); return@runCatching false }
                val body = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect()
                val code = JSONObject(body).optString("code")
                check(device, code).also { if (it) Prefs.putNow(app, Prefs.LICENSE, code) }
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
