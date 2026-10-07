package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Wide, short screens retain an actual destination and confirmation action at maximum supported text size. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun largeLandscapeKeepsDestinationAndConfirmationReachable() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val state = appStateWithDirectChats(groupId to peerId, profiles = mutableMapOf(peerId to profile("Alice")))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                WhiteNoiseTheme {
                    ForwardMessagePickerContent(
                        appState = state,
                        messageCount = 11,
                        attachmentCount = 11,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                    )
                }
            }
        }
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Alice"))
        composeRule.onNodeWithText("Alice").performClick().assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("forward.destinations").getUnclippedBoundsInRoot()
        val viewportHeight = (viewport.bottom - viewport.top).value
        org.junit.Assert.assertTrue("A full minimum touch target must fit", viewportHeight >= 48f)
        composeRule.onNodeWithText("Forward to 1 chat").assertIsDisplayed()
    }

    @Test
    fun chatRowRemainsSelectableAndUnselectableAcrossRepeatedTaps() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val appState =
            appStateWithDirectChats(
                groupId to peerId,
                profiles = mutableMapOf(peerId to profile("Person 1")),
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessagePickerContent(
                        appState = appState,
                        messageCount = 2,
                        attachmentCount = 1,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                    )
                }
            }
        }

        val row = composeRule.onNodeWithText("Person 1")
        repeat(10) { tap ->
            row.performClick()
            if (tap % 2 == 0) row.assertIsSelected() else row.assertIsNotSelected()
        }
    }
}
