package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiSection
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.InstalledEmojiSetTab

/** Persistent reaction slots use device-wide artwork while ordinary sends can use account and chat sources. */
internal fun emojiPickerShortcodes(
    emoji: CustomEmojiSet,
    purpose: EmojiPickerPurpose,
): List<String> =
    if (purpose == EmojiPickerPurpose.GROUP_IMAGE) {
        emptyList()
    } else {
        emoji.composerShortcodes.filterNot {
            purpose == EmojiPickerPurpose.CONFIGURE_QUICK_REACTION &&
                (emoji.usesInstalledArtwork(it) || emoji.usesConversationArtwork(it))
        }
    }

/** The picker's set sections and the custom emoji left over for the user's own section. */
internal class EmojiPickerSets(
    val custom: List<String>,
    val sets: List<EmojiSection>,
)

/**
 * One section per pre-installed set the user kept and per installed set the picker can send from, in
 * that order. Set emoji leave the user's own section so each one appears once.
 */
internal fun emojiPickerSets(
    emoji: CustomEmojiSet,
    purpose: EmojiPickerPurpose,
    hidden: Set<BuiltinEmojiSet>,
): EmojiPickerSets {
    val available = emojiPickerShortcodes(emoji, purpose)
    if (purpose == EmojiPickerPurpose.GROUP_IMAGE) {
        return EmojiPickerSets(available, emptyList())
    }
    val builtin =
        BuiltinEmojiSet.entries
            .filterNot { it in hidden }
            .map { EmojiSection(it, it.shortcodes) }
    val installedEntries = emoji.installed?.entries.orEmpty()
    val installed =
        available
            .filter { emoji.usesInstalledArtwork(it) }
            .groupBy { requireNotNull(installedEntries[it]).setCoordinate }
            .map { (coordinate, codes) ->
                // A set without a title falls back to its `d` identifier, the last part of the coordinate.
                val title =
                    requireNotNull(installedEntries[codes.first()]).setTitle?.takeIf { it.isNotBlank() }
                        ?: coordinate.substringAfterLast(':').ifBlank { coordinate }
                EmojiSection(InstalledEmojiSetTab(coordinate, title, codes.first()), codes)
            }
    val inSets = (builtin + installed).flatMapTo(mutableSetOf()) { it.emoji }
    val custom = available.filterNot { it in inSets || it in EmojiShortcodes.builtins }
    return EmojiPickerSets(custom, builtin + installed)
}
