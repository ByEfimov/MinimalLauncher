package com.ravium.teyeslauncher

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Activation lock — works fully OFFLINE, no internet needed on the head unit.
 *
 *  • the head unit shows a 6-digit DEVICE code (from its Android ID);
 *  • the owner turns it into a 6-digit ACTIVATION code (offline generator / Mac script);
 *  • the head unit checks it locally and unlocks forever. A code from one head unit does not fit another.
 *
 * The same secret is used by the app and by the owner's generator, so all of them must compute the code
 * the SAME way: unsigned big-endian of the first 8 HMAC bytes, mod 1_000_000.
 */
object License {
    // Shared secret, XOR-obfuscated so it isn't a plain string in the APK.
    private const val OBF_B64 = "VOBG/0HVZT3p02dIxKhhW69Z103AH1hNMQi13yHKwP8="
    private val PAD = byteArrayOf(0x9e.toByte(), 0x3c, 0x7f, 0xa1.toByte(), 0x5b, 0x2d, 0x84.toByte(), 0xc6.toByte())
    private val secret: ByteArray by lazy {
        Base64.decode(OBF_B64, Base64.DEFAULT).mapIndexed { i, b -> (b.toInt() xor PAD[i % PAD.size].toInt()).toByte() }.toByteArray()
    }
    private val MILLION = BigInteger.valueOf(1_000_000)
    private fun sixDigits(bytes: ByteArray): String =
        BigInteger(1, bytes.copyOfRange(0, 8)).mod(MILLION).toString().padStart(6, '0')

    var activated by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)   // last entered code was wrong
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        activated = matches(deviceId(app), Prefs.str(app, Prefs.LICENSE))
    }

    /** 6-digit code of this head unit, e.g. "821423". */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "") + "|" + ctx.packageName
        return sixDigits(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))
    }

    /** The 6-digit activation code that matches [device] — same formula the generator / Mac script use. */
    fun codeFor(device: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return sixDigits(mac.doFinal(device.toByteArray()))
    }

    private fun matches(device: String, code: String?): Boolean {
        val c = code?.trim()?.filter { it.isDigit() } ?: return false
        return c.length == 6 && constEq(c, codeFor(device))
    }

    private fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0; for (i in a.indices) r = r or (a[i].code xor b[i].code); return r == 0
    }

    /** Apply a 6-digit code the owner gave. Returns true on success. */
    fun applyCode(code: String): Boolean {
        val c = code.trim().filter { it.isDigit() }
        if (c.length < 6) { error = false; return false }
        val ok = matches(deviceId(app), c)
        error = !ok
        if (ok) { Prefs.putNow(app, Prefs.LICENSE, c); activated = true }
        return ok
    }
}
