package com.ravium.teyeslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.asImageBitmap

enum class Source { YANDEX, BLUETOOTH }

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val artUri: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val liked: Boolean,
    val hasSession: Boolean,
)

/**
 * Player for the music card. Reads media sessions through the notification listener and
 * filters them by the selected source, so the card shows either Яндекс Музыка or Bluetooth.
 */
class MediaRepo(private val ctx: Context) {
    companion object { var instance: MediaRepo? = null }

    var source by mutableStateOf(runCatching { Source.valueOf(Prefs.str(ctx, Prefs.SOURCE, "YANDEX")) }.getOrDefault(Source.YANDEX))
        private set
    var hasAccess by mutableStateOf(false)
        private set
    /** Bumped on any session/metadata/playback change to trigger recomposition. */
    var version by mutableIntStateOf(0)
        private set

    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListener::class.java)
    private var listening = false
    private var likedLocal = HashMap<String, Boolean>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list ?: emptyList()) }

    init { instance = this }

    fun isListenerEnabled(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun start() {
        hasAccess = isListenerEnabled()
        if (!hasAccess || listening) { if (listening) refresh(); return }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            listening = true
            refresh()
        } catch (_: SecurityException) { hasAccess = false }
    }

    fun restart() = main.post { stop(); start() }

    fun stop() {
        if (listening) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        setControllers(emptyList())
    }

    private fun refresh() {
        try { setControllers(msm.getActiveSessions(component)) } catch (_: SecurityException) { hasAccess = false }
    }

    private fun setControllers(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        controllers = list
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { onStateChanged(c, state); version++ }
                override fun onMetadataChanged(metadata: MediaMetadata?) { version++ }
                override fun onSessionDestroyed() { refresh() }
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
        }
        version++
    }

    private fun active(st: Int?) = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING ||
        st == PlaybackState.STATE_CONNECTING || st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING

    /** Pause every session that belongs to the other tab. */
    private fun pauseOthers(s: Source) {
        controllers.filter { !inSource(it.packageName, s) && it.packageName != ctx.packageName && active(it.playbackState?.state) }
            .forEach { runCatching { it.transportControls.pause() } }
    }

    /**
     * Two separate players: whatever starts playing (steering-wheel keys, the phone, the Yandex app) becomes the
     * selected tab, and the other tab's player is paused.
     */
    private fun onStateChanged(c: MediaController, st: PlaybackState?) {
        if (st?.state != PlaybackState.STATE_PLAYING || c.packageName == ctx.packageName) return
        val s = if (c.packageName in Known.YANDEX_MUSIC) Source.YANDEX else Source.BLUETOOTH
        if (s != source) { source = s; Prefs.put(ctx, Prefs.SOURCE, s.name) }
        pauseOthers(s)
    }

    private fun controllerFor(s: Source): MediaController? = when (s) {
        Source.YANDEX -> controllers.filter { it.packageName in Known.YANDEX_MUSIC }.maxByOrNull { if (isPlaying(it)) 1 else 0 }
        // Bluetooth = any player except Яндекс Музыка; known TEYES BT apps first, then whatever is playing.
        Source.BLUETOOTH -> controllers.filter { it.packageName !in Known.YANDEX_MUSIC && it.packageName != ctx.packageName }
            .maxByOrNull { (if (it.packageName in Known.BT_SESSIONS) 2 else 0) + (if (isPlaying(it)) 1 else 0) }
    }

    private fun inSource(pkg: String, s: Source) = if (s == Source.YANDEX) pkg in Known.YANDEX_MUSIC else pkg !in Known.YANDEX_MUSIC

    val current: MediaController? get() { version; return controllerFor(source) }

    private fun isPlaying(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING ||
        c?.playbackState?.state == PlaybackState.STATE_BUFFERING

    fun nowPlaying(): NowPlaying {
        version
        val c = current
        val md = c?.metadata
        val st = c?.playbackState
        val playing = isPlaying(c)
        var pos = st?.position ?: 0L
        if (st != null && playing && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur > 0) pos = pos.coerceIn(0, dur)
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val rating = md?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        val key = "${c?.packageName}|$title|$artist"
        val liked = likedLocal[key] ?: (rating?.ratingStyle == Rating.RATING_HEART && rating.hasHeart())
        val (defTitle, defArtist) = when {
            !hasAccess -> "Музыка" to "Дайте доступ"
            source == Source.YANDEX -> "Яндекс Музыка" to "Нажмите ▶ для воспроизведения"
            else -> "Bluetooth-аудио" to "Подключите телефон по Bluetooth"
        }
        return NowPlaying(
            title = title ?: defTitle,
            artist = artist ?: if (title == null) defArtist else "",
            art = art,
            artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            durationMs = dur, positionMs = pos, playing = playing, liked = liked, hasSession = c != null,
        )
    }

    fun debug(): List<String> {
        if (!hasAccess) return listOf("Нет доступа к уведомлениям")
        if (controllers.isEmpty()) return listOf("Активных плееров нет")
        return controllers.map { c ->
            val st = when (c.playbackState?.state) { PlaybackState.STATE_PLAYING -> "играет"; PlaybackState.STATE_PAUSED -> "пауза"; else -> "state=${c.playbackState?.state}" }
            "${c.packageName} · $st · ${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"}"
        }
    }

    // ---------- controls ----------

    fun playPause() {
        val c = current
        if (c != null) {
            if (isPlaying(c)) c.transportControls.pause()
            else { if (source == Source.BLUETOOTH && Prefs.bool(ctx, Prefs.BT_OPEN_APP, false)) BtAudio.activate(ctx); c.transportControls.play() }
        } else coldStart(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun next() { current?.transportControls?.skipToNext() ?: coldStart(KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun previous() { current?.transportControls?.skipToPrevious() ?: coldStart(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    fun seekTo(fraction: Float) {
        val c = current ?: return
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: return
        if (dur > 0) c.transportControls.seekTo((dur * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleLike() {
        val c = current ?: return
        val np = nowPlaying()
        val key = "${c.packageName}|${np.title}|${np.artist}"
        val newValue = !np.liked
        likedLocal[key] = newValue
        runCatching { c.transportControls.setRating(Rating.newHeartRating(newValue)) }
        version++
    }

    /**
     * Switch the card to another source. Two independent players: switching never starts playback —
     * it only pauses the other source. Bluetooth additionally switches the car audio to BT (TEYES).
     */
    fun select(s: Source) {
        if (s == source) return
        val old = current
        source = s
        Prefs.put(ctx, Prefs.SOURCE, s.name)
        pauseOthers(s)
        // optional: some TEYES firmwares switch audio to BT only when their BT screen is opened
        if (s == Source.BLUETOOTH && Prefs.bool(ctx, Prefs.BT_OPEN_APP, false)) BtAudio.activate(ctx)
        version++
    }

    /** No session yet: wake the source app in the background via a media button. */
    private fun coldStart(keyCode: Int) {
        when (source) {
            Source.YANDEX -> {
                val pkg = Apps.firstInstalled(ctx, Known.YANDEX_MUSIC)
                if (pkg == null) { Apps.toast(ctx, "Яндекс Музыка не установлена"); return }
                sendMediaButton(pkg, keyCode)
                // If Yandex didn't start a session in the background, open it so the user can choose music.
                main.postDelayed({ if (controllerFor(Source.YANDEX) == null && source == Source.YANDEX) Apps.launch(ctx, pkg) }, 2500)
            }
            Source.BLUETOOTH -> {
                // No Bluetooth player session: never send a global media key (Android would hand it to
                // Яндекс Музыке). Only the head unit's own BT app gets it, if there is one.
                if (Prefs.bool(ctx, Prefs.BT_OPEN_APP, false)) BtAudio.activate(ctx)
                val pkg = BtAudio.packageName(ctx)
                if (pkg != null) sendMediaButton(pkg, keyCode)
                else Apps.toast(ctx, "Включите музыку на телефоне — она появится здесь")
            }
        }
    }

    private fun sendMediaButton(pkg: String, keyCode: Int) {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            runCatching { ctx.sendBroadcast(i) }
        }
    }

    private fun dispatchKey(keyCode: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /** Full-screen app of the current source (bottom-bar music button, long press on source). */
    fun openSourceApp(s: Source = source) {
        val pkg = when (s) {
            Source.YANDEX -> Apps.firstInstalled(ctx, Known.YANDEX_MUSIC)
            Source.BLUETOOTH -> {
                val playing = controllerFor(Source.BLUETOOTH)?.packageName
                if (!Apps.launch(ctx, playing) && !BtAudio.activate(ctx, stayInApp = true)) Apps.toast(ctx, "Нет активного плеера")
                return
            }
        }
        if (!Apps.launch(ctx, pkg)) Apps.toast(ctx, if (s == Source.YANDEX) "Яндекс Музыка не установлена" else "Bluetooth-приложение не найдено")
    }
}

/** Loads album covers from http(s)/content URIs, keeps the last few in memory. */
object ArtCache {
    private val cache = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > 6
    }

    @Synchronized fun get(uri: String?): androidx.compose.ui.graphics.ImageBitmap? = uri?.let { cache[it] }

    fun load(ctx: Context, uri: String): androidx.compose.ui.graphics.ImageBitmap? {
        get(uri)?.let { return it }
        val bytes = runCatching {
            if (uri.startsWith("http")) {
                val c = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.inputStream.use { it.readBytes() }.also { c.disconnect() }
            } else ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 600) sample *= 2   // keep ~600–1200 px
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val img = bmp.asImageBitmap()
        synchronized(this) { cache[uri] = img }
        return img
    }
}
