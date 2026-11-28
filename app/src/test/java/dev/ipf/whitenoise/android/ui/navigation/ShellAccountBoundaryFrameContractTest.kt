package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The account boundary as a reader would see it: from the first frame composed after another account becomes
 * active, nothing of the previous account's open conversation is composed, with no exit motion carrying it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellAccountBoundaryFrameContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /** Every frame after the switch is free of the previous account's message and of its composer. */
    @Test
    fun noFrameAfterAnAccountSwitchComposesThePreviousConversation() {
        val message =
            timelineRecord("ab".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = PREVIOUS_ACCOUNT_MESSAGE)
        val script = ScriptedConversationTimelineSubscription(timelinePage(message))
        val fixture = ReturnFixture(timelineScripts = listOf(script))
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), fixture) { _, holder ->
            shell.openTarget(fixture, holder)
            shell.awaitCondition { composed(PREVIOUS_ACCOUNT_MESSAGE) == 1 }
            // Both markers are composed before the switch, so their later absence means something.
            assertEquals(1, composerCount())
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            composeRule.mainClock.autoAdvance = false
            composeRule.runOnUiThread {
                fixture.appState.switchActiveAccountForContract(OTHER_ACCOUNT_REF)
                // The runtime delivers a main-thread write before the next frame. With the clock held the test
                // must deliver it, or the first frame would be composed from state that never saw the switch.
                Snapshot.sendApplyNotifications()
            }
            val selectedAtFrame = mutableListOf<String?>()
            val leaked =
                (1..FRAMES_WATCHED).filter {
                    composeRule.mainClock.advanceTimeByFrame()
                    shadowOf(Looper.getMainLooper()).idle()
                    selectedAtFrame += holder.selectedChat.value?.id
                    composed(PREVIOUS_ACCOUNT_MESSAGE) > 0 || composerCount() > 0
                }
            composeRule.mainClock.autoAdvance = true

            assertEquals("frames that still composed the previous account's conversation", emptyList<Int>(), leaked)
            val stillSelected = selectedAtFrame.filterNotNull()
            assertEquals("the selection is clear from the first frame on", emptyList<String>(), stillSelected)
            // Frame sampling cannot see a route an effect clears before the sample, the committed record can.
            val first = shell.firstCommitFor(OTHER_ACCOUNT_REF)
            assertEquals("the first composition after the switch composed only the list", CHATS_ROOT, first.stack)
        }
    }

    /** How many composed nodes carry [text], counted in the unmerged tree so no parent can hide one. */
    private fun composed(text: String): Int {
        val carriers = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        return carriers.fetchSemanticsNodes().size
    }

    /** How many text inputs are composed, which is the conversation composer on this route. */
    private fun composerCount(): Int = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size

    private companion object {
        val COMPACT_WIDTH = 660.dp
        const val OTHER_ACCOUNT_REF = "other-contract-account"
        const val PREVIOUS_ACCOUNT_MESSAGE = "previous account only message"

        /** Longer than any route exit motion, so retained exit content would be seen. */
        const val FRAMES_WATCHED = 60
    }
}

/** Uses the same private account setter as the mounted settings isolation tests. */
private fun WhiteNoiseAppState.switchActiveAccountForContract(accountRef: String?) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(this, accountRef)
}
