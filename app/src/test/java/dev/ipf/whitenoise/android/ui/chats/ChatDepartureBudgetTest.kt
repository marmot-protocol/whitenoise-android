package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.GroupChangeDeclinedException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A departure step has a UI budget for native work, but a group-change question can open in the middle of it (#3289).
 * Time the user spends reading that question must not count, or the step is marked unfinished while they decide.
 */
class ChatDepartureBudgetTest {
    /** Work that outlasts the budget only because a question was open still finishes and returns its value. */
    @Test
    fun timeSpentOnAnOpenQuestionDoesNotCount() =
        runTest {
            val result =
                withNativeTimeoutOrNull(BUDGET_MILLIS, paused = { testScheduler.currentTime < QUESTION_MILLIS }) {
                    delay(QUESTION_MILLIS + BUDGET_MILLIS / 2)
                    "left"
                }

            assertEquals("left", result)
        }

    /** Without a question the budget still ends work that never settles, on time. */
    @Test
    fun theBudgetStillEndsWorkThatNeverSettles() =
        runTest {
            val result =
                withNativeTimeoutOrNull(BUDGET_MILLIS, paused = { false }) {
                    delay(QUESTION_MILLIS)
                    "left"
                }

            assertNull(result)
            assertEquals(BUDGET_MILLIS, testScheduler.currentTime)
        }

    /** Waiting on the question reaches the step as the same declined change, never as an unfinished timeout. */
    @Test
    fun aDeclinedChangeLeavesTheBudgetUnchanged() =
        runTest {
            val refusal = MarmotKitException.GroupCatchingUp(GROUP)

            val failure =
                runCatching {
                    withNativeTimeoutOrNull(BUDGET_MILLIS, paused = { false }) {
                        throw GroupChangeDeclinedException(refusal)
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is GroupChangeDeclinedException)
            assertSame(refusal, (failure as GroupChangeDeclinedException).refusal)
        }

    private companion object {
        const val GROUP = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
        const val BUDGET_MILLIS = 1_000L
        const val QUESTION_MILLIS = 5_000L
    }
}
