package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Original bundled PNG bytes bypass drawable decoding and Android resource image processing. */
internal fun readBuiltinEmojiArtwork(
    context: Context,
    shortcodes: List<String>,
): List<LocalEmojiArtwork> =
    shortcodes.filter { it in EmojiShortcodes.builtins }.map { code ->
        val fileName = "${code.trim(':')}.png"
        val bytes = context.assets.open("builtin-emoji/$fileName").use { it.readBytes() }
        LocalEmojiArtwork(code, fileName, "image/png", bytes)
    }
