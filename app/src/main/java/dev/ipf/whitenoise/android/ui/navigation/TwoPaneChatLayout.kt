package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Width of the chat list when it sits beside the conversation. */
internal val TwoPaneListWidth = 360.dp

internal const val TWO_PANE_LIST_TAG = "two_pane_list"
internal const val CONVERSATION_PANE_PLACEHOLDER_TAG = "conversation_pane_placeholder"

/**
 * Places the chat list beside the conversation on expanded-width windows.
 *
 * The conversation pane keeps the same composition position whether or not the list is shown, so
 * crossing the two-pane width keeps the open conversation, its scroll position and its draft.
 */
@Composable
internal fun TwoPaneChatFrame(
    twoPane: Boolean,
    listPane: @Composable () -> Unit,
    conversationPane: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (twoPane) {
            Box(
                Modifier
                    .width(TwoPaneListWidth)
                    .fillMaxHeight()
                    .testTag(TWO_PANE_LIST_TAG),
            ) {
                listPane()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            conversationPane()
        }
    }
}

/** Fills the conversation pane while no chat is open beside the list. */
@Composable
internal fun ConversationPanePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(CONVERSATION_PANE_PLACEHOLDER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.conversation_pane_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
