@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalWindowFolds
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowHeightClass
import kotlin.math.abs

internal const val TWO_PANE_LIST_TAG = "two_pane_list"
internal const val CONVERSATION_PANE_PLACEHOLDER_TAG = "conversation_pane_placeholder"

/**
 * Places the chat list beside the conversation when [twoPane] is set; [chatPaneSplit] decides
 * the proportions and keeps both panes off a half-open hinge.
 *
 * The conversation pane keeps the same composition position whether or not the list is shown, so
 * crossing the two-pane width or folding keeps the open conversation, its scroll position and its
 * draft.
 *
 * Side by side, each pane touches only one outer edge, so a camera cutout or navigation bar on the
 * other edge must not pad it: the list drops the end-side inset and the conversation the start-side.
 */
@Composable
internal fun TwoPaneChatFrame(
    twoPane: Boolean,
    listPane: @Composable () -> Unit,
    conversationPane: @Composable () -> Unit,
) {
    val shortWindow = LocalWindowLayout.current.heightClass == WindowHeightClass.Compact
    val folds = LocalWindowFolds.current.upright
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            if (twoPane) {
                Box(
                    Modifier
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                        .testTag(TWO_PANE_LIST_TAG),
                ) {
                    listPane()
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(
                if (twoPane) {
                    Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                } else {
                    Modifier
                },
            ) {
                conversationPane()
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (measurables.size == 1) {
            val conversation = measurables.single().measure(Constraints.fixed(width, height))
            return@Layout layout(width, height) { conversation.placeRelative(0, 0) }
        }
        val (list, divider, conversation) = measurables
        val dividerPlaceable = divider.measure(Constraints(minHeight = height, maxHeight = height))
        val split =
            chatPaneSplit(
                width = width,
                dividerWidth = dividerPlaceable.width,
                shortWindow = shortWindow,
                // Fold bounds are in window coordinates, so they only apply while the frame spans the window.
                folds = folds.takeIf { abs(width - windowWidthPx) <= 1 }.orEmpty(),
                layoutDirection = layoutDirection,
            )
        val listPlaceable = list.measure(Constraints.fixed(split.listWidth, height))
        val conversationPlaceable = conversation.measure(Constraints.fixed(width - split.conversationStart, height))
        layout(width, height) {
            listPlaceable.placeRelative(0, 0)
            dividerPlaceable.placeRelative(split.listWidth, 0)
            conversationPlaceable.placeRelative(split.conversationStart, 0)
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
