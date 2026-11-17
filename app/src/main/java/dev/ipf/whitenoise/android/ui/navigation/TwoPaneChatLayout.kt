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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowHeightClass

/** Width of the chat list when it sits beside the conversation. */
internal val TwoPaneListWidth = 360.dp

/**
 * Share of the width after the divider that the chat list takes on short, wide windows such as a
 * phone held horizontally (#3866), up to [ShortWindowListMaxWidth]. From the 840dp expanded
 * breakpoint this is always wider than [TwoPaneListWidth].
 */
internal const val SHORT_WINDOW_LIST_FRACTION = 0.5f

/**
 * Widest the chat list grows on short windows. Phones held horizontally stay below it and keep the
 * even split; wider short windows, such as a tablet in split screen or a desktop window, give the
 * extra width to the conversation instead of the list.
 */
internal val ShortWindowListMaxWidth = 480.dp

internal const val TWO_PANE_LIST_TAG = "two_pane_list"
internal const val CONVERSATION_PANE_PLACEHOLDER_TAG = "conversation_pane_placeholder"

/**
 * Places the chat list beside the conversation on expanded-width windows.
 *
 * The conversation pane keeps the same composition position whether or not the list is shown, so
 * crossing the two-pane width keeps the open conversation, its scroll position and its draft.
 * Compact-height windows split the width evenly, up to [ShortWindowListMaxWidth], instead of fixing
 * the list at [TwoPaneListWidth], which would leave a phone held horizontally with a narrow list
 * beside a very wide conversation.
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
        val space = (width - dividerPlaceable.width).coerceAtLeast(0)
        // Truncating gives an odd leftover pixel to the conversation.
        val listWidth =
            if (shortWindow) {
                minOf((space * SHORT_WINDOW_LIST_FRACTION).toInt(), ShortWindowListMaxWidth.roundToPx())
            } else {
                TwoPaneListWidth.roundToPx()
            }.coerceAtMost(space)
        val listPlaceable = list.measure(Constraints.fixed(listWidth, height))
        val conversationPlaceable = conversation.measure(Constraints.fixed(space - listWidth, height))
        layout(width, height) {
            listPlaceable.placeRelative(0, 0)
            dividerPlaceable.placeRelative(listWidth, 0)
            conversationPlaceable.placeRelative(listWidth + dividerPlaceable.width, 0)
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
