package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.robolectric.Shadows.shadowOf

/** The production shell mounted over [ReturnFixture] for the route contract tests, with their shared waits. */
internal class MountedContractShell(
    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    /** Mounts the production shell under a test-controlled window width and always releases its jobs. */
    fun withMountedShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture = ReturnFixture(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) {
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalWindowLayout provides WindowLayout.fromSize(DpSize(width.value, SHELL_HEIGHT)),
                ) {
                    WhiteNoiseTheme { MainShell(appState = fixture.appState, stateHolder = holder) }
                }
            }
        }
        try {
            awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.waitForIdle()
            exercise(fixture, holder)
        } finally {
            composeRule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            composeRule.waitForIdle()
        }
    }

    /** Opens the fixture's target chat from its list row and waits for the route to settle. */
    fun openTarget(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").assertIsDisplayed().performClick()
        awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
        settle()
    }

    /** Lets route transitions and their one-frame retention finish. */
    fun settle() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** Polls only for observable asynchronous shell/controller settlement. */
    fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val SHELL_HEIGHT = 800.dp
    }
}
