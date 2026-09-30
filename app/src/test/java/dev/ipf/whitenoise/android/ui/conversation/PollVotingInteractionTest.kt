package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real option taps on a received poll, including a delayed or rejected native vote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PollVotingInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun receivedPollInAmoledSubmitsNativeIdAndShowsPendingThenConfirmedSelection() {
        val projection = mutableStateOf(poll())
        val submissions = mutableListOf<List<String>>()
        var complete: ((SendAcceptDispositionFfi?) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                PollVotingCard(projection.value, "account" to "received-poll", canVote = true, submitVote = { ids, done ->
                    submissions += ids
                    complete = done
                })
            }
        }

        composeRule
            .onNodeWithText("Salad", substring = true)
            .performClick()
            .assertIsSelected()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Sending").assertExists()
        assertEquals(listOf(listOf("native-b")), submissions)
        composeRule.runOnIdle {
            projection.value =
                poll().copy(
                    localSelection = listOf("native-b"),
                    participants = 1uL,
                    options = listOf(PollOptionResultFfi("native-a", "Soup", 0uL), PollOptionResultFfi("native-b", "Salad", 1uL)),
                )
        }
        composeRule.onNodeWithText("Sending").assertExists()
        composeRule.onNodeWithText("Salad", substring = true).assertIsNotEnabled()
        composeRule.runOnIdle { checkNotNull(complete)(SendAcceptDispositionFfi.PUBLISHED) }
        composeRule.onNodeWithText("Sending").assertDoesNotExist()
        composeRule.onNodeWithText("Salad", substring = true).assertIsSelected().assertIsEnabled()
        composeRule.onNodeWithText("Soup", substring = true).performClick()
        assertEquals(listOf(listOf("native-b"), listOf("native-a")), submissions)
    }

    @Test
    fun failedVoteRollsBackAndAnOptionTapRetries() {
        val submissions = mutableListOf<List<String>>()
        var complete: ((SendAcceptDispositionFfi?) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                PollVotingCard(poll(), "account" to "received-poll", canVote = true, submitVote = { ids, done ->
                    submissions += ids
                    complete = done
                })
            }
        }
        composeRule.onNodeWithText("Salad", substring = true).performClick()
        composeRule.runOnIdle { checkNotNull(complete)(null) }
        composeRule.onNodeWithText("Couldn't submit vote").assertExists()
        composeRule.onNodeWithText("Salad").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Couldn't submit vote").assertDoesNotExist()
        assertEquals(listOf(listOf("native-b"), listOf("native-b")), submissions)
    }

    @Test
    fun queuedVoteStaysPendingUntilNativeProjectionArrives() {
        val projection = mutableStateOf(poll())
        var calls = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                PollVotingCard(projection.value, "account" to "received-poll", canVote = true, submitVote = { _, done ->
                    calls++
                    done(SendAcceptDispositionFfi.ACCEPTED_PENDING)
                })
            }
        }
        composeRule
            .onNodeWithText("Salad", substring = true)
            .performClick()
            .assertIsSelected()
            .assertIsEnabled()
        composeRule.onNodeWithText("Sending").assertExists()
        assertEquals(1, calls)
        composeRule.runOnIdle { projection.value = poll().copy(localSelection = listOf("native-b")) }
        composeRule.onNodeWithText("Sending").assertDoesNotExist()
    }

    @Test
    fun unknownCompletionShowsUnconfirmedWithoutAutomaticallyResending() {
        var calls = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                PollVotingCard(poll(), "account" to "received-poll", canVote = true, submitVote = { _, done ->
                    calls++
                    done(SendAcceptDispositionFfi.COMPLETION_UNKNOWN)
                })
            }
        }
        composeRule
            .onNodeWithText("Salad", substring = true)
            .performClick()
            .assertIsSelected()
            .assertIsEnabled()
        composeRule.onNodeWithText("Delivery not confirmed").assertExists()
        composeRule.onNodeWithText("Sending").assertDoesNotExist()
        assertEquals(1, calls)
    }

    @Test
    fun closedAndUnsendablePollsNeverSubmit() {
        val available = mutableStateOf(false)
        val projection = mutableStateOf(poll())
        val submissions = mutableListOf<List<String>>()
        composeRule.setContent {
            WhiteNoiseTheme {
                PollVotingCard(projection.value, "account" to "received-poll", canVote = available.value, submitVote = { ids, _ ->
                    submissions += ids
                })
            }
        }
        composeRule.onNodeWithText("Salad").assertIsNotEnabled().performClick()
        assertEquals(emptyList<List<String>>(), submissions)
        composeRule.runOnIdle {
            available.value = true
            projection.value = poll().copy(open = false)
        }
        composeRule.onNodeWithText("Salad").performClick()
        assertEquals(emptyList<List<String>>(), submissions)
    }

    private fun poll() =
        PollProjectionFfi(
            question = "Lunch?",
            options = listOf(PollOptionResultFfi("native-a", "Soup", 0uL), PollOptionResultFfi("native-b", "Salad", 0uL)),
            pollType = PollTypeFfi.SINGLE_CHOICE,
            participants = 0uL,
            localSelection = emptyList(),
            creator = "remote-creator",
            endsAt = null,
            open = true,
        )
}
