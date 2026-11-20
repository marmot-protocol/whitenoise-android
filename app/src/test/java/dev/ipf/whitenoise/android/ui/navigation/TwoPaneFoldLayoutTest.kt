package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalVerticalWindowFold
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.VerticalWindowFold
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A half-open foldable puts the chat list and the conversation on either side of the hinge (#3685). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w800dp-h700dp-mdpi")
class TwoPaneFoldLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Below the expanded width only a separating fold shows the list beside the conversation. */
    @Test
    fun aSeparatingFoldShowsTheListBesideTheConversationAtMediumWidth() {
        val medium = WindowLayout.fromSize(WINDOW)
        val expanded = WindowLayout.fromSize(DpSize(840.dp, 700.dp))

        assertFalse(listBesideConversation(medium, fold = null))
        assertTrue(listBesideConversation(medium, VerticalWindowFold(400.dp, 400.dp)))
        assertTrue(listBesideConversation(expanded, fold = null))
    }

    /** On a seamless fold the divider sits on the fold and each pane fills its own side. */
    @Test
    fun eachPaneFillsItsOwnSideOfTheFold() {
        mountFrame(VerticalWindowFold(400.dp, 400.dp))

        assertPanes(list = 0.dp to 400.dp, conversation = 400.dp to 800.dp)
    }

    /** A hinge with a physical gap leaves the gap empty. */
    @Test
    fun neitherPaneIsDrawnUnderAHinge() {
        mountFrame(VerticalWindowFold(390.dp, 410.dp))

        assertPanes(list = 0.dp to 390.dp, conversation = 410.dp to 800.dp)
    }

    /** Right to left, the list fills the right side of the fold, whatever its width. */
    @Test
    fun rightToLeftPutsTheListOnTheRightOfTheFold() {
        mountFrame(VerticalWindowFold(340.dp, 340.dp), LayoutDirection.Rtl)

        assertPanes(list = 340.dp to 800.dp, conversation = 0.dp to 340.dp)
    }

    /** Fold bounds are window coordinates; a frame inset from the window keeps the regular split. */
    @Test
    fun aFrameNarrowerThanTheWindowIgnoresTheFold() {
        mountFrame(VerticalWindowFold(400.dp, 400.dp), frameWidth = 700.dp)

        assertPanes(list = 0.dp to TwoPaneListWidth, conversation = TwoPaneListWidth to 700.dp)
    }

    private fun mountFrame(
        fold: VerticalWindowFold,
        direction: LayoutDirection = LayoutDirection.Ltr,
        frameWidth: Dp? = null,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout.fromSize(WINDOW),
                LocalVerticalWindowFold provides fold,
                LocalLayoutDirection provides direction,
            ) {
                if (frameWidth == null) {
                    Frame()
                } else {
                    Box(Modifier.size(frameWidth, WINDOW.height)) { Frame() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Suppress("FunctionNaming")
    @Composable
    private fun Frame() {
        TwoPaneChatFrame(
            twoPane = listBesideConversation(LocalWindowLayout.current, LocalVerticalWindowFold.current),
            listPane = { Text("list") },
        ) {
            Box(Modifier.fillMaxSize().testTag(CONVERSATION_TAG))
        }
    }

    private fun assertPanes(
        list: Pair<Dp, Dp>,
        conversation: Pair<Dp, Dp>,
    ) {
        assertSpan("list", list, composeRule.onNodeWithTag(TWO_PANE_LIST_TAG).getUnclippedBoundsInRoot())
        assertSpan("conversation", conversation, composeRule.onNodeWithTag(CONVERSATION_TAG).getUnclippedBoundsInRoot())
    }

    private fun assertSpan(
        name: String,
        expected: Pair<Dp, Dp>,
        bounds: DpRect,
    ) {
        // The seamless fold's divider is 1dp wide, so the conversation may start one dp later.
        assertEquals("$name start", expected.first.value, bounds.left.value, TOLERANCE)
        assertEquals("$name end", expected.second.value, bounds.right.value, TOLERANCE)
    }

    private companion object {
        val WINDOW = DpSize(800.dp, 700.dp)
        const val CONVERSATION_TAG = "fold_conversation"
        const val TOLERANCE = 1f
    }
}
