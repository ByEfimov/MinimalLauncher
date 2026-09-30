package com.ravium.teyeslauncher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.offline_cache.OfflineCacheManager
import com.yandex.mapkit.offline_cache.Region
import com.yandex.mapkit.offline_cache.RegionListUpdatesListener
import com.yandex.mapkit.offline_cache.RegionListener
import com.yandex.mapkit.offline_cache.RegionState
import com.yandex.mapkit.offline_cache.RegionsAtPointListener
import java.lang.ref.WeakReference

/**
 * Maps without internet (Яндекс MapKit offline cache): download a region (область / город) in advance —
 * the map, routing and search then keep working with no connection (the router and search are COMBINED:
 * online when possible, offline otherwise). Needs a Yandex key; OSM mode only keeps what was already shown.
 */
object OfflineMaps {
    val available get() = YandexMaps.enabled

    /** Bumped on any region state/progress change → UI re-reads. */
    var version by mutableIntStateOf(0)
        private set
    var nearby by mutableStateOf<List<Int>>(emptyList())
        private set
    var cacheSize by mutableStateOf<String?>(null)
        private set

    private val manager: OfflineCacheManager? by lazy {
        if (!available) null else runCatching {
            MapKitFactory.getInstance().offlineCacheManager.also {
                it.allowUseCellularNetwork(true)   // head units are online through the phone / SIM
                it.enableAutoUpdate(true)
            }
        }.getOrNull()
    }

    // MapKit keeps listeners as weak references — hold strong ones here
    private val regionListener = object : RegionListener {
        override fun onRegionStateChanged(id: Int) {
            if (state(id) == RegionState.DOWNLOADING) downloading += id else downloading -= id
            anyDownloading = downloading.isNotEmpty(); version++
        }
        override fun onRegionProgress(id: Int) { version++ }
    }
    private val listListener = RegionListUpdatesListener { version++ }
    private var listening = false

    fun start() {
        val m = manager ?: return
        if (listening) return
        listening = true
        m.addRegionListener(WeakReference(regionListener))
        m.addRegionListUpdatesListener(WeakReference(listListener))
    }

    fun regions(): List<Region> = runCatching { manager?.regions().orEmpty() }.getOrDefault(emptyList())
    fun state(id: Int): RegionState? = runCatching { manager?.getState(id) }.getOrNull()
    fun progress(id: Int): Float = runCatching { manager?.getProgress(id) ?: 0f }.getOrDefault(0f)
    fun download(id: Int) { manager?.startDownload(id); version++ }
    fun pause(id: Int) { manager?.pauseDownload(id); version++ }
    fun drop(id: Int) { manager?.drop(id); version++; refreshSize() }

    private val downloading = HashSet<Int>()
    /** Something is downloading right now (dot on the map button). */
    var anyDownloading by mutableStateOf(false)
        private set

    fun findNearby(lat: Double, lon: Double) {
        val m = manager ?: return
        m.requestRegionsAtPoint(Point(lat, lon), object : RegionsAtPointListener {
            override fun onRegions(ids: MutableList<Int>) { nearby = ids.toList() }
            override fun onError(e: com.yandex.runtime.Error) {}
        })
    }

    fun refreshSize() {
        val m = manager ?: return
        m.computeCacheSize { bytes -> cacheSize = bytes?.let { "%.0f МБ".format(it / 1_048_576.0) } }
    }
}
