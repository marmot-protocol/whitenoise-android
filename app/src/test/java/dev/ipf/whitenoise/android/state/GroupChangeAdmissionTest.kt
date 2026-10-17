package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.groupChangeConfirmationText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MDK refuses group changes, advisorily, while the group may have moved past this device or its last change has not
 * reached every relay (#3289). The user decides once per caution; continuing acknowledges and retries.
 */
class GroupChangeAdmissionTest {
    private val asked = mutableListOf<GroupChangeCaution>()
    private val acknowledged = mutableListOf<GroupChangeCaution>()

    /** A catching-up refusal the user overrides is acknowledged once and the change is retried. */
    @Test
    fun catchingUpRefusalIsAcknowledgedAndRetriedWhenTheUserContinues() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))

            assertEquals("done", admit(proceed = true, attempts))
            assertEquals(listOf(GroupChangeCaution.CatchingUp), asked)
            assertEquals(listOf(GroupChangeCaution.CatchingUp), acknowledged)
            assertEquals(2, attempts.count)
        }

    /** A change can need both acknowledgements; each is asked once and the third attempt goes through. */
    @Test
    fun aChangeCanNeedBothAcknowledgements() =
        runTest {
            val attempts =
                scripted(MarmotKitException.GroupCatchingUp(GROUP), MarmotKitException.GroupCommitUndelivered(GROUP))

            assertEquals("done", admit(proceed = true, attempts))
            val both = listOf(GroupChangeCaution.CatchingUp, GroupChangeCaution.Undelivered)
            assertEquals(both, asked)
            assertEquals(both, acknowledged)
            assertEquals(3, attempts.count)
        }

    /** Waiting acknowledges nothing and ends the change as a quiet cancellation. */
    @Test
    fun waitingAcknowledgesNothingAndEndsQuietly() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCommitUndelivered(GROUP))

            val failure = runCatching { admit(proceed = false, attempts) }.exceptionOrNull()

            assertTrue("$failure", failure is GroupChangeDeclinedException)
            assertTrue(
                "a flow with a committed earlier step needs the refusal the user waited on",
                (failure as GroupChangeDeclinedException).refusal is MarmotKitException.GroupCommitUndelivered,
            )
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertEquals(listOf(GroupChangeCaution.Undelivered), asked)
            assertTrue(acknowledged.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** The same refusal after an acknowledgement is reported as the failure it is, not asked about again. */
    @Test
    fun theSameRefusalTwiceIsReportedNotAskedAgain() =
        runTest {
            val second = MarmotKitException.GroupCatchingUp(GROUP)
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP), second)

            val failure = runCatching { admit(proceed = true, attempts) }.exceptionOrNull()

            assertSame(second, failure)
            assertEquals(listOf(GroupChangeCaution.CatchingUp), asked)
            assertEquals(2, attempts.count)
        }

    /** Any other failure passes through untouched and nobody is asked. */
    @Test
    fun otherFailuresPassThroughWithoutAQuestion() =
        runTest {
            val other = MarmotKitException.NotGroupAdmin(GROUP)
            val attempts = scripted(other)

            assertSame(other, runCatching { admit(proceed = true, attempts) }.exceptionOrNull())
            assertTrue(asked.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** Only the two advisory refusals carry a caution. */
    @Test
    fun onlyTheAdvisoryRefusalsCarryACaution() {
        assertEquals(GroupChangeCaution.CatchingUp, MarmotKitException.GroupCatchingUp(GROUP).groupChangeCaution())
        assertEquals(
            GroupChangeCaution.Undelivered,
            MarmotKitException.GroupCommitUndelivered(GROUP).groupChangeCaution(),
        )
        assertNull(MarmotKitException.NotGroupAdmin(GROUP).groupChangeCaution())
    }

    /** The app-level question appears while the change waits, takes one answer, and is withdrawn on cancellation. */
    @Test
    fun questionIsShownUntilAnsweredAndWithdrawnOnCancellation() =
        runTest {
            val confirmations = GroupChangeConfirmations()
            val answered =
                async(start = CoroutineStart.UNDISPATCHED) {
                    confirmations.ask(GroupChangeCaution.CatchingUp, invite = true, TARGET)
                }
            val question = checkNotNull(confirmations.pending)
            assertTrue(question.invite)
            assertEquals("the question names the change it is about", TARGET, question.target)
            question.answer(proceed = true)
            question.answer(proceed = false)
            assertTrue(answered.await())
            assertNull(confirmations.pending)

            val abandoned =
                async(start = CoroutineStart.UNDISPATCHED) {
                    confirmations.ask(GroupChangeCaution.Undelivered, invite = false, TARGET)
                }
            assertEquals(GroupChangeCaution.Undelivered, confirmations.pending?.caution)
            abandoned.cancel()
            yield()
            assertNull("a cancelled change left its question on screen", confirmations.pending)
        }

    /** Invites get MDK's stronger catching-up wording; every other change shares the general text. */
    @Test
    fun invitesGetTheStrongerCatchingUpWording() {
        val invite = groupChangeConfirmationText(GroupChangeCaution.CatchingUp, invite = true)
        val other = groupChangeConfirmationText(GroupChangeCaution.CatchingUp, invite = false)
        val undelivered = groupChangeConfirmationText(GroupChangeCaution.Undelivered, invite = true)

        assertEquals(R.string.group_change_catching_up_invite_message, invite.second)
        assertEquals(R.string.group_change_invite_anyway, invite.third)
        assertEquals(R.string.group_change_catching_up_message, other.second)
        assertEquals(R.string.group_change_undelivered_message, undelivered.second)
        assertEquals(R.string.group_change_continue_anyway, undelivered.third)
    }

    /** A change that fails with each of [refusals] in turn, then returns "done", counting its attempts. */
    private class Scripted(
        private val refusals: List<MarmotKitException>,
    ) {
        var count = 0
            private set

        /** One attempt: the next scripted refusal, or success once they are used up. */
        fun attempt(): String {
            val refusal = refusals.getOrNull(count)
            count += 1
            if (refusal != null) throw refusal
            return "done"
        }
    }

    /** Scripts a change that is refused with each of [refusals] before it succeeds. */
    private fun scripted(vararg refusals: MarmotKitException) = Scripted(refusals.toList())

    /** Runs the admission loop with a user who always answers [proceed]. */
    private suspend fun admit(
        proceed: Boolean,
        attempts: Scripted,
    ): String =
        admitGroupChange(
            ask = { caution ->
                asked += caution
                proceed
            },
            acknowledge = { acknowledged += it },
            change = { attempts.attempt() },
        )

    private companion object {
        const val GROUP = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
        val TARGET = GroupChangeTarget("alice", GROUP, runtimeGeneration = 0, title = "Weekend hikers")
    }
}
