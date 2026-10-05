package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.deleteLocalChatsBatch
import dev.ipf.whitenoise.android.state.setLocalDeleteTestReactivation
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGroupDeleteConfirmationOwnerTest {
    @Test
    fun accountOrRuntimeReplacementWhileDialogIsOpenAdmitsZeroDeletes() =
        runTest {
            val state = emptyAppState()
            val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
            val request =
                PendingLocalChatDelete.capture(
                    listOf(chatListItemFromProjection(chatRow("group"))),
                    controller,
                    state,
                )
            assertTrue(request.isCurrent(state, controller))
            state.setLocalDeleteTestReactivation(ACCOUNT_REF)
            assertFalse(request.isCurrent(state, controller))
            state.setLocalDeleteTestReactivation(null)
            state.advanceLocalDeleteTestRuntime()
            var calls = 0
            deleteLocalChatsBatch(request.groupIds, { request.isCurrent(state, controller) }) {
                calls++
                true
            }
            assertEquals(0, calls)
            val other = emptyAppState(activeAccountRef = "another-account")
            assertFalse(request.isCurrent(other, controller))
        }

    @Test
    fun sameAccountNewControllerAndNewBindEpochCannotReuseOldConfirmation() =
        runTest {
            val state = emptyAppState()
            val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
            val request =
                PendingLocalChatDelete.capture(
                    listOf(chatListItemFromProjection(chatRow("group"))),
                    controller,
                    state,
                )
            val replacement = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
            assertFalse(request.isCurrent(state, replacement))
            assertFalse(request.isCurrent(emptyAppState(), controller))
            controller.closeLiveSubscriptionsForAccountTeardown(ACCOUNT_REF)
            assertFalse(request.isCurrent(state, controller))
        }

    @Test
    fun remainingBatchRetryKeepsTheOriginalOwnerFence() {
        val state = emptyAppState()
        val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        val request =
            PendingLocalChatDelete.capture(
                listOf("first", "failed", "unattempted").map { chatListItemFromProjection(chatRow(it)) },
                controller,
                state,
            )
        val retry = request.remaining(1)
        assertEquals(listOf("failed", "unattempted"), retry.groupIds)
        assertTrue(retry.isCurrent(state, controller))
        state.advanceLocalDeleteTestRuntime()
        assertFalse(retry.isCurrent(state, controller))
    }

    @Test
    fun confirmationKeepsItsOriginalSnapshotAndDeduplicatedGroupIds() {
        val state = emptyAppState()
        val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        val items = mutableListOf(chatListItemFromProjection(chatRow("Aa")), chatListItemFromProjection(chatRow("aa")))
        val request = PendingLocalChatDelete.capture(items, controller, state)
        items.clear()
        assertEquals(listOf("Aa"), request.groupIds)
    }
}
