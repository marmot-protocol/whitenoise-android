package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.EmojiPickerTab
import dev.ipf.whitenoise.android.ui.InstalledEmojiSetTab

/** Section header and tab label of [tab]. */
@Composable
internal fun emojiPickerTabTitle(tab: EmojiPickerTab): String =
    when (tab) {
        is EmojiCategory -> stringResource(tab.titleRes)
        is BuiltinEmojiSet -> stringResource(tab.titleRes)
        is InstalledEmojiSetTab -> tab.title
    }

/** A category's glyph, or an emoji set's own first emoji, on its picker tab. */
@Composable
internal fun EmojiPickerTabIcon(
    tab: EmojiPickerTab,
    size: Dp,
    tint: Color,
) {
    val title = emojiPickerTabTitle(tab)
    when (tab) {
        is EmojiCategory ->
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = title,
                modifier = Modifier.size(size),
                tint = tint,
            )
        is BuiltinEmojiSet -> EmojiGlyph(tab.iconEmoji, Modifier.semantics { contentDescription = title }, size)
        is InstalledEmojiSetTab -> EmojiGlyph(tab.iconEmoji, Modifier.semantics { contentDescription = title }, size)
    }
}
