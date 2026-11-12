package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.MessageStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the inline Read More and the existing Show less action on the production bubble. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w400dp-h2400dp-mdpi")
class MessageShowLessBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    /** Read More expands under either chat default; the same bubble can collapse and expand again. */
    @Test
    fun readMoreExpandsInlineWhenAutomaticCollapseIsEnabled() = assertInlineRoundTrip(collapseByDefault = true)

    /** Show less can also override the default when long messages normally start expanded. */
    @Test
    fun explicitCollapseCanBeExpandedInlineWhenAutomaticCollapseIsDisabled() = assertInlineRoundTrip(collapseByDefault = false)

    /** Measures the rendered bubble to confirm that tapping Read More removes the preview height limit. */
    private fun assertInlineRoundTrip(collapseByDefault: Boolean) {
        val fixture =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = false, media = false)
        val longBody = (1..90).joinToString("\n") { "Message line $it" }
        val item = fixture.item.copy(record = fixture.item.record.copy(plaintext = longBody), projected = null)
        var surface by mutableStateOf(fixture.copy(item = item))
        var menuOpen by mutableStateOf(false)
        composeRule.setContent {
            SwipeTestBubbleHost(
                surface = surface,
                collapseLongMessages = collapseByDefault,
                isActionMenuOpen = menuOpen,
                onActionMenuOpenChange = { menuOpen = it },
            )
        }
        val bubble = composeRule.onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex), useUnmergedTree = true)
        if (!collapseByDefault) {
            bubble.performTouchInput { longClick() }
            composeRule.onNodeWithText("Show less").performClick()
        }
        val readMore = visibleReadMore()
        val collapsedBounds = bubble.getUnclippedBoundsInRoot()
        val collapsedHeight = collapsedBounds.bottom - collapsedBounds.top
        readMore.performClick()
        readMore.assertDoesNotExist()
        val expandedBounds = bubble.getUnclippedBoundsInRoot()
        assertTrue(expandedBounds.bottom - expandedBounds.top > collapsedHeight)

        bubble.performTouchInput { longClick() }
        composeRule.onNodeWithText("Show less").performClick()
        readMore.assertExists()
        readMore.performClick()
        composeRule.runOnIdle {
            surface = surface.copy(item = item.copy(record = item.record.copy(plaintext = "Short edit")))
            menuOpen = true
        }
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    /** Finds the real footer action while ignoring the zero-size measurement copy. */
    private fun visibleReadMore() =
        composeRule.onNode(
            hasText("Read More", ignoreCase = true) and SemanticsMatcher("visible footer action") { it.boundsInRoot.width > 0f },
        )

    @Test
    fun failedRowWithoutFailureProjectionDoesNotOfferShowLess() {
        val fixture =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        val longBody = (1..90).joinToString("\n") { "Failed message line $it" }
        val item =
            fixture.item.copy(
                record = fixture.item.record.copy(plaintext = longBody),
                status = MessageStatus.Failed,
                projected = null,
            )
        composeRule.setContent {
            SwipeTestBubbleHost(
                surface = fixture.copy(item = item),
                collapseLongMessages = false,
                isActionMenuOpen = true,
            )
        }
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    /** A row that becomes a failed send after expansion loses its collapse action. */
    @Test
    fun expandedBubbleStopsOfferingShowLessWhenTheRowBecomesFailed() {
        val fixture =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        val longBody = (1..90).joinToString("\n") { "Message line $it" }
        var surface by mutableStateOf(
            fixture.copy(
                item =
                    fixture.item.copy(
                        record = fixture.item.record.copy(plaintext = longBody),
                        status = MessageStatus.Sent,
                        projected = null,
                    ),
            ),
        )
        var menuOpen by mutableStateOf(false)
        composeRule.setContent {
            SwipeTestBubbleHost(
                surface = surface,
                collapseLongMessages = true,
                isActionMenuOpen = menuOpen,
                onActionMenuOpenChange = { menuOpen = it },
            )
        }
        visibleReadMore().performClick()
        composeRule.runOnIdle { surface = surface.copy(item = surface.item.copy(status = MessageStatus.Failed)) }
        composeRule.runOnIdle { menuOpen = true }
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    @Test
    fun shortExpandedBodyNeverOffersRedundantCollapse() {
        val surface =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        composeRule.setContent {
            SwipeTestBubbleHost(surface = surface, collapseLongMessages = false, isActionMenuOpen = true)
        }
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }
}
