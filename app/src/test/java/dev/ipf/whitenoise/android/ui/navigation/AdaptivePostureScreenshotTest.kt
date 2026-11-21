package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowFolds
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowFold
import dev.ipf.whitenoise.android.ui.common.WindowFolds
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat layout on foldables and flip phones (#3685): an unfolded book-style foldable, the same
 * device held half open, a flip phone in the tabletop posture and a flip phone's cover screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptivePostureScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Golden: an unfolded book-style foldable in portrait fits the list beside the chat. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w673dp-h841dp-mdpi")
    fun unfoldedPortraitFoldable() = capture("posture_unfolded_portrait_foldable") { WindowFolds.None }

    /** Golden: the half-open book splits the panes at the hinge. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w800dp-h700dp-mdpi")
    fun halfOpenBookFoldable() = capture("posture_half_open_book", folds = ::bookFold)

    /** Golden: an RTL book layout puts the chat list to the right of the hinge. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-rEG-ldrtl-w673dp-h841dp-mdpi")
    fun halfOpenBookFoldableRtl() = capture("posture_half_open_book_rtl", darkTheme = true, folds = ::bookFold)

    /** Golden: large text in the 300dp list and its adjacent conversation. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w673dp-h841dp-mdpi")
    fun unfoldedPortraitFoldableLargeText() = captureLargeText("posture_unfolded_portrait_large_text")

    /** Golden: a flip phone held half open reads above the hinge and types below it. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w412dp-h900dp-mdpi")
    fun tabletopFlipPhone() =
        capture("posture_tabletop_flip", darkTheme = true) { size ->
            WindowFolds(tabletop = WindowFold(size.height / 2, size.height / 2, separating = true))
        }

    /** Golden: a flip phone's small square cover screen keeps the chat usable. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w340dp-h360dp-mdpi")
    fun flipCoverScreenChat() = capture("posture_flip_cover_chat") { WindowFolds.None }

    /** Golden: the cover screen's chat list. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w340dp-h360dp-mdpi")
    fun flipCoverScreenList() = capture("posture_flip_cover_list", openChat = false) { WindowFolds.None }

    /** Golden: the cover-screen composer remains reachable at large text size. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w340dp-h360dp-mdpi")
    fun flipCoverScreenChatLargeText() = captureLargeText("posture_flip_cover_chat_large_text")

    private fun bookFold(size: DpSize) = WindowFolds(
        upright = listOf(WindowFold(size.width / 2, size.width / 2, separating = true)),
    )

    private fun captureLargeText(name: String) {
        RuntimeEnvironment.setFontScale(LARGE_FONT_SCALE)
        try {
            capture(name, darkTheme = true) { WindowFolds.None }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun capture(
        name: String,
        darkTheme: Boolean = false,
        openChat: Boolean = true,
        folds: (DpSize) -> WindowFolds,
    ) {
        val fixture = ReturnFixture()
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) {
                val window = LocalWindowInfo.current.containerDpSize
                CompositionLocalProvider(
                    LocalWindowLayout provides WindowLayout.fromSize(window),
                    LocalWindowFolds provides folds(window),
                ) {
                    WhiteNoiseTheme(darkTheme = darkTheme) {
                        MainShell(appState = fixture.appState, stateHolder = holder)
                    }
                }
            }
        }
        try {
            awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.waitForIdle()
            if (openChat) {
                val row = "chat.row.${fixture.target.groupIdHex}"
                composeRule.onNodeWithTag("chats.list").performScrollToNode(hasTestTag(row))
                composeRule.onNodeWithTag(row).performClick()
                awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
                composeRule.mainClock.advanceTimeBy(1_000)
                composeRule.waitForIdle()
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        } finally {
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val LARGE_FONT_SCALE = 1.3f
    }
}
