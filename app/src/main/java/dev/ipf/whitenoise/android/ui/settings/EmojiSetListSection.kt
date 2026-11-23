package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSetChoices

/** One heading of the Emoji sets list; the installed section also lists the pre-installed sets. */
internal fun LazyListScope.emojiSetSection(
    @StringRes heading: Int,
    sets: List<EmojiSetFfi>,
    busy: Boolean,
    onShow: (EmojiSetFfi) -> Unit,
    builtins: BuiltinEmojiSetChoices? = null,
) {
    if (sets.isEmpty() && builtins == null) return
    item(key = "heading.$heading") { SettingsSection(stringResource(heading)) }
    // The shipped White Noise and Marmot sets sit with the installed sets and can be hidden like them.
    if (builtins != null) builtinEmojiSetRows(builtins, enabled = !busy)
    items(sets, key = { it.coordinate }) { set ->
        SettingsGroup {
            row("set") { context ->
                SettingsAction(
                    context = context,
                    title = set.displayTitle(),
                    subtitle =
                        when {
                            set.installed?.updateAvailable == true ->
                                stringResource(R.string.emoji_sets_update_available)
                            set.installed != null -> stringResource(R.string.emoji_sets_installed)
                            else -> {
                                val count = set.emoji.size
                                pluralStringResource(R.plurals.emoji_sets_item_count, count, count)
                            }
                        },
                    explanation = stringResource(R.string.explanation_organize_emoji_set),
                    enabled = !busy,
                    onClick = { onShow(set) },
                    leading = { SettingsLeadingIcon(R.drawable.ic_category) },
                )
            }
        }
    }
}
