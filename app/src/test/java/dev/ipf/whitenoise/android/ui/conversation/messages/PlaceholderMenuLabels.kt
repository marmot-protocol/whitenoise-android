package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.R

/** Actions a read-only deleted placeholder's long-press menu must never offer. */
internal val PLACEHOLDER_ABSENT_MENU_LABELS =
    listOf(
        R.string.delete,
        R.string.reply,
        R.string.edit,
        R.string.select_text,
        R.string.copy,
        R.string.read_aloud,
        R.string.forward,
        R.string.save_attachments,
        R.string.floating_keep,
        R.string.info,
    )
