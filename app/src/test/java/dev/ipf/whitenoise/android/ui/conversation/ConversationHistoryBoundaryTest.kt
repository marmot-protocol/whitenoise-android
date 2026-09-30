package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The room policy appears once at an authoritative oldest edge, without per-row clocks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationHistoryBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Loading, failed, uninitialized, empty, and still-pageable histories never claim an old edge. */
    @Test
    fun boundaryRequiresSettledExhaustedRetainedHistory() {
        assertTrue(visible())
        assertFalse(visible(retentionSeconds = 0uL))
        assertFalse(visible(hasMessages = false))
        assertFalse(visible(initialLoadStarted = false))
        assertFalse(visible(hasMoreBefore = true))
        assertFalse(visible(isLoading = true))
        assertFalse(visible(isLoadingPage = true))
        assertFalse(visible(isLoadingOlder = true))
        assertFalse(visible(olderLoadFailed = true))
    }

    /** A policy change updates the single TalkBack sentence without exposing the visual dividers. */
    @Test
    fun boundarySemanticsFollowCurrentPolicy() {
        var duration by mutableStateOf(86_400uL)
        composeRule.setContent { WhiteNoiseTheme { ConversationHistoryBoundary(duration) } }
        composeRule
            .onAllNodesWithContentDescription("Earlier messages may have expired. Disappearing messages: 1 day.")
            .assertCountEquals(1)
        composeRule.onAllNodesWithText("Earlier messages may have expired").assertCountEquals(0)
        composeRule.runOnIdle { duration = 7uL * 86_400uL }
        composeRule
            .onAllNodesWithContentDescription(
                "Earlier messages may have expired. Disappearing messages: 1 week.",
            ).assertCountEquals(1)
    }

    /** Captures the compact light appearance at the oldest loaded edge. */
    @Test
    fun lightBoundary() = capture(dark = false, amoled = false, largeRtl = false, name = "light")

    /** Captures the same notice on a dark surface. */
    @Test
    fun darkBoundary() = capture(dark = true, amoled = false, largeRtl = false, name = "dark")

    /** Captures 200% text and mirrored layout on an AMOLED surface. */
    @Test
    fun amoledLargeRtlBoundary() = capture(dark = true, amoled = true, largeRtl = true, name = "amoled_large_rtl")

    private fun capture(
        dark: Boolean,
        amoled: Boolean,
        largeRtl: Boolean,
        name: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface {
                        Box(Modifier.width(360.dp).testTag("history-boundary")) {
                            ConversationHistoryBoundary(7_776_000uL)
                        }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("history-boundary")
            .captureRoboImage("src/test/snapshots/conversation_history_boundary_$name.png")
    }

    private fun visible(
        retentionSeconds: ULong = 86_400uL,
        hasMessages: Boolean = true,
        initialLoadStarted: Boolean = true,
        hasMoreBefore: Boolean = false,
        isLoading: Boolean = false,
        isLoadingPage: Boolean = false,
        isLoadingOlder: Boolean = false,
        olderLoadFailed: Boolean = false,
    ): Boolean =
        retentionHistoryBoundaryVisible(
            retentionSeconds,
            hasMessages,
            initialLoadStarted,
            hasMoreBefore,
            isLoading,
            isLoadingPage,
            isLoadingOlder,
            olderLoadFailed,
        )
}
