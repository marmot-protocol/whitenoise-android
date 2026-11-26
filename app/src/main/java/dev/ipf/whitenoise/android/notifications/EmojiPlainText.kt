package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Resolve device emoji before a background surface publishes a label from a cold process. */
internal suspend fun Context.prepareEmojiLabels(labels: Iterable<String?>) {
    val app = applicationContext as? WhiteNoiseApplication ?: return
    if (labels.none { label ->
            label != null &&
                EmojiShortcodes.presentationShortcodes(label).any {
                    EmojiShortcodes.builtinDrawable(it) == null
                }
        }
    ) {
        return
    }
    runCatchingCancellable { app.customEmojiStore.load() }
}

internal suspend fun Context.prepareNotificationEmojiLabels(
    update: NotificationUpdateFfi,
    conversationTitle: String?,
    senderName: String?,
    recipientLabel: String?,
) {
    if (!NotificationPreviewPreferences.capture(this).allowed) return
    prepareEmojiLabels(
        listOf(
            conversationTitle,
            update.groupName,
            senderName,
            update.sender.displayName,
            update.receiver.displayName,
            recipientLabel,
        ),
    )
}

/**
 * [text] for a surface that can only draw text (notification names, shortcut labels, widget and tile text): every
 * `:shortcode:` that renders as artwork in the app reads as its name, the way a Unicode emoji reads natively.
 * Uses the device's own emoji already in memory, so before the store has loaded only built-ins are replaced.
 */
internal fun Context.emojiPlainText(text: String): String {
    if (':' !in text) return text
    val custom = (applicationContext as? WhiteNoiseApplication)?.customEmojiStore?.emoji ?: CustomEmojiSet.Empty
    return EmojiShortcodes.plainText(text, custom, resources)
}

/** Freeze the local catalog so the first-write projection and presenter format the same labels. */
internal fun Context?.notificationEmojiLabelFormatter(): (String) -> String {
    val resources = this?.resources ?: return { it }
    val custom = (applicationContext as? WhiteNoiseApplication)?.customEmojiStore?.emoji ?: CustomEmojiSet.Empty
    return { EmojiShortcodes.plainText(it, custom, resources) }
}
