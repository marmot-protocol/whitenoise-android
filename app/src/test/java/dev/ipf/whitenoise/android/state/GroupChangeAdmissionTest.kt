package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.groupChangeConfirmationText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChangeAdmissionTest {
    private val asked = mutableListOf<GroupChangeCaution>()
    private val acknowledged = mutableListOf<GroupChangeCaution>()
    private val sessions = mutableListOf<List<GroupChangeCaution>>()

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

    /** A caution hidden behind the reported one is asked in the same session, so Wait leaves nothing acknowledged. */
    @Test
    fun aHiddenCautionIsAskedBeforeAnythingIsAcknowledged() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))

            val failure = runCatching { admitHiding(listOf(GroupChangeAnswer.Wait), attempts) }.exceptionOrNull()

            assertTrue("$failure", failure is GroupChangeDeclinedException)
            assertEquals(listOf(listOf(GroupChangeCaution.CatchingUp, GroupChangeCaution.Undelivered)), sessions)
            assertTrue("consent was spent before every question was answered", acknowledged.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** Continuing past both cautions acknowledges both, in MDK's checking order, before a single retry. */
    @Test
    fun continuingPastAHiddenCautionAcknowledgesBothBeforeOneRetry() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))

            assertEquals("done", admitHiding(listOf(GroupChangeAnswer.Continue), attempts))
            val both = listOf(GroupChangeCaution.CatchingUp, GroupChangeCaution.Undelivered)
            assertEquals(listOf(both), sessions)
            assertEquals(both, acknowledged)
            assertEquals(2, attempts.count)
        }

    /** A caution that turns up while the user answers is asked too, before anything is acknowledged. */
    @Test
    fun aCautionThatAppearsWhileTheUserAnswersIsAskedFirst() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))
            var reads = 0

            val failure =
                runCatching {
                    admitHiding(listOf(GroupChangeAnswer.Continue, GroupChangeAnswer.Wait), attempts) {
                        reads += 1
                        if (reads > 1) listOf(GroupChangeCaution.Undelivered) else emptyList()
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is GroupChangeDeclinedException)
            val oneAtATime = listOf(listOf(GroupChangeCaution.CatchingUp), listOf(GroupChangeCaution.Undelivered))
            assertEquals(oneAtATime, sessions)
            assertTrue("the first answer was acknowledged before the second question", acknowledged.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** A change that stopped applying while the user answered acknowledges nothing, even after Continue. */
    @Test
    fun aChangeThatStoppedApplyingAcknowledgesNothing() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))
            var wanted = true

            val failure =
                runCatching {
                    admitGroupChange(
                        ask = {
                            wanted = false
                            GroupChangeAnswer.Continue
                        },
                        acknowledge = { acknowledged += it },
                        stillWanted = { wanted },
                        change = { attempts.attempt() },
                    )
                }.exceptionOrNull()

            assertTrue("$failure", failure is GroupChangeDeclinedException)
            assertTrue(acknowledged.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** A question that cannot be asked reports the refusal itself, as an ordinary failure rather than a quiet wait. */
    @Test
    fun anUnaskableQuestionReportsTheRefusal() =
        runTest {
            val refusal = MarmotKitException.GroupCatchingUp(GROUP)
            val attempts = scripted(refusal)

            val failure =
                runCatching {
                    admitGroupChange(
                        ask = { GroupChangeAnswer.Unavailable },
                        acknowledge = { acknowledged += it },
                        change = { attempts.attempt() },
                    )
                }.exceptionOrNull()

            assertSame(refusal, failure)
            assertTrue(acknowledged.isEmpty())
            assertEquals(1, attempts.count)
        }

    /** Once the user continues, the acknowledgement and its retry finish together even if the caller is cancelled. */
    @Test
    fun acknowledgementAndRetryFinishTogetherDespiteCancellation() =
        runTest {
            val attempts = scripted(MarmotKitException.GroupCatchingUp(GROUP))
            val acknowledging = CompletableDeferred<Unit>()
            val run =
                async(start = CoroutineStart.UNDISPATCHED) {
                    admitGroupChange(
                        ask = { GroupChangeAnswer.Continue },
                        acknowledge = {
                            acknowledging.await()
                            acknowledged += it
                        },
                        change = { attempts.attempt() },
                    )
                }

            run.cancel()
            acknowledging.complete(Unit)
            runCatching { run.await() }

            assertEquals(listOf(GroupChangeCaution.CatchingUp), acknowledged)
            assertEquals("the acknowledgement was left for some later change", 2, attempts.count)
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

    /** One change's questions follow each other, so another change's question never comes between them. */
    @Test
    fun anotherChangeNeverAsksBetweenOneChangesQuestions() =
        runTest {
            val confirmations = GroupChangeConfirmations()
            val both = listOf(GroupChangeCaution.CatchingUp, GroupChangeCaution.Undelivered)
            val first = async(start = CoroutineStart.UNDISPATCHED) { confirmations.askAll(both, false, TARGET) }
            val other = TARGET.copy(title = "Book club")
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    confirmations.ask(GroupChangeCaution.CatchingUp, invite = false, other)
                }

            checkNotNull(confirmations.pending).answer(proceed = true)
            runCurrent()
            val next = checkNotNull(confirmations.pending)
            assertEquals(TARGET to GroupChangeCaution.Undelivered, next.target to next.caution)
            next.answer(proceed = false)
            runCurrent()
            assertEquals(other, confirmations.pending?.target)
            checkNotNull(confirmations.pending).answer(proceed = true)

            assertEquals(false, first.await())
            assertEquals(true, second.await())
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
            ask = { cautions ->
                asked += cautions
                if (proceed) GroupChangeAnswer.Continue else GroupChangeAnswer.Wait
            },
            acknowledge = { acknowledged += it },
            change = { attempts.attempt() },
        )

    /**
     * Runs the admission loop where [hidden] reports the cautions behind each one, by default an undelivered refusal
     * behind catching up, answering each question session with the next of [answers].
     */
    private suspend fun admitHiding(
        answers: List<GroupChangeAnswer>,
        attempts: Scripted,
        hidden: (GroupChangeCaution) -> List<GroupChangeCaution> = { caution ->
            if (caution == GroupChangeCaution.CatchingUp) listOf(GroupChangeCaution.Undelivered) else emptyList()
        },
    ): String =
        admitGroupChange(
            ask = { cautions ->
                sessions += listOf(cautions)
                answers[sessions.size - 1]
            },
            acknowledge = { acknowledged += it },
            hiddenBehind = { hidden(it) },
            change = { attempts.attempt() },
        )

    private companion object {
        const val GROUP = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
        val TARGET = GroupChangeTarget("alice", GROUP, runtimeGeneration = 0, title = "Weekend hikers")
    }
}
