@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.CustomEmojiScreen
import dev.ipf.whitenoise.android.ui.settings.EmojiSetsScreen

/**
 * Emoji sets or emoji settings over the open conversation, so browsing never leaves the chat or
 * its draft (#3882). [pages] is a back stack: Back pops one page and closes the overlay when empty.
 */
@Composable
internal fun ConversationEmojiLibraryOverlay(
    appState: WhiteNoiseAppState,
    pages: List<ConversationEmojiLibraryPage>,
    onPagesChange: (List<ConversationEmojiLibraryPage>) -> Unit,
) {
    val page = pages.lastOrNull() ?: return
    val back = { onPagesChange(pages.dropLast(1)) }
    PlaybackDialog(
        onDismissRequest = {},
        onSourceDismiss = { onPagesChange(emptyList()) },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        when (page) {
            ConversationEmojiLibraryPage.Sets -> EmojiSetsScreen(appState, onBack = back)
            ConversationEmojiLibraryPage.Settings -> {
                // Settings relies on its host for system Back; the sets screen handles its own.
                BackHandler(onBack = back)
                CustomEmojiScreen(
                    appState = appState,
                    onOpenSets = { onPagesChange(pages + ConversationEmojiLibraryPage.Sets) },
                    onBack = back,
                )
            }
        }
    }
}
