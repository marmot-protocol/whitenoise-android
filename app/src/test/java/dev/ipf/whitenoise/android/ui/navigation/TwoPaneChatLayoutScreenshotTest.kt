package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tablet chat layout: the list beside an empty pane, then beside an open chat (#3685). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class TwoPaneChatLayoutScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listBesideEmptyPaneLight() = capture(darkTheme = false, openChat = false, name = "two_pane_empty_light")

    @Test
    fun listBesideEmptyPaneDark() = capture(darkTheme = true, openChat = false, name = "two_pane_empty_dark")

    @Test
    fun listBesideOpenChatLight() = capture(darkTheme = false, openChat = true, name = "two_pane_open_chat_light")

    @Test
    fun listBesideOpenChatDark() = capture(darkTheme = true, openChat = true, name = "two_pane_open_chat_dark")

    private fun capture(
        darkTheme: Boolean,
        openChat: Boolean,
        name: String,
    ) {
        val fixture = ReturnFixture()
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(TABLET)) {
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
                composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").performClick()
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
        val TABLET = DpSize(1280.dp, 800.dp)
    }
}
