package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.ui.CustomEmojiSet

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
