package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.ui.conversation.reactions.CompleteReactionDetailsSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the shared sheet with a real controller and a native transport returning more than the preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class CompleteReactionDetailsIntegrationTest : GroupSystemReactionTestFixtures() {
    @get:Rule val composeRule = createComposeRule()

    /** Releases the owner and its coroutines after each composition. */
    @After fun clearController() = pollController.onCleared()

    /** Activity details read the exact target and re-read when only the authoritative window revision changes. */
    @Test fun activityDetailsUseCanonicalReadAndRefreshBeyondThePreview() {
        val item = activity()
        val initial = reactions(item.record.messageIdHex, "03")
        reactionDetailsResponder = { initial }
        val dismissed = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (!dismissed.value) {
                    CompleteReactionDetailsSheet(item, pollController, pollState, null, { dismissed.value = true })
                }
            }
        }
        awaitText("All · 4")
        composeRule.onNodeWithText("👍 3").performClick().assertIsSelected()
        assertEquals(
            listOf("personal", item.record.groupIdHex, item.record.messageIdHex),
            recordedCalls().first { it.first == "messageReactions" }.second,
        )
        val replacement = reactions(item.record.messageIdHex, "05")
        composeRule.runOnIdle {
            reactionDetailsResponder = { replacement }
            pollController.window.install(
                MarmotWindowTestFakes.conversationFrame("group").copy(revision = ConversationWindowRevisionFfi("details", 2uL)),
            )
        }
        awaitText(participantLabel(replacement[2].sender))
        composeRule.onNodeWithText("👍 3").assertIsSelected()
        composeRule.onNodeWithText(participantLabel(initial[2].sender)).assertDoesNotExist()
        assertFalse(dismissed.value)
    }

    /** An initial failure stays open with Retry; changing the message starts a separate complete read. */
    @Test fun retryAndMessageReplacementNeverReuseThePreviousSnapshot() {
        val first = activity()
        val current = mutableStateOf(first)
        reactionDetailsResponder = { error("read failed") }
        composeRule.setContent {
            WhiteNoiseTheme { CompleteReactionDetailsSheet(current.value, pollController, pollState, null, {}) }
        }
        awaitText("Retry")
        composeRule.runOnIdle { reactionDetailsResponder = { reactions(it[2] as String, "03") } }
        composeRule.onNodeWithText("Retry").performClick()
        awaitText("All · 4")
        val second = first.copy(record = first.record.copy(messageIdHex = "ff".repeat(32)))
        composeRule.runOnIdle {
            reactionDetailsResponder = { listOf(reactions(it[2] as String, "06")[2]) }
            current.value = second
        }
        awaitText("All · 1")
        assertEquals(second.record.messageIdHex, recordedCalls().last { it.first == "messageReactions" }.second[2])
        composeRule.onNodeWithText("All · 4").assertDoesNotExist()
    }

    /** Unknown reactors still have distinct stable identity labels before profile resolution. */
    private fun participantLabel(sender: String): String =
        pollState.displayName(sender).ifBlank {
            pollState.shortNpub(sender).ifBlank { IdentityFormatter.short(sender, prefix = 10, suffix = 8) }
        }

    /** Waits for the off-main exact-message read rather than relying on Compose's animation clock. */
    private fun awaitText(text: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Complete canonical fixture: three distinct thumbs-up reactors and one fire reactor. */
    private fun reactions(
        target: String,
        third: String,
    ): List<TimelineUserReactionFfi> =
        listOf("01", "02", third, "04").mapIndexed { index, id ->
            TimelineUserReactionFfi("${index + 1}".repeat(64), target, id + "00".repeat(31), if (index == 3) "🔥" else "👍", 1uL)
        }
}
