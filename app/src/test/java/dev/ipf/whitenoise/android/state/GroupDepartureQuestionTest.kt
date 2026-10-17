package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductRecordResultFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Leaving as one of several admins steps down first, and MDK may hold that step back (#3289). A departure cancelled
 * while the user is still deciding must withdraw its question and change nothing, rather than demoting and leaving
 * later when someone taps Continue anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupDepartureQuestionTest {
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    /** Cancelling the leave while its question is open withdraws the question; no demotion or leave follows. */
    @Test
    fun cancellingTheLeaveWithdrawsItsQuestion() =
        runBlocking {
            val state = appState()
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = conversationTimelineTestGroup().copy(admins = listOf(SELF, OTHER_ADMIN)),
                    initialMemberSnapshot = GroupMemberSnapshot(roster()),
                )
            // Outside runBlocking's children, so a leave that ignores cancellation fails this test rather than hang.
            val leave = CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) { controller.leaveGroup() }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }

                leave.cancel()
                awaitWithMainLooper { leave.isCompleted }
            } finally {
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertTrue(leave.isCancelled)
            assertNull("the question outlived its departure", state.groupChangeConfirmations.pending)
            assertEquals(1, calls.count { it == "selfDemoteAdminDetailed" })
            assertFalse("the group was left without an answer", "leaveGroup" in calls)
        }

    /** The test thread is Robolectric's main thread, so its looper runs between polls for main-thread steps. */
    private suspend fun awaitWithMainLooper(done: () -> Boolean) {
        withTimeout(QUESTION_TIMEOUT_MILLIS) {
            while (!done()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(POLL_MILLIS)
            }
        }
    }

    /** One signed-in admin whose runtime holds the self-demotion back as catching up. */
    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { SELF },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = SELF,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            initialMarmotRuntime = AppMarmotRuntime("test", catchingUpRuntime()),
        )

    /** Answers the roster read and refuses every self-demotion as catching up; anything else is unexpected. */
    private fun catchingUpRuntime(): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            val name = method.name.substringBefore('-')
            calls += name
            when (name) {
                "groupMembers" -> roster()
                "npub" -> null
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "selfDemoteAdminDetailed" -> failSuspendedCall(arguments, MarmotKitException.GroupCatchingUp(GROUP))
                else -> error("Unexpected native call: $name")
            }
        } as MarmotInterface

    /** Delivers a checked UniFFI error through the suspend continuation, which a proxy cannot throw directly. */
    private fun failSuspendedCall(
        arguments: Array<out Any?>?,
        failure: MarmotKitException,
    ): Any {
        @Suppress("UNCHECKED_CAST")
        val continuation = arguments?.last() as Continuation<Any?>
        continuation.resumeWith(Result.failure(failure))
        return COROUTINE_SUSPENDED
    }

    /** Self, a second admin and one member, so leaving needs a self-demotion first. */
    private fun roster() =
        listOf(
            AppGroupMemberRecordFfi(SELF, ConversationTimelineTestIds.ACCOUNT_REF, true),
            AppGroupMemberRecordFfi(OTHER_ADMIN, null, false),
            AppGroupMemberRecordFfi(MEMBER, null, false),
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
        val SELF = ConversationTimelineTestIds.ACCOUNT_ID
        val GROUP = ConversationTimelineTestIds.GROUP_ID
        val OTHER_ADMIN = "e1".repeat(32)
        val MEMBER = "e2".repeat(32)
        const val QUESTION_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
