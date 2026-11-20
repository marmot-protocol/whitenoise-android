package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Recreation through the production Activity, which reacquires the shell's state holder from its retained
 * ViewModel store. The composition-only restoration case lives in [ShellBackLadderContractTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi", application = ContractShellTestApplication::class)
class ShellActivityRecreationContractTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    /**
     * Chat details and the conversation controller survive the Activity being destroyed and created again, and
     * Back on the new Activity still returns to that conversation first.
     */
    @Test
    fun activityRecreationKeepsChatDetailsOpenOnTheSameController() {
        val fixture = ReturnFixture()
        val seededBootstrap = CompletableDeferred<Unit>()
        seedReadyStartup(fixture.appState, seededBootstrap)
        ContractShellTestApplication.fixtureAppState = fixture.appState
        try {
            ActivityScenario.launch(MainActivity::class.java).use { detailsSurviveRecreation(it, fixture) }
        } finally {
            seededBootstrap.complete(Unit)
            ContractShellTestApplication.fixtureAppState = null
        }
    }

    /** Opens chat details on [scenario]'s Activity, recreates it, and checks what the new Activity shows. */
    private fun detailsSurviveRecreation(
        scenario: ActivityScenario<MainActivity>,
        fixture: ReturnFixture,
    ) {
        run {
            var first: MainActivity? = null
            scenario.onActivity { first = it }
            val process = (requireNotNull(first).application as WhiteNoiseApplication).mainShellProcessState
            awaitCondition { rowShown(fixture) }
            composeRule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").performClick()
            awaitCondition { process.selectedChat.value?.id == fixture.target.groupIdHex }
            awaitCondition { composeRule.onAllNodes(conversationHeader()).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(conversationHeader()).performClick()
            awaitCondition { detailsShown() }
            val opened = fixture.appState.attachedConversationControllers().single()

            scenario.recreate()
            var second: MainActivity? = null
            scenario.onActivity { second = it }
            awaitCondition { detailsShown() }

            assertNotSame("recreation must build a new Activity", first, second)
            assertTrue("chat details must survive Activity recreation", detailsShown())
            assertSame(opened, fixture.appState.attachedConversationControllers().single())
            assertEquals(fixture.target.groupIdHex, process.selectedChat.value?.id)

            Espresso.pressBack()
            awaitCondition { !detailsShown() }
            assertEquals(fixture.target.groupIdHex, process.selectedChat.value?.id)
        }
    }

    /**
     * Puts [appState] on its ready surface with a bootstrap attempt already in flight, so the Activity's own
     * bootstrap call attaches to that attempt and never starts a native runtime.
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

    /** True while the target chat's list row is composed. */
    private fun rowShown(fixture: ReturnFixture): Boolean {
        val rows = composeRule.onAllNodesWithTag("chat.row.${fixture.target.groupIdHex}")
        return rows.fetchSemanticsNodes().isNotEmpty()
    }

    /** True while the chat details list is composed. */
    private fun detailsShown(): Boolean = composeRule.onAllNodesWithTag(DETAILS_TAG).fetchSemanticsNodes().isNotEmpty()

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** Waits for asynchronous shell settlement, idling the Robolectric main looper between checks. */
    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(WAIT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val DETAILS_TAG = "chat_info.list"
        const val WAIT_MILLIS = 10_000L
    }
}

/** The production application with startup services off and its app state supplied by the test. */
class ContractShellTestApplication : WhiteNoiseApplication() {
    override val appState: WhiteNoiseAppState
        get() = requireNotNull(fixtureAppState) { "the test must supply the app state before launch" }

    /** No background work or native bootstrap belongs to a route contract. */
    override fun onCreate() = Unit

    companion object {
        /** The fixture state the next launched Activity reads. */
        @Volatile
        var fixtureAppState: WhiteNoiseAppState? = null
    }
}
