@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleBottomAlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The side toggle uses the bubble's empty gutter, so it never changes the row's height or the column's width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageTranslationToggleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val source = TranslationSource("message", "Short line", "de")
    private val translationLabel get() = RuntimeEnvironment.getApplication().getString(R.string.translation_title)
    private val loadingLabel get() = RuntimeEnvironment.getApplication().getString(R.string.translation_loading)

    private lateinit var session: ConversationTranslationSession
    private var swipe by mutableIntStateOf(0)

    private data class RowConfig(
        val mine: Boolean,
        val group: Boolean,
        val reactions: Boolean,
        val selection: Boolean,
        val text: String,
        val direction: LayoutDirection,
    )

    private var config by mutableStateOf<RowConfig?>(null)

    /** Sets content once per test; later calls swap the keyed row so loops start from a fresh session. */
    private fun setRow(
        mine: Boolean = false,
        group: Boolean = false,
        reactions: Boolean = false,
        selection: Boolean = false,
        text: String = source.text,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val next = RowConfig(mine, group, reactions, selection, text, direction)
        if (config != null) {
            composeRule.runOnIdle { config = next }
            composeRule.waitForIdle()
            return
        }
        config = next
        composeRule.setContent {
            val current = config ?: return@setContent
            key(current) {
                WhiteNoiseTheme(darkTheme = false) {
                    val scope = rememberCoroutineScope()
                    session = remember { ConversationTranslationSession(scope, { awaitCancellation() }, { true }) }
                    CompositionLocalProvider(
                        LocalConversationTranslation provides session,
                        LocalLayoutDirection provides current.direction,
                    ) {
                        MessageRow(current.mine, current.group, current.reactions, current.selection, current.text)
                    }
                }
            }
        }
    }

    /** Mirrors the transcript row: avatar slot, then the bubble column with the toggle as its gutter-side sibling. */
    @Composable
    private fun MessageRow(
        mine: Boolean,
        group: Boolean,
        reactions: Boolean,
        selection: Boolean,
        text: String,
    ) {
        val alignsByBottom = reactions && (group || !selection)
        Row(
            modifier = Modifier.width(360.dp).testTag("row"),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group) {
                // The production avatar slot is a 30 dp box using exactly this row alignment.
                Box(Modifier.size(30.dp).testTag("avatar").alignBy { it.measuredHeight })
            }
            val toggle: @Composable () -> Unit = {
                val rowAlignment =
                    if (alignsByBottom) Modifier.alignBy { it.measuredHeight } else Modifier.align(Alignment.Bottom)
                MessageTranslationToggle(
                    source = source,
                    modifier =
                        Modifier
                            .width(MessageTranslationToggleSlotWidth)
                            .then(rowAlignment)
                            .absoluteOffset { IntOffset(swipe, 0) },
                )
            }
            if (mine && !selection) toggle()
            Column(
                modifier =
                    Modifier
                        .absoluteOffset { IntOffset(swipe, 0) }
                        .widthIn(max = 220.dp)
                        .testTag("column")
                        .then(if (alignsByBottom) Modifier.alignBy(MessageBubbleBottomAlignmentLine) else Modifier),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Box(Modifier.testTag("bubble")) {
                    MessageBubbleFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = mine),
                        highlighted = false,
                        mine = mine,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                    ) { Text(text) }
                }
                if (reactions) {
                    MessageReactionSummary(
                        tallies = listOf(ReactionTally(emoji = "👍", count = 1, mine = false)),
                        mine = mine,
                        onClick = {},
                    )
                }
            }
            if (!mine && !selection) toggle()
        }
    }

    private fun height(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }

    private fun finishTranslation() {
        composeRule.runOnIdle { session.renditions[source] = TranslationRendition("Eine Zeile") }
        composeRule.waitForIdle()
    }

    private fun startPending() {
        composeRule.runOnIdle { session.request(source) }
        composeRule.waitForIdle()
    }

    @Test
    fun rowHeightIgnoresPendingAndFinishedToggle() {
        for (reactions in listOf(false, true)) {
            setRow(reactions = reactions)
            val plain = height("row")
            startPending()
            composeRule.onNodeWithTag(MESSAGE_TRANSLATION_PENDING_TEST_TAG).assertExists()
            assertEquals(plain, height("row"))
            composeRule.runOnIdle { session.clear() }
            finishTranslation()
            composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).assertExists()
            assertEquals(plain, height("row"))
        }
    }

    @Test
    fun senderAvatarStaysOnBubbleBottomWithToggle() {
        setRow(group = true, reactions = true)
        val before = composeRule.onNodeWithTag("avatar").getBoundsInRoot().bottom
        assertEquals(composeRule.onNodeWithTag("bubble").getBoundsInRoot().bottom, before)
        finishTranslation()
        composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).assertExists()
        assertEquals(
            composeRule.onNodeWithTag("bubble").getBoundsInRoot().bottom,
            composeRule.onNodeWithTag("avatar").getBoundsInRoot().bottom,
        )
        // The toggle sits beside the bubble, above the reaction chips.
        assertTrue(
            composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).getBoundsInRoot().bottom <=
                composeRule.onNodeWithTag("bubble").getBoundsInRoot().bottom,
        )
    }

    @Test
    fun toggleIsASwitchWithStableDescription() {
        setRow()
        finishTranslation()
        val toggle = composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG)
        toggle
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(translationLabel)))
        toggle.performClick()
        composeRule.waitForIdle()
        assertEquals(false, session.renditions[source]?.showTranslation)
        toggle
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(translationLabel)))
    }

    @Test
    fun pendingRingIsLabelledPoliteLiveRegion() {
        setRow()
        startPending()
        composeRule
            .onNodeWithTag(MESSAGE_TRANSLATION_PENDING_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(loadingLabel)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onAllNodesWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun toggleIsAbsentInSelectionMode() {
        setRow(selection = true)
        finishTranslation()
        composeRule.onAllNodesWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun toggleSitsOnTheGutterSideInBothDirections() {
        for (direction in LayoutDirection.entries) {
            for (mine in listOf(false, true)) {
                setRow(mine = mine, direction = direction)
                finishTranslation()
                val column = composeRule.onNodeWithTag("column").getBoundsInRoot()
                val toggle = composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).getBoundsInRoot()
                val onRight = (!mine) == (direction == LayoutDirection.Ltr)
                if (onRight) {
                    assertTrue("$direction mine=$mine", toggle.left >= column.right)
                } else {
                    assertTrue("$direction mine=$mine", toggle.right <= column.left)
                }
            }
        }
    }

    @Test
    fun columnWidthIsUnchangedByToggle() {
        val long = "A much longer message that wraps across several lines in the bubble to reach its width cap"
        for (text in listOf(source.text, long)) {
            setRow(text = text)
            val before = composeRule.onNodeWithTag("column").getBoundsInRoot().let { it.right - it.left }
            finishTranslation()
            composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).assertExists()
            assertEquals(before, composeRule.onNodeWithTag("column").getBoundsInRoot().let { it.right - it.left })
        }
    }

    @Test
    fun toggleTravelsWithTheBubbleDuringReplySwipe() {
        setRow()
        finishTranslation()
        val column = composeRule.onNodeWithTag("column").getBoundsInRoot().left
        val toggle = composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).getBoundsInRoot().left
        composeRule.runOnIdle { swipe = 40 }
        composeRule.waitForIdle()
        assertEquals(
            composeRule.onNodeWithTag("column").getBoundsInRoot().left - column,
            composeRule.onNodeWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG).getBoundsInRoot().left - toggle,
        )
        assertTrue(composeRule.onNodeWithTag("column").getBoundsInRoot().left > column)
    }
}
