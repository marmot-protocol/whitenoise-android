package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Most group changes run inside the group's commit lock, which sends to that group also take (#3289). Asking the user
 * about a refused change must not hold that lock, or a reply from a notification would wait for an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChangeCommitLockTest {
    /** A send to the group goes through while the question is open, and the lock is free once the user waits. */
    @Test
    fun anOpenQuestionDoesNotHoldUpSendsToTheGroup() =
        runTest {
            val state = appState()
            val change =
                async {
                    runCatching {
                        state.withGroupCommitLock(ACCOUNT, GROUP) {
                            state.runGroupChange(ACCOUNT, GROUP) { throw MarmotKitException.GroupCatchingUp(GROUP) }
                        }
                    }.exceptionOrNull()
                }
            runCurrent()
            val question = checkNotNull(state.groupChangeConfirmations.pending) { "the user was never asked" }

            val send = async { state.withGroupCommitLock(ACCOUNT, GROUP) { "sent" } }
            runCurrent()
            assertTrue("a send waited for the user's answer", send.isCompleted)

            question.answer(proceed = false)
            assertTrue(change.await() is GroupChangeDeclinedException)
            assertEquals("free", state.withGroupCommitLock(ACCOUNT, GROUP) { "free" })
        }

    /** One signed-in account, with no runtime attached. */
    private fun appState() =
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
    }
}
