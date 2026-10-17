package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Adding one profile to several groups asks about each group MDK holds back (#3289). Choosing Wait for one group must
 * skip only that group: the groups after it are still invited and the result says some groups wait, while a genuine
 * cancellation still stops the whole fan-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileGroupInviteWaitTest {
    private val invites = Collections.synchronizedList(mutableListOf<String>())

    /** A is invited, the user waits on B, and C is still invited, with a toast that says the rest wait. */
    @Test
    fun waitingOnOneGroupStillInvitesTheRest() =
        runBlocking {
            val state = appState()
            val invite = start { state.inviteProfileToGroups(INVITEE, listOf(GROUP_A, GROUP_B, GROUP_C)) }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }
                checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = false)
                awaitWithMainLooper { invite.isCompleted }
            } finally {
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertFalse("waiting escaped the fan-out as a cancellation", invite.isCancelled)
            assertFalse("a waited group cannot count as complete success", invite.await())
            assertEquals(listOf(GROUP_A, GROUP_B, GROUP_C), invites)
            assertEquals(AppText.Resource(R.string.toast_invites_sent_to_groups_waited), state.transientNotice?.title)
            assertNull("waiting is not an error", state.toast)
        }

    /** Cancelling while B's question is open stops the fan-out: C is never invited and nothing is reported. */
    @Test
    fun cancellationStillStopsTheFanOut() =
        runBlocking {
            val state = appState()
            val invite = start { state.inviteProfileToGroups(INVITEE, listOf(GROUP_A, GROUP_B, GROUP_C)) }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }
                invite.cancel()
                awaitWithMainLooper { invite.isCompleted }
            } finally {
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertTrue(invite.isCancelled)
            assertEquals(listOf(GROUP_A, GROUP_B), invites)
            assertNull(state.groupChangeConfirmations.pending)
            assertNull(state.toast)
            assertNull(state.transientNotice)
        }

    /** Starts [block] outside runBlocking's children, so a fan-out that ignores cancellation fails, not hangs. */
    private fun <T> start(block: suspend () -> T): Deferred<T> {
        val scope = CoroutineScope(Job())
        return scope.async(start = CoroutineStart.UNDISPATCHED) { block() }
    }

    /** The test thread is Robolectric's main thread, so its looper runs between polls for main-thread steps. */
    private suspend fun awaitWithMainLooper(done: () -> Boolean) {
        withTimeout(TIMEOUT_MILLIS) {
            while (!done()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(POLL_MILLIS)
            }
        }
    }

    /** One signed-in admin whose runtime invites to every group except B, which it holds back as catching up. */
    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            initialMarmotRuntime = AppMarmotRuntime("test", runtime()),
        )

    /** Records each invitation by group and refuses B's as catching up; anything else is unexpected. */
    private fun runtime(): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            when (val name = method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "inviteMembersDetailed" -> {
                    val group = arguments?.get(1) as String
                    invites += group
                    if (group == GROUP_B) {
                        failSuspendedNativeCall(arguments, MarmotKitException.GroupCatchingUp(group))
                    } else {
                        testGroupMutationResult(listOf(ConversationTimelineTestIds.ACCOUNT_ID, INVITEE), MEMBERS_ADMINS)
                    }
                }
                else -> error("Unexpected native call: $name")
            }
        } as MarmotInterface

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
        val INVITEE = "e3".repeat(32)
        val GROUP_A = "a1".repeat(32)
        val GROUP_B = "b1".repeat(32)
        val GROUP_C = "c1".repeat(32)
        val MEMBERS_ADMINS = listOf(ConversationTimelineTestIds.ACCOUNT_ID)
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
