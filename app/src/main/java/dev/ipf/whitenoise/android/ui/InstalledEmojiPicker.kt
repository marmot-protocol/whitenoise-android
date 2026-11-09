@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.InstalledCustomEmojiFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.installedEmojiByCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Transient picker metadata. The image bytes are read from MDK only for visible cells. */
internal class InstalledEmojiPicker(
    val entries: Map<String, InstalledCustomEmojiFfi>,
    private val read: suspend (InstalledCustomEmojiFfi) -> EmojiArt?,
) {
    private val decodes = Semaphore(2)

    /** Decodes a bounded thumbnail without persisting the set's artwork on Android. */
    @Suppress("TooGenericExceptionCaught") // A failed decode leaves the shortcode visible.
    suspend fun artwork(entry: InstalledCustomEmojiFfi): EmojiArt? =
        decodes.withPermit {
            try {
                read(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
}

/** Reads the active account's installed emoji. Changing owner immediately drops the old projection. */
@Suppress("TooGenericExceptionCaught") // An unavailable native projection cannot replace device-local emoji.
@Composable
internal fun rememberInstalledEmojiPicker(appState: WhiteNoiseAppState?): InstalledEmojiPicker? {
    val account = appState?.activeAccountRef ?: return null
    val generation = appState.runtimeGeneration
    return key(appState, account, generation, appState.emojiSetLibrary.revision) {
        val picker by produceState<InstalledEmojiPicker?>(null) {
            try {
                val entries = appState.marmotIo { installedEmojiByCode(installedCustomEmoji(account)) }
                if (appState.activeAccountRef == account && appState.runtimeGeneration == generation) {
                    value =
                        InstalledEmojiPicker(entries) { entry ->
                            val bytes =
                                appState.marmotIo {
                                    readInstalledCustomEmoji(account, entry.setCoordinate, entry.shortcode)
                                        .takeIf { it.emoji.sha256 == entry.sha256 }
                                        ?.bytes
                                }
                            val art =
                                withContext(Dispatchers.IO) {
                                    bytes?.let { decodeEmojiImage(it)?.let(EmojiArt::Decoded) }
                                }
                            art.takeIf {
                                appState.activeAccountRef == account && appState.runtimeGeneration == generation
                            }
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = null
            }
        }
        picker
    }
}

/** Keeps installed set thumbnails scoped to the composer picker and shortcode strip. */
@Composable
internal fun ComposerEmojiLibrary(
    installed: InstalledEmojiPicker?,
    content: @Composable () -> Unit,
) {
    val local = LocalCustomEmoji.current
    val library = remember(local, installed) { installed?.let { CustomEmojiSet(local.entries, it) } ?: local }
    CompositionLocalProvider(LocalCustomEmoji provides library, content = content)
}

/** Resolves one visible shortcode without retaining stale pixels when its set or account changes. */
@Composable
internal fun installedEmojiArtwork(shortcode: String): EmojiArt? {
    val picker = LocalCustomEmoji.current.installed ?: return null
    val entry = picker.entries[shortcode] ?: return null
    return key(picker, entry) {
        val art by produceState<EmojiArt?>(null) { value = picker.artwork(entry) }
        art
    }
}
