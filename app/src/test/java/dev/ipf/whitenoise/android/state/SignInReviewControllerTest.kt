package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-in answers stay distinct, a failed check is not an empty list, and dismissing a warning does nothing else. */
class SignInReviewControllerTest {
    /** A scripted runtime that records every call. */
    private class Native : SignInReviewNative {
        val calls = mutableListOf<String>()
        var listed: suspend () -> List<UnrecognizedSignInFfi> = { listOf(signIn("a")) }
        var checked: suspend () -> List<UnrecognizedSignInFfi> = { listOf(signIn("a"), signIn("b")) }
        var warning: suspend () -> List<CompromiseWarningItemFfi> = { emptyList() }
        var fresh: suspend () -> Boolean = { false }
        var act: suspend () -> Unit = {}

        /** Answers with the scripted local list. */
        override suspend fun unrecognizedSignIns(accountRef: String) = listed()

        /** Records the check and answers with the scripted result. */
        override suspend fun checkSignIns(accountRef: String): List<UnrecognizedSignInFfi> {
            calls += "check:$accountRef"
            return checked()
        }

        /** Records the answer with its slot and response. */
        override suspend fun answerSignIn(
            accountRef: String,
            slotIdHex: String,
            response: SignInResponseFfi,
        ) {
            calls += "answer:$accountRef:$slotIdHex:$response"
            act()
        }

        /** Answers with the scripted warning. */
        override suspend fun compromiseWarning(accountRef: String) = warning()

        /** Records the dismissal. */
        override suspend fun dismissCompromiseWarning(accountRef: String) {
            calls += "dismiss-warning:$accountRef"
            act()
        }

        /** Answers with the scripted flag. */
        override suspend fun startedFresh(accountRef: String) = fresh()

        /** Records the fresh start. */
        override suspend fun startFresh(accountRef: String) {
            calls += "start-fresh:$accountRef"
            act()
        }
    }

    /** A local read begun before a relay check finished is older than the check, so it does not replace its list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRefreshBegunBeforeACheckDoesNotReplaceTheCheckedList() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native = Native()
            native.listed = {
                gate.await()
                listOf(signIn("a"))
            }
            val controller = SignInReviewController(ACCOUNT, native)
            val refresh = launch { controller.refresh() }
            runCurrent()

            controller.check()
            gate.complete(Unit)
            refresh.join()

            assertEquals(listOf("a", "b"), controller.list.signIns.map { it.slotIdHex })
        }

    /** A refresh publishes the list, the warning and the started-fresh flag. */
    @Test
    fun aRefreshPublishesEverything() =
        runTest {
            val native = Native()
            native.warning = { listOf(WARNING) }
            native.fresh = { true }
            val controller = SignInReviewController(ACCOUNT, native)

            controller.refresh()

            assertEquals(listOf("a"), controller.list.signIns.map { it.slotIdHex })
            assertTrue(controller.list.loaded)
            assertEquals(listOf(WARNING), controller.warning)
            assertTrue(controller.startedFresh)
        }

    /** A relay check that fails keeps the listed sign-ins and marks the failure; it is not "no sign-ins". */
    @Test
    fun aFailedCheckKeepsTheListAndMarksTheFailure() =
        runTest {
            val native = Native()
            val controller = SignInReviewController(ACCOUNT, native)
            controller.refresh()
            native.checked = { throw MarmotKitException.TransportClosed() }

            controller.check()

            assertEquals(listOf("a"), controller.list.signIns.map { it.slotIdHex })
            assertTrue(controller.list.checkFailed)
            assertFalse(controller.list.checking)
            assertNull(controller.failure)
        }

    /** A check that succeeds replaces the list and clears an earlier failure. */
    @Test
    fun aSuccessfulCheckReplacesTheListAndClearsTheFailure() =
        runTest {
            val native = Native()
            native.checked = { throw MarmotKitException.TransportClosed() }
            val controller = SignInReviewController(ACCOUNT, native)
            controller.check()
            native.checked = { listOf(signIn("a"), signIn("b")) }

            controller.check()

            assertEquals(listOf("a", "b"), controller.list.signIns.map { it.slotIdHex })
            assertFalse(controller.list.checkFailed)
        }

    /** A check that finds nothing is an empty, loaded list without a failure. */
    @Test
    fun aCheckThatFindsNothingIsNotAFailure() =
        runTest {
            val native = Native()
            native.checked = { emptyList() }
            native.listed = { emptyList() }
            val controller = SignInReviewController(ACCOUNT, native)

            controller.check()

            assertEquals(SignInListState(loaded = true), controller.list)
        }

    /** Not now, Reject and Dismiss each reach MDK as themselves. */
    @Test
    fun eachAnswerReachesMdkAsItself() =
        runTest {
            val native = Native()
            val controller = SignInReviewController(ACCOUNT, native)

            SignInResponseFfi.entries.forEach { controller.answer("slot", it) }

            assertEquals(SignInResponseFfi.entries.map { "answer:$ACCOUNT:slot:$it" }, native.calls)
        }

    /** Marking the warning seen calls only that; it answers no sign-in and starts nothing fresh. */
    @Test
    fun dismissingTheWarningAnswersNothing() =
        runTest {
            val native = Native()
            val controller = SignInReviewController(ACCOUNT, native)

            controller.dismissWarning()

            assertEquals(listOf("dismiss-warning:$ACCOUNT"), native.calls)
        }

    /** Starting fresh calls only that; it rejects nothing. */
    @Test
    fun startingFreshRejectsNothing() =
        runTest {
            val native = Native()
            val controller = SignInReviewController(ACCOUNT, native)

            controller.startFresh()

            assertEquals(listOf("start-fresh:$ACCOUNT"), native.calls)
        }

    /** A failed answer is reported once with the action it belonged to. */
    @Test
    fun aFailedAnswerIsReportedOnce() =
        runTest {
            val native = Native()
            val error = MarmotKitException.TransportClosed()
            native.act = { throw error }
            val controller = SignInReviewController(ACCOUNT, native)

            controller.answer("slot", SignInResponseFfi.REJECT)

            assertEquals(SignInReviewFailure(SignInReviewAction.Answer, error), controller.failure)
            controller.failureShown()
            assertNull(controller.failure)
        }

    /** After retirement nothing is sent and nothing is published. */
    @Test
    fun aRetiredControllerSendsAndPublishesNothing() =
        runTest {
            val native = Native()
            val controller = SignInReviewController(ACCOUNT, native)
            controller.retire()

            controller.refresh()
            controller.check()
            controller.answer("slot", SignInResponseFfi.REJECT)

            assertTrue(native.calls.isEmpty())
            assertEquals(SignInListState(), controller.list)
        }

    /** Only this account's sign-in, warning and roster events trigger a re-read. */
    @Test
    fun onlyThisAccountsEventsReread() =
        runTest {
            val native = Native()
            var reads = 0
            native.listed = {
                reads++
                emptyList()
            }
            val controller = SignInReviewController(ACCOUNT, native)

            controller.onEvent(MarmotEventFfi.UnrecognizedSignInsChanged("id", ACCOUNT))
            controller.onEvent(MarmotEventFfi.CompromiseWarningChanged("id", ACCOUNT))
            controller.onEvent(MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT))
            controller.onEvent(MarmotEventFfi.UnrecognizedSignInsChanged("id", "other"))
            controller.onEvent(MarmotEventFfi.DeviceLinkChanged("id", ACCOUNT))

            assertEquals(3, reads)
        }

    private companion object {
        const val ACCOUNT = "account"
        val WARNING = CompromiseWarningItemFfi(CompromiseWarningReasonFfi.REJECTED_SIGN_IN, "slot", null, 1u)

        /** A sign-in waiting for an answer in [slot]. */
        fun signIn(slot: String) =
            UnrecognizedSignInFfi(
                slotIdHex = slot,
                client = "White Noise",
                appearedAt = 1u,
                lastSeenAt = 2u,
                beforeThisDevice = false,
                signedIn = true,
                state = UnrecognizedSignInStateFfi.PROMPT,
                linkedBySlotIdHex = null,
                previouslyLinkedHere = false,
            )
    }
}
