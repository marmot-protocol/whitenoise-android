package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.InstalledCustomEmojiFfi
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
