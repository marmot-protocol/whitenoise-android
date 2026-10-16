package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            val conversation = dmConversation(fixture)
            assertTrue(conversation.forgetGroupLocal())
            assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
            assertTrue(fixture.calls.none { it in DEPARTURE_MUTATIONS || it == "deleteGroupLocal" })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertEquals(AppText.Resource(R.string.toast_chat_abandoned), fixture.state.transientNotice?.title)
        }

    /** A committed DM forget whose Android cleanup fails still closes the chat and repairs from the journal only. */
    @Test
    fun dmAbandonmentCleanupFailureClosesTheChatAndNeverReplaysTheForget() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("selectedMessageDraft")
                val conversation = dmConversation(fixture)
                assertTrue("a committed forget must close the old conversation", conversation.forgetGroupLocal())
                assertTrue(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                fixture.failureMethod.set(null)
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                // A repeated confirm reads the absent row and reports the reset instead of forgetting again.
                assertTrue(conversation.forgetGroupLocal())
                assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
                assertEquals(
                    AppText.Resource(R.string.toast_group_reset_already_waiting),
                    fixture.state.transientNotice?.title,
                )
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

    /** A failed forget restores the row with the abandonment wording, and a later confirm forgets exactly once. */
    @Test
    fun chatListAbandonmentFailureRestoresTheRowAndARetryForgetsOnce() =
        runTest {
            val fixture = fixture("forgetGroupLocal")
            assertFalse(fixture.controller.abandonChatFromList(GROUP, isDm = true))
            flushLocalDeleteChatRows()
            assertTrue(fixture.controller.items.any { it.id == GROUP })
            val failure = requireNotNull(fixture.state.toast)
            assertEquals(AppText.Resource(R.string.toast_couldnt_abandon_chat), failure.title)
            assertNotNull("the failure keeps a scoped retry", failure.localDeleteNotice?.retry)
            fixture.failureMethod.set(null)
            val forgetsBefore = fixture.calls.count { it == "forgetGroupLocal" }
            assertTrue(fixture.controller.abandonChatFromList(GROUP, isDm = true))
            assertEquals(forgetsBefore + 1, fixture.calls.count { it == "forgetGroupLocal" })
            assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
            assertEquals(null, fixture.state.toast)
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

    /** A Group Details controller for the fixture row presented as a freshly started direct chat. */
    private fun dmConversation(fixture: LocalGroupDeleteFixture): ConversationController =
        ConversationController(
            appState = fixture.state,
            initialGroup = chatListItemFromProjection(chatRow(GROUP)).group,
            initialIsDm = true,
        ).also { assertTrue(it.isDm) }

    /** The shared fake-native fixture, cleared with this test's controllers. */
    private fun TestScope.fixture(
        failing: String? = null,
        onRowRead: () -> Unit = {},
    ): LocalGroupDeleteFixture = localGroupDeleteFixture(controllers, failing, onRowRead)

    private companion object {
        val GROUP = LOCAL_DELETE_GROUP
        val DEPARTURE_MUTATIONS =
            setOf("leaveGroup", "selfDemoteAdmin", "selfDemoteAdminDetailed", "promoteAdmin", "promoteAdminDetailed")
    }
}
