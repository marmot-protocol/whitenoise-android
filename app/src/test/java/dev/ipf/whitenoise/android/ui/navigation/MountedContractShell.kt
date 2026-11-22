package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    private val restoration = StateRestorationTester(composeRule)

    /** Recreates the shell's composition from its saved instance state, as Activity recreation does. */
    fun recreate() {
        restoration.emulateSavedInstanceStateRestore()
        settle()
    }

    /** Mounts the production shell under a test-controlled window width and always releases its jobs. */
    fun withMountedShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture = ReturnFixture(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) = withMountedShell(
        width = width,
        fixture = fixture,
        shell = { MainShell(appState = fixture.appState, stateHolder = it) },
        exercise = exercise,
    )

    /**
     * Mounts the shell through the test's own [shell] call, for a test that feeds it an inbound route, and always
     * releases its jobs. [savedState] is the holder's Activity saved state, empty unless a test restores one.
     */
    fun withMountedShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture,
        shell: @Composable (MainShellStateHolder) -> Unit,
        savedState: SavedStateHandle = SavedStateHandle(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) {
        withGlobalSnapshotApply {
            val mounted = mutableStateOf(true)
            val holder = MainShellStateHolder(fixture.appState, savedState)
            val controller =
                holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
            fixture.appState.setAppInForeground(true)
            restoration.setContent {
                if (mounted.value) {
                    CompositionLocalProvider(
                        LocalWindowLayout provides WindowLayout.fromSize(DpSize(width.value, SHELL_HEIGHT)),
                    ) {
                        WhiteNoiseTheme { shell(holder) }
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
    }

    /**
     * Mounts the production shell like [withMountedShell] and hands [exercise] a way to emulate process death: the
     * composition's saved instance state and the holder's saved keys survive into a fresh holder over a fresh
     * process state, so nothing process-retained, the route owner included, comes back. The saved section is marked
     * as written by another process, as it is after a real process death, so the section state takes its
     * different-process branch. Returns the fresh holder.
     */
    fun withRestartableShell(
        width: MutableState<Dp>,
        fixture: ReturnFixture,
        exercise: (ReturnFixture, MainShellStateHolder, () -> MainShellStateHolder) -> Unit,
    ) {
        withGlobalSnapshotApply {
            val mounted = mutableStateOf(true)
            var saved = SavedStateHandle()
            val holders = mutableListOf(MainShellStateHolder(fixture.appState, saved))
            // A plain field, read only when the restored composition is created, so a restart never recomposes.
            var current = holders.single()
            val controller =
                current.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
            fixture.appState.setAppInForeground(true)
            restoration.setContent {
                if (mounted.value) {
                    CompositionLocalProvider(
                        LocalWindowLayout provides WindowLayout.fromSize(DpSize(width.value, SHELL_HEIGHT)),
                    ) {
                        WhiteNoiseTheme { MainShell(appState = fixture.appState, stateHolder = current) }
                    }
                }
            }
            val restart = {
                saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                if (saved.contains(SECTION_PROCESS_KEY)) saved[SECTION_PROCESS_KEY] = DEAD_PROCESS
                current = MainShellStateHolder(fixture.appState, saved, MainShellProcessState(fixture.appState))
                holders += current
                recreate()
                current
            }
            try {
                awaitCondition { controller.items.size == fixture.rows.size && !controller.isLoading }
                composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
                composeRule.waitForIdle()
                exercise(fixture, holders.first(), restart)
            } finally {
                composeRule.runOnIdle {
                    mounted.value = false
                    holders.asReversed().forEach { it.release() }
                }
                composeRule.waitForIdle()
            }
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

        /** The saved key naming the process that wrote the section. */
        const val SECTION_PROCESS_KEY = "main_shell_section_process"

        /** A process epoch no live process has. */
        const val DEAD_PROCESS = "a process that died"
    }
}
