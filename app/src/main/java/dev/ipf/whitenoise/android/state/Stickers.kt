package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Sonar sticker packs (kind 30031) are public Nostr events. A sticker message is
// a kind-9 whose `sticker` tag names the pack, the shortcode and the image's
// SHA-256. MDK authorizes, downloads and verifies every image; its bytes come
// from a public URL the pack's author chose, so loading one discloses this
// device's IP address to that server.

/** One sticker's immutable identity: pack, shortcode and image hash. */
data class StickerKey(
    val packCoordinate: String,
    val shortcode: String,
    val plaintextSha256: String,
) {
    val ref: StickerRefFfi get() = StickerRefFfi(packCoordinate, shortcode, plaintextSha256)

    companion object {
        fun from(ref: StickerRefFfi) = StickerKey(ref.packCoordinate, ref.shortcode, ref.plaintextSha256)

        fun from(sticker: StickerFfi) = StickerKey(sticker.packCoordinate, sticker.shortcode, sticker.plaintextSha256)
    }
}

/** A decoded sticker image with the sticker record MDK verified it against. */
data class LoadedSticker<I>(
    val image: I,
    val sticker: StickerFfi,
)

/**
 * The account's sticker packs as the picker and timeline need them. Main-confined;
 * MDK owns the packs, this only holds the last installed list it returned.
 */
class StickerLibrary(
    private val packs: suspend (installedOnly: Boolean, search: String?) -> List<StickerPackFfi>,
    private val syncPacks: suspend () -> Unit,
    private val fetchPack: suspend (input: String) -> StickerPackFfi,
    private val installPack: suspend (input: String) -> StickerPackFfi,
    private val uninstallPack: suspend (input: String) -> Unit,
) {
    var installed: List<StickerPackFfi> by mutableStateOf(emptyList())
        private set
    var hasLoadedInstalled: Boolean by mutableStateOf(false)
        private set

    private val loadMutex = Mutex()
    private val syncMutex = Mutex()
    private var synced = false

    fun isInstalled(coordinate: String): Boolean = installed.any { it.coordinate == coordinate }

    /** Reads the installed packs once; concurrent callers share the first read. */
    suspend fun loadInstalledIfNeeded() {
        if (hasLoadedInstalled) return
        loadMutex.withLock { if (!hasLoadedInstalled) reloadInstalled() }
    }

    /** A failed read keeps the last list; the next caller tries again. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reloadInstalled() {
        try {
            installed = packs(true, null)
            hasLoadedInstalled = true
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
        }
    }

    /** Fetches pack events from relays once per conversation unless [force]d; throws when relays fail. */
    suspend fun sync(force: Boolean = false) {
        if (synced && !force) return
        syncMutex.withLock {
            if (synced && !force) return
            syncPacks()
            synced = true
        }
        reloadInstalled()
    }

    /** Cached packs the person hasn't installed, optionally filtered by [search]. */
    suspend fun discover(search: String): List<StickerPackFfi> {
        val query = search.trim().ifEmpty { null }
        val installedCoordinates = installed.mapTo(HashSet()) { it.coordinate }
        return packs(false, query).filter { pack ->
            !pack.installed && pack.coordinate !in installedCoordinates && pack.stickers.isNotEmpty()
        }
    }

    suspend fun lookUp(input: String): StickerPackFfi = fetchPack(input)

    suspend fun install(pack: StickerPackFfi): StickerPackFfi = installPack(pack.coordinate).also { reloadInstalled() }

    suspend fun uninstall(pack: StickerPackFfi) {
        uninstallPack(pack.coordinate)
        reloadInstalled()
    }
}

/**
 * Loads and caches decoded sticker images for one conversation. MDK keeps no copy
 * of a sticker image, so this keeps a byte-bounded in-memory cache. Concurrent
 * requests for one sticker share one download, and a failed load stays failed
 * until [retry], so a broken sticker never re-dials its server from scrolling.
 * Main-confined.
 */
class StickerAssetCache<I : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (StickerKey) -> LoadedSticker<I>?,
    maxBytes: Long,
    sizeOf: (I) -> Int,
) {
    private val images = ByteSizeLruCache<StickerKey, LoadedSticker<I>>(maxBytes, sizeOf = { sizeOf(it.image) })
    private val failures = HashSet<StickerKey>()
    private val inFlight = HashMap<StickerKey, Deferred<LoadedSticker<I>?>>()

    fun cached(key: StickerKey): LoadedSticker<I>? = images.get(key)

    fun hasFailed(key: StickerKey): Boolean = key in failures

    /** The sticker's image, downloading it unless it already failed. */
    suspend fun image(key: StickerKey): LoadedSticker<I>? {
        val cached = images.get(key)
        return if (cached != null || key in failures) cached else download(key)
    }

    private suspend fun download(key: StickerKey): LoadedSticker<I>? {
        val pending = inFlight.getOrPut(key) { scope.async { loadOrNull(key) } }
        val loaded = pending.await()
        if (inFlight[key] === pending) {
            inFlight.remove(key)
            if (loaded != null) images.put(key, loaded) else failures.add(key)
        }
        return loaded
    }

    /** Clears an earlier failure and downloads again, for an explicit tap. */
    suspend fun retry(key: StickerKey): LoadedSticker<I>? {
        failures.remove(key)
        return image(key)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadOrNull(key: StickerKey): LoadedSticker<I>? =
        try {
            load(key)
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            null
        }
}
