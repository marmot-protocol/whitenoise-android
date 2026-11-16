package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.media.ByteSizeLruCache
import dev.ipf.whitenoise.android.media.PresentationMemoryTrim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    /** The MDK reference for this sticker. */
    val ref: StickerRefFfi get() = StickerRefFfi(packCoordinate, shortcode, plaintextSha256)

    companion object {
        /** The key of a received sticker reference. */
        fun from(ref: StickerRefFfi) = StickerKey(ref.packCoordinate, ref.shortcode, ref.plaintextSha256)

        /** The key of a sticker from a pack. */
        fun from(sticker: StickerFfi) = StickerKey(sticker.packCoordinate, sticker.shortcode, sticker.plaintextSha256)
    }
}

/** A decoded sticker image with the sticker record MDK verified it against. */
data class LoadedSticker<I>(
    val image: I,
    val sticker: StickerFfi,
)

/**
 * One account's sticker packs as the picker and timeline need them, shared by all
 * of its conversations. Main-confined; MDK owns the packs, this only holds the last
 * installed list it returned.
 */
class StickerLibrary(
    private val packs: suspend (installedOnly: Boolean, search: String?) -> List<StickerPackFfi>,
    private val syncPacks: suspend () -> Unit,
    private val fetchPack: suspend (input: String) -> StickerPackFfi,
    private val installPack: suspend (input: String) -> StickerPackFfi,
    private val uninstallPack: suspend (input: String) -> Unit,
    /** MDK's share link for a coordinate, naming the relays the pack was found on; null when none is available. */
    private val packShareLink: suspend (coordinate: String) -> String? = { null },
    /** Which White Noise default packs this account has had; null for a library that installs no defaults. */
    private val defaultPacks: StickerDefaultPackLedger? = null,
) {
    /** The link or address each pack was last opened from, so installing it asks the same relay hints. */
    private val openedFrom = HashMap<String, String>()

    /** The installed packs from the last successful read. */
    var installed: List<StickerPackFfi> by mutableStateOf(emptyList())
        private set

    /** Whether [installed] holds a successful read. */
    var hasLoadedInstalled: Boolean by mutableStateOf(false)
        private set

    /** Whether the first read has finished, successfully or not, so [installed] can decide a received sticker. */
    var hasTriedInstalled: Boolean by mutableStateOf(false)
        private set

    /** Whether the latest installed read failed and settled; a successful read clears it. */
    var installedReadFailed: Boolean by mutableStateOf(false)
        private set

    /** Whether White Noise's default packs are installing, so an empty picker waits instead of offering the browser. */
    var isInstallingDefaults: Boolean by mutableStateOf(false)
        private set

    private val loadMutex = Mutex()
    private val syncMutex = Mutex()
    private var synced = false

    /** Advances on every install or uninstall, so a read that started before it can't overwrite its effect. */
    private var membershipGeneration = 0L

    /** Whether the pack at [coordinate] was in the last installed list. */
    fun isInstalled(coordinate: String): Boolean = installed.any { it.coordinate == coordinate }

    /** Reads the installed packs once; concurrent callers share the first read. */
    suspend fun loadInstalledIfNeeded() {
        if (hasLoadedInstalled) return
        loadMutex.withLock { if (!hasLoadedInstalled) reloadInstalled() }
    }

    /**
     * A failed read keeps the last list and settles as [installedReadFailed]; the next caller tries again. A read,
     * or a read failure, that overlapped an install or uninstall is dropped, because it may predate that change.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reloadInstalled() {
        val generation = membershipGeneration
        try {
            val read = packs(true, null)
            if (generation == membershipGeneration) {
                installed = read
                hasLoadedInstalled = true
                installedReadFailed = false
            }
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            if (generation == membershipGeneration) installedReadFailed = true
        }
        hasTriedInstalled = true
    }

    /** Fetches pack events from relays once per account unless [force]d; throws when relays fail. */
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

    /** The pack an naddr or share link names, fetched from the account's relays plus the input's relay hints. */
    suspend fun lookUp(input: String): StickerPackFfi = fetchPack(input).also { openedFrom[it.coordinate] = input }

    /**
     * Installs [pack] through the link it was opened from, so a pack found only on that link's relays still installs
     * on an account without them, then lists it at once and rereads the installed list.
     */
    suspend fun install(pack: StickerPackFfi): StickerPackFfi {
        val added = installPack(openedFrom[pack.coordinate] ?: pack.coordinate)
        membershipGeneration++
        installed = installed.filterNot { it.coordinate == added.coordinate } + added
        reloadInstalled()
        return added
    }

    /**
     * Uninstalls [pack] and drops it from [installed] before rereading, so a failed or stale reread can't keep
     * loading a removed pack's stickers automatically. A failed uninstall leaves the list unchanged.
     */
    suspend fun uninstall(pack: StickerPackFfi) {
        uninstallPack(pack.coordinate)
        // A default removed before it was recorded must not be installed again.
        if (pack.coordinate in StickerDefaultPacks.coordinates) defaultPacks?.record(listOf(pack.coordinate))
        membershipGeneration++
        installed = installed.filterNot { it.coordinate == pack.coordinate }
        reloadInstalled()
    }

    /** A link to [pack] naming the relays it was found on, so recipients on other relays can open it; null if none. */
    suspend fun shareLink(pack: StickerPackFfi): String? = packShareLink(pack.coordinate)

    /**
     * Installs White Noise's default packs this account hasn't had yet. It waits for a successful [sync], so packs
     * already on the account's list count as installed. A failed install, offline say, is tried again next time; an
     * installed or removed default is recorded and never installed again.
     */
    suspend fun installDefaultPacksIfNeeded() {
        val ledger = defaultPacks
        if (ledger != null && synced && !isInstallingDefaults) {
            loadInstalledIfNeeded()
            if (hasLoadedInstalled) installDefaults(ledger)
        }
    }

    /** Installs each default the account still needs; one that fails stays pending for the next open. */
    private suspend fun installDefaults(ledger: StickerDefaultPackLedger) {
        val present = installed.mapTo(HashSet()) { it.coordinate }
        ledger.record(StickerDefaultPacks.coordinates.filter { it in present })
        val pending = StickerDefaultPacks.toInstall(present, ledger.offered())
        if (pending.isEmpty()) return
        isInstallingDefaults = true
        try {
            for (coordinate in pending) {
                // A default the person removed while an earlier one installed stays removed.
                val added =
                    if (coordinate in ledger.offered()) {
                        null
                    } else {
                        runCatchingCancellable { installPack(StickerDefaultPacks.installInput(coordinate)) }.getOrNull()
                    }
                if (added != null) {
                    ledger.record(listOf(coordinate))
                    membershipGeneration++
                    installed = installed.filterNot { it.coordinate == added.coordinate } + added
                }
            }
        } finally {
            isInstallingDefaults = false
        }
        reloadInstalled()
    }
}

/**
 * Loads and caches decoded sticker images for one account. MDK keeps no copy of a
 * sticker image, so this keeps a byte-bounded in-memory cache. Concurrent requests
 * for one sticker share one download, and a failed load stays failed until [retry],
 * so a broken sticker never re-dials its server from scrolling. A download finishes
 * and is kept even when every caller leaves; [scope] ends with the account.
 * Main-confined.
 */
class StickerAssetCache<I : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (StickerKey) -> LoadedSticker<I>?,
    maxBytes: Long,
    sizeOf: (I) -> Int,
) {
    private val capacity = maxBytes
    private val images = ByteSizeLruCache<StickerKey, LoadedSticker<I>>(maxBytes, sizeOf = { sizeOf(it.image) })
    private val failures = HashSet<StickerKey>()
    private val inFlight = HashMap<StickerKey, Deferred<LoadedSticker<I>?>>()

    /** Downloads currently shared by callers; a finished or cancelled download leaves none behind. */
    internal val downloadsInFlight: Int get() = inFlight.size

    /** The decoded image when it is in memory. */
    fun cached(key: StickerKey): LoadedSticker<I>? = images.get(key)

    /** Whether the last load of [key] failed. */
    fun hasFailed(key: StickerKey): Boolean = key in failures

    /** The sticker's image, downloading it unless it already failed. */
    suspend fun image(key: StickerKey): LoadedSticker<I>? {
        val cached = images.get(key)
        return if (cached != null || key in failures) cached else download(key)
    }

    private suspend fun download(key: StickerKey): LoadedSticker<I>? {
        inFlight[key]?.let { return it.await() }
        // Lazy, so the entry is in place before the download can finish and remove it.
        val download =
            scope.async(start = CoroutineStart.LAZY) {
                loadOrNull(key).also { loaded -> if (loaded != null) images.put(key, loaded) else failures.add(key) }
            }
        inFlight[key] = download
        // Also runs when the account's scope is cancelled before the download starts.
        download.invokeOnCompletion { inFlight.remove(key, download) }
        return download.await()
    }

    /** Clears an earlier failure and downloads again, for an explicit tap. */
    suspend fun retry(key: StickerKey): LoadedSticker<I>? {
        failures.remove(key)
        return image(key)
    }

    /** Releases cached images down to [trim]'s share of the budget; shown stickers keep their bitmaps. */
    internal fun trimMemory(trim: PresentationMemoryTrim) {
        images.trimToSize(trim.targetBytes(capacity))
    }

    /** Loads [key]; a failed load returns null so the bubble shows its fallback. Cancellation still propagates. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadOrNull(key: StickerKey): LoadedSticker<I>? =
        try {
            load(key)
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            null
        }
}
