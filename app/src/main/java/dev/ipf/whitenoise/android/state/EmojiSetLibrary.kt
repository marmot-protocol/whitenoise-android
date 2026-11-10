package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.InstalledCustomEmojiFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Invalidates transient picker metadata after an MDK install or removal. MDK owns the library. */
internal class EmojiSetLibrary {
    var revision by mutableIntStateOf(0)
        private set

    fun invalidate() {
        revision++
    }
}

/** The first installed set wins a shortcode collision. Bundled and device-local emoji keep precedence. */
internal fun installedEmojiByCode(entries: List<InstalledCustomEmojiFfi>): Map<String, InstalledCustomEmojiFfi> =
    buildMap {
        entries.forEach { entry ->
            val code = ":${entry.shortcode}:"
            if (Nip30Emoji.isSendable(code) && code !in EmojiShortcodes.builtins) putIfAbsent(code, entry)
        }
    }

/** Reads the installed bytes and checks that the selected set did not change during resolution. */
internal suspend fun MarmotInterface.readInstalledEmojiArtwork(
    account: String,
    code: String,
    entry: InstalledCustomEmojiFfi,
): LocalEmojiArtwork {
    val asset = readInstalledCustomEmoji(account, entry.setCoordinate, entry.shortcode)
    check(asset.emoji.sha256 == entry.sha256) { "installed emoji changed during send" }
    val extension =
        EMOJI_MEDIA_TYPES.entries.firstOrNull { it.value == entry.mime }?.key
            ?: if (entry.mime == "image/apng") "png" else error("unsupported installed emoji media type")
    return LocalEmojiArtwork(code, "${entry.shortcode}.$extension", entry.mime, asset.bytes)
}
