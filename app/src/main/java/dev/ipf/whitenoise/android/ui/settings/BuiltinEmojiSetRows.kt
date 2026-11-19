package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSetChoices
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Stable test tag for a pre-installed set's row in the emoji set browser. */
internal fun builtinEmojiSetRowTestTag(set: BuiltinEmojiSet): String = "emoji_sets.builtin.${set.id}"

private val BuiltinEmojiSetIconSize = 32.dp

/**
 * The sets shipped in the APK, listed with installed sets. Turning one off only takes it out of the
 * picker and suggestions; its artwork stays bundled, so messages that use it still render.
 */
internal fun LazyListScope.builtinEmojiSetRows(
    choices: BuiltinEmojiSetChoices,
    enabled: Boolean,
) {
    items(BuiltinEmojiSet.entries, key = { it.id }) { set ->
        SettingsGroup {
            row("builtin") { context ->
                val shown = set !in choices.hidden
                SettingsSwitch(
                    context = context,
                    title = stringResource(set.titleRes),
                    checked = shown,
                    onCheckedChange = { choices.setHidden(set, hide = !it) },
                    modifier = Modifier.testTag(builtinEmojiSetRowTestTag(set)),
                    subtitle =
                        if (shown) {
                            val count = set.shortcodes.size
                            stringResource(
                                R.string.emoji_sets_builtin_shown,
                                pluralStringResource(R.plurals.emoji_sets_item_count, count, count),
                            )
                        } else {
                            stringResource(R.string.emoji_sets_builtin_hidden)
                        },
                    explanation = stringResource(R.string.explanation_organize_emoji_builtin_set),
                    enabled = enabled && choices.loaded,
                    leading = { BuiltinEmojiSetIcon(set) },
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun BuiltinEmojiSetIcon(set: BuiltinEmojiSet) {
    val drawable = EmojiShortcodes.builtinDrawable(set.iconEmoji) ?: return
    EmojiArtImage(
        EmojiArt.Bundled(drawable),
        contentDescription = null,
        modifier = Modifier.size(BuiltinEmojiSetIconSize),
    )
}
