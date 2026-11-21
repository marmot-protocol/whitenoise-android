package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalWindowFolds
import dev.ipf.whitenoise.android.ui.common.WindowFold
import dev.ipf.whitenoise.android.ui.common.WindowFolds
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In the tabletop posture the composer sits just below the hinge (#3685). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w412dp-h900dp-mdpi")
class ConversationTabletopTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The bottom bar reserves from the top of the hinge down, keeping the hinge gap empty. */
    @Test
    fun reserveStartsAtTheTopOfTheHinge() {
        assertEquals(ConversationTabletop(0.dp, 0.dp), conversationTabletop(null, 900.dp))
        val gapped = WindowFold(450.dp, 460.dp, separating = true)
        assertEquals(ConversationTabletop(450.dp, 10.dp), conversationTabletop(gapped, 900.dp))
    }

    /**
     * Half open, the bottom bar fills the lower half with the composer at its top, so the messages
     * end at the hinge; opening flat returns the composer to the bottom edge. The composer keeps its
     * composition either way, and with it the draft.
     */
    @Test
    fun composerMovesToTheHingeAndKeepsItsState() {
        val tabletop = mutableStateOf(true)
        val identities = mutableListOf<Any>()
        var windowHeight = 0.dp
        composeRule.setContent {
            windowHeight = LocalWindowInfo.current.containerDpSize.height
            val folds =
                if (tabletop.value) {
                    val hinge = WindowFold(windowHeight / 2 - GAP, windowHeight / 2 + GAP, separating = true)
                    WindowFolds(tabletop = hinge)
                } else {
                    WindowFolds.None
                }
            CompositionLocalProvider(LocalWindowFolds provides folds) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag(MESSAGES))
                    ConversationBottomBarFrame(routeMorph = null) {
                        val identity = remember { Any() }
                        SideEffect { if (identities.lastOrNull() !== identity) identities += identity }
                        Box(Modifier.fillMaxWidth().height(COMPOSER_HEIGHT).testTag(COMPOSER))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Neither the messages nor the composer is drawn under the hinge.
        assertDp(windowHeight / 2 - GAP, bottom(MESSAGES))
        assertDp(windowHeight / 2 + GAP, top(COMPOSER))

        tabletop.value = false
        composeRule.waitForIdle()
        assertDp(windowHeight - COMPOSER_HEIGHT, top(COMPOSER))
        assertDp(windowHeight, bottom(COMPOSER))
        assertEquals(1, identities.size)
    }

    private fun top(tag: String) = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    private fun bottom(tag: String) = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom

    private fun assertDp(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, TOLERANCE)

    private companion object {
        const val MESSAGES = "tabletop_messages"
        const val COMPOSER = "tabletop_composer"
        val COMPOSER_HEIGHT = 72.dp
        val GAP = 5.dp
        const val TOLERANCE = 1f
    }
}
