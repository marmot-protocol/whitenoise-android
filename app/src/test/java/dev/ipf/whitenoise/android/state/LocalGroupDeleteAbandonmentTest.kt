package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3310 local abandonment through the real Group Details and chat-list controllers: MDK's forget behind the local
 * removal journal, its notices and retries, with no leave or admin mutation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class LocalGroupDeleteAbandonmentTest {
    private val controllers = mutableListOf<ChatsController>()

    /** Clears every chat-list controller a test created. */
    @After
    fun closeControllers() {
        controllers.forEach(ChatsController::onCleared)
    }

    /** A newly started DM is abandoned through the journaled forget with no leave or admin mutation. */
    @Test
    fun dmAbandonmentFromDetailsForgetsOnceWithoutAnyDepartureMutation() =
        runTest {
            val fixture = fixture()
            val conversation = conversation(fixture, isDm = true)
            assertTrue(conversation.forgetGroupLocal())
            assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
            assertTrue(fixture.calls.none { it in DEPARTURE_MUTATIONS || it == "deleteGroupLocal" })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertGlobalNotice(fixture, R.string.toast_chat_abandoned)
        }

    /** A group reset and a local delete from Group Details confirm app-wide, since both close the conversation. */
    @Test
    fun detailsRemovalsConfirmOutsideTheClosedConversation() =
        runTest {
            val fixture = fixture()
            assertTrue(conversation(fixture, isDm = false).forgetGroupLocal())
            assertGlobalNotice(fixture, R.string.toast_group_reset_done)
            val deleting = localGroupDeleteFixture(controllers)
            assertTrue(conversation(deleting, isDm = false).deleteGroupLocal())
            assertEquals(1, deleting.calls.count { it == "deleteGroupLocal" })
            assertGlobalNotice(deleting, R.string.toast_chat_deleted_local)
        }

    /** A committed DM forget whose Android cleanup fails still closes the chat and repairs from the journal only. */
    @Test
    fun dmAbandonmentCleanupFailureClosesTheChatAndNeverReplaysTheForget() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("selectedMessageDraft")
                val conversation = conversation(fixture, isDm = true)
                assertTrue("a committed forget must close the old conversation", conversation.forgetGroupLocal())
                assertTrue(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                fixture.failureMethod.set(null)
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                // A repeated confirm reads the absent row and reports the reset instead of forgetting again.
                assertTrue(conversation.forgetGroupLocal())
                assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
                assertGlobalNotice(fixture, R.string.toast_chat_already_abandoned)
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** The chat-list row action uses the same forget and journal, removing the row without any departure. */
    @Test
    fun chatListAbandonmentForgetsTheReviewedRowOnceAndReportsIt() =
        runTest {
            val fixture = fixture()
            assertTrue(fixture.controller.abandonChatFromList(GROUP, isDm = true))
            flushLocalDeleteChatRows()
            assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
            assertTrue(fixture.calls.none { it in DEPARTURE_MUTATIONS || it == "deleteGroupLocal" })
            assertTrue(fixture.controller.items.none { it.id == GROUP })
            assertEquals(AppText.Resource(R.string.toast_chat_abandoned), fixture.state.transientNotice?.title)
        }

    /**
     * A failed forget restores the row with abandonment wording, and the notice's own Retry forgets exactly once
     * and confirms the abandonment, never a local delete.
     */
    @Test
    fun chatListAbandonmentFailureRestoresTheRowAndItsRetryForgetsOnceInAbandonmentWording() =
        runTest {
            // The notice's Retry launches on the app's mutation scope, which the test dispatcher then drives.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("forgetGroupLocal")
                assertFalse(fixture.controller.abandonChatFromList(GROUP, isDm = true))
                testScheduler.advanceTimeBy(ROW_PROJECTION_SETTLE_MS)
                testScheduler.runCurrent()
                flushLocalDeleteChatRows()
                assertTrue("a failed forget restores the row", fixture.controller.items.any { it.id == GROUP })
                val failure = requireNotNull(fixture.state.toast)
                assertEquals(AppText.Resource(R.string.toast_couldnt_abandon_chat), failure.title)
                assertEquals(AppText.Resource(R.string.local_abandon_retry_detail), failure.detail)
                fixture.failureMethod.set(null)
                val forgetsBefore = fixture.calls.count { it == "forgetGroupLocal" }
                retry(fixture, failure)
                assertEquals(forgetsBefore + 1, fixture.calls.count { it == "forgetGroupLocal" })
                assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
                assertEquals(null, fixture.state.toast)
                assertEquals(AppText.Resource(R.string.toast_chat_abandoned), fixture.state.transientNotice?.title)
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A group's failed reset keeps the reset wording through its notice and its retry's confirmation. */
    @Test
    fun chatListGroupResetFailureAndRetryKeepTheResetWording() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("forgetGroupLocal")
                assertFalse(fixture.controller.abandonChatFromList(GROUP, isDm = false))
                val failure = requireNotNull(fixture.state.toast)
                assertEquals(AppText.Resource(R.string.toast_couldnt_reset_group), failure.title)
                assertEquals(AppText.Resource(R.string.local_reset_retry_detail), failure.detail)
                fixture.failureMethod.set(null)
                retry(fixture, failure)
                assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
                assertEquals(AppText.Resource(R.string.toast_group_reset_done), fixture.state.transientNotice?.title)
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A failed media listing never blocks abandonment: the forget runs once and only media keys are skipped. */
    @Test
    fun failedMediaListingStillAbandonsWithoutMediaKeys() =
        runTest {
            val fixture = fixture("listMedia")
            assertTrue(fixture.controller.abandonChatFromList(GROUP, isDm = true))
            flushLocalDeleteChatRows()
            val listings = fixture.calls.count { it == "listMedia" }
            assertTrue("the listing is retried before it is skipped", listings > 1)
            assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
            assertTrue(fixture.controller.items.none { it.id == GROUP })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertEquals(null, fixture.state.toast)
            assertEquals(AppText.Resource(R.string.toast_chat_abandoned), fixture.state.transientNotice?.title)
        }

    /** Runs the failure notice's own Retry, as its action button does, and waits for the launched mutation. */
    private suspend fun retry(
        fixture: LocalGroupDeleteFixture,
        failure: ToastMessage,
    ) {
        val notice = requireNotNull(failure.localDeleteNotice) { "the failure keeps a scoped retry" }
        requireNotNull(notice.retry).invoke(notice.groupIds)
        fixture.state.mutationsScope.coroutineContext[Job]
            ?.children
            ?.toList()
            ?.forEach { it.join() }
    }

    /** A Group Details controller for the fixture row, presented as a direct chat when [isDm]. */
    private fun conversation(
        fixture: LocalGroupDeleteFixture,
        isDm: Boolean,
    ): ConversationController =
        ConversationController(
            appState = fixture.state,
            initialGroup = chatListItemFromProjection(chatRow(GROUP)).group,
            initialIsDm = isDm,
        ).also { assertEquals(isDm, it.isDm) }

    /** The confirmation is app-wide, not scoped to the conversation the removal just closed. */
    private fun assertGlobalNotice(
        fixture: LocalGroupDeleteFixture,
        title: Int,
    ) {
        val notice = requireNotNull(fixture.state.transientNotice) { "the removal must confirm itself" }
        assertEquals(AppText.Resource(title), notice.title)
        assertEquals("a closed conversation's scope could never show it", null, notice.conversation)
    }

    /** The shared fake-native fixture, cleared with this test's controllers. */
    private fun TestScope.fixture(
        failing: String? = null,
        onRowRead: () -> Unit = {},
    ): LocalGroupDeleteFixture = localGroupDeleteFixture(controllers, failing, onRowRead)

    private companion object {
        const val ROW_PROJECTION_SETTLE_MS = 100L
        val GROUP = LOCAL_DELETE_GROUP
        val DEPARTURE_MUTATIONS =
            setOf("leaveGroup", "selfDemoteAdmin", "selfDemoteAdminDetailed", "promoteAdmin", "promoteAdminDetailed")
    }
}
