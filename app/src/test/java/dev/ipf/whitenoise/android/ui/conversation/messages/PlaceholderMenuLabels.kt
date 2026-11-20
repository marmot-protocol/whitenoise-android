package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

internal const val CODE_BODY = "secret code retained in protocol storage\n\n```\nval secret = 1\n```"

internal fun ComposeContentTestRule.openPlaceholderDeleteConfirmation(string: (Int) -> String) {
    onNodeWithText(string(R.string.select)).assertIsDisplayed()
    PLACEHOLDER_ABSENT_MENU_LABELS.forEach { label ->
        onNodeWithText(string(label), substring = false).assertDoesNotExist()
    }
    onNodeWithText(string(R.string.delete_for_me)).performClick()
}
