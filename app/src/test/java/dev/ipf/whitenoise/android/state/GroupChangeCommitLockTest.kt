package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableState
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Group changes run inside the group's commit lock, which sends to that group also take (#3289). Asking the user about
 * a refused change must not hold that lock, or a reply from a notification would wait for an answer. The one-attempt
 * acknowledgement and its retry must share one hold, and an answer only counts for the account it was asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupChangeCommitLockTest {
    /** A send to the group goes through while the question is open, and the lock is free once the user waits. */
    @Test
    fun anOpenQuestionDoesNotHoldUpSendsToTheGroup() =
        runBlocking {
            val state = appState(runtime { name -> check(name == "groupCommitDelivery") { "Unexpected: $name" } })
            val change =
                background.async {
                    runCatching {
                        state.withGroupCommitLock(ACCOUNT, GROUP) {
                            state.runGroupChange(ACCOUNT, GROUP) { throw MarmotKitException.GroupCatchingUp(GROUP) }
                        }
                    }.exceptionOrNull()
                }
            awaitUntil { state.groupChangeConfirmations.pending != null }
            val question = checkNotNull(state.groupChangeConfirmations.pending)

            val send = background.async { state.withGroupCommitLock(ACCOUNT, GROUP) { "sent" } }
            assertEquals("a send waited for the user's answer", "sent", withTimeout(SETTLE_MILLIS * 5) { send.await() })

            question.answer(proceed = false)
            assertTrue(change.await() is GroupChangeDeclinedException)
            assertEquals("free", state.withGroupCommitLock(ACCOUNT, GROUP) { "free" })
        }

    /** Acknowledgement and retry share one hold of the lock, so a competing change cannot spend the acknowledgement. */
    @Test
    fun acknowledgementAndRetryShareOneHoldOfTheLock() =
        runBlocking {
            val order = Collections.synchronizedList(mutableListOf<String>())
            val acknowledging = CountDownLatch(1)
            val finishAcknowledgement = CountDownLatch(1)
            val state =
                appState(
                    runtime { name ->
                        check(name == "acknowledgeGroupCatchingUp") { "Unexpected native call: $name" }
                        acknowledging.countDown()
                        finishAcknowledgement.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        order += "acknowledge"
                        true
                    },
                )
            var attempts = 0
            // Called without holding the lock, as the profile paths once were: runGroupChange takes it itself.
            val change =
                background.async {
                    state.runGroupChange(ACCOUNT, GROUP) {
                        attempts += 1
                        if (attempts == 1) throw MarmotKitException.GroupCatchingUp(GROUP)
                        order += "retry"
                    }
                }
            awaitUntil { state.groupChangeConfirmations.pending != null }
            checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = true)
            assertTrue(acknowledging.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val competitor = background.async { state.withGroupCommitLock(ACCOUNT, GROUP) { order += "competitor" } }
            delay(SETTLE_MILLIS)
            assertFalse("another change took the lock between acknowledgement and retry", competitor.isCompleted)

            finishAcknowledgement.countDown()
            change.await()
            competitor.await()
            assertEquals(listOf("acknowledge", "retry", "competitor"), order)
        }

    /** An answer given after switching accounts never retries the previous account's change. */
    @Test
    fun anAnswerAfterAnAccountSwitchNeverRetriesTheOldChange() =
        runBlocking {
            val state = appState(runtime { name -> error("nothing may reach the runtime: $name") })
            var attempts = 0
            val change =
                background.async {
                    runCatching {
                        state.runGroupChange(ACCOUNT, GROUP) {
                            attempts += 1
                            throw MarmotKitException.GroupCatchingUp(GROUP)
                        }
                    }.exceptionOrNull()
                }
            awaitUntil { state.groupChangeConfirmations.pending != null }
            val question = checkNotNull(state.groupChangeConfirmations.pending)
            assertEquals(ACCOUNT, question.target.accountRef)

            switchActiveAccount(state, "bob")
            assertFalse(state.ownsGroupChange(question.target))
            question.answer(proceed = true)

            assertTrue(change.await() is GroupChangeDeclinedException)
            assertEquals(1, attempts)
        }

    /** Continue given after the change stopped applying, such as a superseded avatar, acknowledges nothing. */
    @Test
    fun anAnswerAfterTheChangeStoppedApplyingAcknowledgesNothing() =
        runBlocking {
            val state = appState(runtime { name -> check(name == "groupCommitDelivery") { "Unexpected: $name" } })
            val wanted = AtomicBoolean(true)
            var attempts = 0
            val change =
                background.async {
                    runCatching {
                        state.runGroupChange(ACCOUNT, GROUP, stillWanted = { wanted.get() }) {
                            attempts += 1
                            throw MarmotKitException.GroupCatchingUp(GROUP)
                        }
                    }.exceptionOrNull()
                }
            awaitUntil { state.groupChangeConfirmations.pending != null }

            wanted.set(false)
            checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = true)

            assertTrue(change.await() is GroupChangeDeclinedException)
            assertEquals(1, attempts)
        }

    /** A refusal for an account that is not the active one cannot be asked about, so it is an ordinary failure. */
    @Test
    fun aRefusalForAnInactiveAccountIsAnOrdinaryFailure() =
        runBlocking {
            val state = appState(runtime { name -> error("nothing may reach the runtime: $name") })
            val refusal = MarmotKitException.GroupCatchingUp(GROUP)

            val failure =
                runCatching {
                    withTimeout(TIMEOUT_SECONDS * MILLIS_PER_SECOND) {
                        state.runGroupChange("bob", GROUP) { throw refusal }
                    }
                }.exceptionOrNull()

            assertSame(refusal, failure)
            assertNull(state.groupChangeConfirmations.pending)
        }

    /** Polls [done] for up to the test timeout. */
    private suspend fun awaitUntil(done: () -> Boolean) {
        withTimeout(TIMEOUT_SECONDS * MILLIS_PER_SECOND) {
            while (!done()) delay(POLL_MILLIS)
        }
    }

    /** Replaces the active account the way a switch does, without the native work around it. */
    @Suppress("UNCHECKED_CAST")
    private fun switchActiveAccount(
        state: WhiteNoiseAppState,
        account: String,
    ) {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("activeAccountRef\$delegate")
        field.isAccessible = true
        (field.get(state) as MutableState<String?>).value = account
    }

    /** A runtime whose every call is answered by [answer] with the method's name. */
    private fun runtime(answer: (String) -> Any?): AppMarmotRuntime =
        AppMarmotRuntime(
            "test",
            Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
                _,
                method,
                _,
                ->
                answer(method.name.substringBefore('-'))
            } as MarmotInterface,
        )

    /** One signed-in account, with [runtime] attached when given. */
    private fun appState(runtime: AppMarmotRuntime? = null) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { it },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT,
                        accountIdHex = ACCOUNT,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT,
            initialMarmotRuntime = runtime,
        )

    /** Draft storage that keeps nothing. */
    private object NoDrafts : DraftPersistence {
        /** No drafts are ever restored. */
        override fun read(): Map<String, String> = emptyMap()

        /** Writes are discarded. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "alice"
        const val GROUP = "group"
        const val TIMEOUT_SECONDS = 5L
        const val MILLIS_PER_SECOND = 1_000L
        const val POLL_MILLIS = 10L
        const val SETTLE_MILLIS = 200L
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
