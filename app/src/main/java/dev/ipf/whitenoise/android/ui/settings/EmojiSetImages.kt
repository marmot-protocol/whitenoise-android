package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.state.EmojiSetsBackend
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One tile's image while the set preview is open. */
internal sealed interface EmojiSetImage {
    data object Loading : EmojiSetImage

    data object Failed : EmojiSetImage

    class Ready(
        val art: EmojiArt,
    ) : EmojiSetImage
}

/** Where a preview's pixels come from. */
internal interface EmojiSetImageSource {
    /** The cover, from the set author's host. */
    suspend fun cover(set: EmojiSetFfi): ByteArray

    /** One emoji, from the installed snapshot when [local], otherwise from the set author's host. */
    suspend fun emoji(
        set: EmojiSetFfi,
        shortcode: String,
        local: Boolean,
    ): ByteArray
}

/** Adapts the screen's owner-scoped backend. */
internal fun EmojiSetsBackend.imageSource(): EmojiSetImageSource =
    object : EmojiSetImageSource {
        override suspend fun cover(set: EmojiSetFfi): ByteArray = this@imageSource.cover(set)

        override suspend fun emoji(
            set: EmojiSetFfi,
            shortcode: String,
            local: Boolean,
        ): ByteArray =
            if (local) installedEmojiImage(set, shortcode) else emojiImage(set, shortcode)
    }

/**
 * Transient images for one opened set version. An installed snapshot of exactly this version renders from local
 * storage at once. Anything else stays blank until [request], because each load shares the user's IP address with
 * the host the set author chose. Tiles load as they scroll into view, a few at a time, and nothing is stored.
 */
internal class EmojiSetImages(
    val set: EmojiSetFfi,
    private val source: EmojiSetImageSource,
    private val decode: suspend (ByteArray) -> EmojiArt? = ::decodeEmojiArt,
) {
    /** True when the installed snapshot is this exact version, so its images are already on the device. */
    val local: Boolean = set.installed?.eventIdHex == set.eventIdHex

    /** Whether tiles may load. Local sets start loaded; remote ones wait for the user. */
    var requested by mutableStateOf(local)
        private set

    /** The cover, once requested and only for a remote set that names one. */
    var cover by mutableStateOf<EmojiSetImage?>(null)
        private set

    /** Restarts the cover's effect after a cancelled load, like [attempt] for tiles. */
    var coverAttempt by mutableIntStateOf(0)
        private set

    private val images = mutableStateMapOf<String, EmojiSetImage>()
    private val attempts = mutableStateMapOf<String, Int>()
    private val readyOrder = ArrayDeque<String>()
    private val attachedTiles = mutableSetOf<String>()
    private val permits = Semaphore(MAX_PARALLEL_LOADS)
    private var activeLoads by mutableIntStateOf(0)

    val hasLoadingTiles: Boolean get() = activeLoads > 0

    /** The tile state for [shortcode]; null before its first load and after a failed one is retried. */
    operator fun get(shortcode: String): EmojiSetImage? = images[shortcode]

    /** Records the user's consent to contact this set's image hosts. */
    fun request() {
        requested = true
    }

    fun attach(shortcode: String) {
        attachedTiles.add(shortcode)
    }

    fun detach(shortcode: String) {
        attachedTiles.remove(shortcode)
        trimReadyImages()
    }

    /** Loads [shortcode] once, unless it is already loading or loaded. */
    suspend fun load(shortcode: String) {
        if (!requested || images[shortcode] != null) return
        images[shortcode] = EmojiSetImage.Loading
        activeLoads++
        try {
            val image = fetch { source.emoji(set, shortcode, local) }
            images[shortcode] = image
            if (image is EmojiSetImage.Ready) {
                readyOrder.addLast(shortcode)
                trimReadyImages()
            }
        } catch (cancelled: CancellationException) {
            // A blocking native read can unwind after its tile already came back and found it Loading; the new
            // attempt restarts that tile's effect, and a tile still off screen loads when it returns.
            images.remove(shortcode)
            attempts[shortcode] = attempt(shortcode) + 1
            throw cancelled
        } finally {
            activeLoads--
        }
    }

    /** Loads the cover once, for a remote set that names one. */
    suspend fun loadCover() {
        if (!requested || local || set.imageUrl == null || cover != null) return
        cover = EmojiSetImage.Loading
        try {
            cover = fetch { source.cover(set) }
        } catch (cancelled: CancellationException) {
            cover = null
            coverAttempt++
            throw cancelled
        }
    }

    /** Counts retries, so a tile's load effect restarts only for a new attempt, never for its own state change. */
    fun attempt(shortcode: String): Int = attempts[shortcode] ?: 0

    /** Whether a failed tile can try again; the installed snapshot's stored bytes will not change. */
    val retryable: Boolean get() = !local

    /** Clears a failed remote tile so it loads again. */
    fun retry(shortcode: String) {
        if (!retryable || images[shortcode] != EmojiSetImage.Failed) return
        images.remove(shortcode)
        attempts[shortcode] = attempt(shortcode) + 1
    }

    /** The first loaded emoji, which stands in for a missing or unloaded cover. */
    fun firstReady(): EmojiArt? =
        set.emoji.firstNotNullOfOrNull { (images[it.shortcode] as? EmojiSetImage.Ready)?.art }

    /** Drops older offscreen images first. Tiles still in composition keep their images until they detach. */
    private fun trimReadyImages() {
        while (readyOrder.size > MAX_READY_IMAGES) {
            val index = readyOrder.indexOfFirst { it !in attachedTiles }
            if (index < 0) return
            images.remove(readyOrder.removeAt(index))
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any fetch or decode failure becomes a retryable tile.
    private suspend fun fetch(read: suspend () -> ByteArray): EmojiSetImage =
        try {
            permits.withPermit { decode(read()) }?.let(EmojiSetImage::Ready) ?: EmojiSetImage.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EmojiSetImage.Failed
        }

    private companion object {
        const val MAX_PARALLEL_LOADS = 4

        // Decoded emoji are under 256 px on their longer edge, so this bounds the grid to a few tens of MB.
        const val MAX_READY_IMAGES = 160
    }
}

private suspend fun decodeEmojiArt(bytes: ByteArray): EmojiArt? =
    withContext(Dispatchers.Default) { decodeEmojiImage(bytes)?.let(EmojiArt::Decoded) }
