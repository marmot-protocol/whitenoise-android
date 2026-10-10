// Row heights and viewport sizes are fixed regression inputs shared by unit and device tests.
@file:Suppress("MagicNumber")

package dev.ipf.whitenoise.android.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.ConversationMentionJumpLayout
import dev.ipf.whitenoise.android.ui.conversation.ConversationReadingStartProbe
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.LazyListConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.conversationReadingLayoutInfo
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The row a reading-start command is aimed at. [bodyLines] makes the row as tall as its text, so a
 * large font scale changes its height; otherwise it is a fixed [heightDp].
 */
internal data class ReversedListTarget(
    val heightDp: Int,
    val index: Int = 8,
    val bodyLines: Int? = null,
) {
    val messageId: String
        get() = "message-$index"
}

/** The viewport, composer clearance and surrounding rows a reading-start command is measured in. */
internal data class ReversedListSpec(
    val target: ReversedListTarget,
    val viewportHeightDp: Int,
    val paddingDp: Int,
    val overlapDp: Int = 0,
    val rtl: Boolean = false,
    val initialIndex: Int = 0,
    val mixedRows: Boolean = false,
    val fontScale: Float = 1f,
)

/** The live list a command under test drives, with the same overlap-aware geometry the screen reads. */
internal class ReversedListHandle(
    val coordinator: ConversationScrollCoordinator,
    val listState: LazyListState,
    val spec: ReversedListSpec,
    private val overlapPx: Int,
) {
    val targetIndex: Int
        get() = spec.target.index

    val targetMessageId: String
        get() = spec.target.messageId

    /** Reads the clear viewport end and the row's measured height, never an estimate. */
    fun readLayout(index: Int): ConversationMentionJumpLayout {
        val layout = conversationReadingLayoutInfo(listState.layoutInfo, overlapPx)
        return ConversationMentionJumpLayout(
            viewportEndOffsetPx = layout.viewportEndOffset,
            itemHeightPx = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
        )
    }

    /** Identity-resolved access to the target row for the shared reading-start settle. */
    fun probe(): ConversationReadingStartProbe =
        ConversationReadingStartProbe(
            resolveTargetIndex = { targetIndex },
            readLayout = this::readLayout,
            traceSections = false,
        )
}

/**
 * A real reversed [LazyColumn] with the production scroll coordinator and writer, extracted from the
 * mention button's physical-bounds test so the notification landing tests share one proven geometry.
 * It never creates its own rule, so callers keep their Robolectric or device runner. The assertions
 * read unclipped bounds: a screenshot or a helper-only test is not placement proof.
 */
internal class ReversedReadingListFixture(
    private val composeRule: ComposeContentTestRule,
) {
    /** The last command's return value, null until it has run to completion. */
    var commandResult: Boolean? = null
        private set

    /** Mounts the list and a trigger that runs [command] against it under the spec's geometry. */
    fun mount(
        spec: ReversedListSpec,
        command: suspend ReversedListHandle.() -> Boolean,
    ) {
        commandResult = null
        composeRule.setContent {
            WhiteNoiseTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (spec.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(base.density, spec.fontScale),
                ) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = spec.initialIndex)
                    val scope = rememberCoroutineScope()
                    val density = LocalDensity.current
                    val handle =
                        remember(listState) {
                            val writer = LazyListConversationScrollWriter(listState)
                            ReversedListHandle(
                                coordinator = ConversationScrollCoordinator(writer),
                                listState = listState,
                                spec = spec,
                                overlapPx = with(density) { spec.overlapDp.dp.roundToPx() },
                            )
                        }
                    ReversedRows(spec, listState) {
                        scope.launch { commandResult = handle.command() }
                    }
                }
            }
        }
    }

    /** Runs the mounted command once and waits until the list, the command and its frames are idle. */
    fun trigger() {
        composeRule.onNodeWithTag(TRIGGER_TAG).performClick()
        composeRule.waitForIdle()
    }

    /** The target row's top edge, in dp from the root, ignoring clipping by the list. */
    fun targetTopDp(spec: ReversedListSpec): Float =
        composeRule
            .onNodeWithTag(spec.target.messageId)
            .getUnclippedBoundsInRoot()
            .top.value

    /** The list's own top edge, which is the reading start of whatever sits first below it. */
    fun listTopDp(): Float =
        composeRule
            .onNodeWithTag(LIST_TAG)
            .getUnclippedBoundsInRoot()
            .top.value

    /** Asserts the target's beginning meets the physical top of the list, and that the command finished. */
    fun assertTargetAtPhysicalTop(spec: ReversedListSpec) {
        assertEquals(listTopDp(), targetTopDp(spec), 1f)
        composeRule.runOnIdle { assertTrue("the command never completed", commandResult == true) }
    }

    /** The reversed list and its trigger, sized and padded by [spec]. */
    @Suppress("FunctionNaming", "LongMethod") // Compose naming, and one real-list layout shared by every geometry.
    @Composable
    private fun ReversedRows(
        spec: ReversedListSpec,
        listState: LazyListState,
        onTrigger: () -> Unit,
    ) {
        val targetIndex = spec.target.index
        Box(modifier = Modifier.fillMaxWidth().height(spec.viewportHeightDp.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
                reverseLayout = true,
                contentPadding = PaddingValues(bottom = spec.paddingDp.dp),
            ) {
                items(
                    (0..maxOf(targetIndex, spec.initialIndex) + 12).toList(),
                    key = { "message-$it" },
                ) { index ->
                    val isTarget = index == targetIndex
                    val rowModifier = Modifier.fillMaxWidth().testTag("message-$index")
                    if (isTarget && spec.target.bodyLines != null) {
                        Column(modifier = rowModifier) {
                            repeat(spec.target.bodyLines) { line -> Text("Message $index line $line") }
                        }
                    } else {
                        val height =
                            when {
                                isTarget -> spec.target.heightDp
                                spec.mixedRows && index % 9 == 0 -> 480
                                else -> 72
                            }
                        Text("Message $index", modifier = rowModifier.height(height.dp))
                    }
                }
            }
            TextButton(onClick = onTrigger, modifier = Modifier.testTag(TRIGGER_TAG)) { Text("go") }
        }
    }

    private companion object {
        const val LIST_TAG = "reversed-list"
        const val TRIGGER_TAG = "reversed-list-trigger"
    }
}
