@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ShortcodeComposer
import dev.ipf.whitenoise.android.ui.EmojiData
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val EMOJI_SUGGESTIONS_TEST_TAG = "composer.emoji_suggestions"
private const val MAX_EMOJI_SUGGESTIONS = 12
private val EmojiSuggestionGlyphSize = 28.dp

/** Stable test tag for one suggestion. */
internal fun emojiSuggestionTestTag(index: Int): String = "composer.emoji_suggestion.$index"

/**
 * Emoji completion for a `:token` before the caret: the user's shortcodes first, then built-ins,
 * then Unicode emoji by name. A sibling strip above the composer, like the mention picker.
 */
@Composable
internal fun EmojiShortcodeSuggestions(
    field: TextFieldValue,
    onPick: (TextFieldValue) -> Unit,
) {
    val active =
        remember(field.text, field.selection) {
            field.selection
                .takeIf { it.collapsed }
                ?.let { ShortcodeComposer.activeQuery(field.text, it.start) }
        }
    val context = LocalContext.current
    val custom = LocalCustomEmoji.current
    val suggestions by produceState(emptyList<String>(), active?.query, custom) {
        val query = active?.query
        if (query == null) {
            value = emptyList()
            return@produceState
        }
        val entries = withContext(Dispatchers.IO) { EmojiData.load(context) }
        value =
            withContext(Dispatchers.Default) {
                val shortcodes = custom.entries.map { it.shortcode } + EmojiShortcodes.builtins
                val named = EmojiData.search(entries, query, MAX_EMOJI_SUGGESTIONS).map { it.emoji }
                (ShortcodeComposer.matchingShortcodes(shortcodes, query) + named).distinct().take(MAX_EMOJI_SUGGESTIONS)
            }
    }
    if (active == null || suggestions.isEmpty()) {
        return
    }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(EMOJI_SUGGESTIONS_TEST_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp)) {
            itemsIndexed(suggestions, key = { _, emoji -> emoji }) { index, emoji ->
                Surface(
                    onClick = {
                        val insertion = ShortcodeComposer.insert(field.text, active, field.selection.start, emoji)
                        onPick(TextFieldValue(insertion.text, TextRange(insertion.selection)))
                    },
                    modifier = Modifier.size(EmojiPickerMinimumCellSize).testTag(emojiSuggestionTestTag(index)),
                    shape = CircleShape,
                    color = Color.Transparent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        EmojiGlyph(emoji, size = EmojiSuggestionGlyphSize)
                    }
                }
            }
        }
    }
}
