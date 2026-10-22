package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Full-list reads share the actual controller deletion's admission and native-commit boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class AccountWideLocalRemovalTest {
    private val controllers = mutableListOf<ChatsController>()

    @After fun closeControllers() = controllers.forEach(ChatsController::onCleared)

    @Test fun offWindowDeleteKeepsNewReadsBehindNativeCommit() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val fixture = heldRemovalFixture(entered, release, onRead = { reads++ }, includeSurvivor = true)
            // The native presented read owns this row, while the retained window now only holds survivor.
            fixture.controller.applyChatListSubscriptionUpdate(
                LOCAL_DELETE_ACCOUNT,
                dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi.RemoveRow(
                    dev.ipf.marmotkit.ChatListUpdateTriggerFfi.REMOVED,
                    LOCAL_DELETE_GROUP,
                ),
            )
            fixture.controller.applyChatListRow(chatRow(SURVIVOR))
            val original = requireNotNull(fixture.controller.loadAccountWideForwardTargets())
            assertTrue(original.chatListItems(emptyList()).any { it.id == LOCAL_DELETE_GROUP })
            val deletion = async { fixture.controller.deleteGroupLocalFromChatList(LOCAL_DELETE_GROUP) }
            val replacement =
                async {
                    entered.await()
                    fixture.controller.readAccountWideForwardTargets()
                }
            try {
                entered.await()
                testScheduler.runCurrent()
                assertFalse(original.hasCurrentMembership)
                assertFalse(replacement.isCompleted)
                assertEquals(1, reads)
                release.complete(Unit)
                assertTrue(deletion.await())
                replacement.await()
                val fresh = requireNotNull(fixture.controller.loadAccountWideForwardTargets())
                assertEquals(listOf(SURVIVOR), fresh.chatListItems(emptyList()).map { it.id })
            } finally {
                release.complete(Unit)
                deletion.cancelAndJoin()
                replacement.cancelAndJoin()
            }
        }

    @Test fun failedNativeDeleteReleasesReaderAndRestoresRetainedRow() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = heldRemovalFixture(entered, release, beforeCommit = { error("delete rejected") })
            val deletion = async { fixture.controller.deleteGroupLocalFromChatList(LOCAL_DELETE_GROUP) }
            try {
                entered.await()
                assertFalse(fixture.controller.containsGroup(LOCAL_DELETE_GROUP))
                val replacement = async { fixture.controller.readAccountWideForwardTargets() }
                testScheduler.runCurrent()
                assertFalse(replacement.isCompleted)
                release.complete(Unit)
                assertFalse(deletion.await())
                replacement.await()
                assertTrue(fixture.controller.containsGroup(LOCAL_DELETE_GROUP))
                val fresh = requireNotNull(fixture.controller.loadAccountWideForwardTargets())
                assertTrue(fresh.chatListItems(emptyList()).any { it.id == LOCAL_DELETE_GROUP })
            } finally {
                release.complete(Unit)
                deletion.cancelAndJoin()
            }
        }

    @Test fun cancelledNativeDeleteRollsBackAndReleasesSnapshotAdmission() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = heldRemovalFixture(entered, release)
            val deletion = async { fixture.controller.deleteGroupLocalFromChatList(LOCAL_DELETE_GROUP) }
            try {
                entered.await()
                val replacement = async { fixture.controller.readAccountWideForwardTargets() }
                testScheduler.runCurrent()
                assertFalse(replacement.isCompleted)
                deletion.cancelAndJoin()
                replacement.await()
                assertTrue(fixture.controller.containsGroup(LOCAL_DELETE_GROUP))
                val fresh = requireNotNull(fixture.controller.loadAccountWideForwardTargets())
                assertTrue(fresh.chatListItems(emptyList()).any { it.id == LOCAL_DELETE_GROUP })
            } finally {
                deletion.cancelAndJoin()
                release.complete(Unit)
            }
        }

    private fun TestScope.heldRemovalFixture(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        beforeCommit: () -> Unit = {},
        onRead: () -> Unit = {},
        includeSurvivor: Boolean = false,
    ): LocalGroupDeleteFixture =
        localGroupDeleteFixture(
            controllers,
            beforeDelete = {
                entered.complete(Unit)
                release.await()
                beforeCommit()
            },
            configureState = { state, present ->
                state.liveSubscriptionOverrides.chatList =
                    reader {
                        onRead()
                        val target = if (present.get()) listOf(presentedRow(LOCAL_DELETE_GROUP)) else emptyList()
                        if (includeSurvivor) target + presentedRow(SURVIVOR) else target
                    }
            },
        )

    private fun reader(read: suspend () -> List<dev.ipf.marmotkit.PresentedChatRowFfi>) =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, _ -> error("not used") },
            openChats = { _, _ -> error("not used") },
            presentedChatList = { _, _ -> read() },
        )

    private companion object {
        val SURVIVOR = "c1".repeat(16)
    }
}
