package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * The production app root mounted over [ReturnFixture], one level above [MountedContractShell]. The root, not the
 * shell, decides whether the shell is composed at all, so anything that depends on app lock is driven from here.
 */
internal class MountedContractApp(
    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    /** Mounts the production root on its ready surface at a single-pane width and always releases its jobs. */
    fun withMountedApp(
        fixture: ReturnFixture = ReturnFixture(),
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) {
        val mounted = mutableStateOf(true)
        val seededBootstrap = CompletableDeferred<Unit>()
        seedReadyStartup(fixture.appState, seededBootstrap)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(SINGLE_PANE)) {
                    WhiteNoiseTheme {
                        WhiteNoiseApp(
                            appState = fixture.appState,
                            mainShellStateHolder = holder,
                            warmResumeTraceToken = 1,
                            warmResumeEpoch = 1,
                        )
                    }
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
            seededBootstrap.complete(Unit)
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

    /** Shows the lock screen, as the app-lock decision does when the app returns after its delay. */
    fun lock(appState: WhiteNoiseAppState) {
        composeRule.runOnIdle { appState.setAppLockScreenVisibleForTest(true) }
        awaitCondition { shown(LOCK_SCREEN_TAG) }
        settle()
    }

    /** Hides the lock screen, as a successful unlock does, and lets the shell mount again. */
    fun unlock(appState: WhiteNoiseAppState) {
        composeRule.runOnIdle { appState.setAppLockScreenVisibleForTest(false) }
        awaitCondition { !shown(LOCK_SCREEN_TAG) }
        settle()
    }

    /** Delivers Back as the system does, to whichever window has focus, and lets the route settle. */
    fun systemBack() {
        Espresso.pressBack()
        settle()
    }

    /** True while a node carrying [tag] is composed. */
    fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** True while any composed node, merged or not, carries [text]. */
    fun reads(text: String): Boolean {
        val carriers = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        return carriers.fetchSemanticsNodes().isNotEmpty()
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

    /**
     * Puts [appState] on its ready surface with a bootstrap attempt already in flight, so the root's own bootstrap
     * call attaches to that attempt and never starts a native runtime.
     */
    private fun seedReadyStartup(
        appState: WhiteNoiseAppState,
        attempt: CompletableDeferred<Unit>,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(appState, AppPhase.Ready)
        runBlocking { appState.accountSession.bootstrapAttempts.currentOrStart { attempt } }
    }

    companion object {
        const val LOCK_SCREEN_TAG = "app.lock"
        private val SINGLE_PANE = DpSize(839.dp, 800.dp)
    }
}
