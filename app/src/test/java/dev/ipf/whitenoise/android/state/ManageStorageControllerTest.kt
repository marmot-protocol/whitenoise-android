package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manage Storage's overview controller over a fake MarmotKit access. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageStorageControllerTest {
    /** Opening reads usage, Keep media and the first page of chats. */
    @Test fun refreshLoadsUsageKeepMediaAndChats() =
        runTest {
            val access = FakeStorageAccess().apply { chatPages = listOf(listOf(chat("a", 30), chat("b", 10))) }
            val controller = open(access)
            val state = controller.state.value
            assertEquals(access.usage, state.usage)
            assertEquals(KeepMediaPeriod.Forever, state.keepMedia)
            assertEquals(listOf("a", "b"), state.chats.map { it.groupIdHex })
            assertTrue(state.chatsComplete)
            controller.close()
        }

    /** Chats page in through the cursor, dropping a chat repeated across pages. */
    @Test fun loadMoreChatsAppendsTheNextPageWithoutDuplicates() =
        runTest {
            val pages = listOf(listOf(chat("a", 30)), listOf(chat("a", 30), chat("b", 5)))
            val access = FakeStorageAccess().apply { chatPages = pages }
            val controller = open(access)
            assertFalse(controller.state.value.chatsComplete)
            controller.loadMoreChats()
            runCurrent()
            assertEquals(
                listOf("a", "b"),
                controller.state.value.chats
                    .map { it.groupIdHex },
            )
            assertTrue(controller.state.value.chatsComplete)
            assertTrue(access.cursorsClosed >= 1)
            controller.close()
        }

    /** A failed page keeps its cursor, so loading again resumes from the same page. */
    @Test fun failedChatPageResumesFromItsCursor() =
        runTest {
            val pages = listOf(listOf(chat("a", 30)), listOf(chat("b", 5)))
            val access = FakeStorageAccess().apply { chatPages = pages }
            val controller = open(access)
            access.failNextChatPage = true
            controller.loadMoreChats()
            runCurrent()
            assertTrue(controller.state.value.chatsFailed)
            controller.loadMoreChats()
            runCurrent()
            assertEquals(
                listOf("a", "b"),
                controller.state.value.chats
                    .map { it.groupIdHex },
            )
            assertFalse(controller.state.value.chatsFailed)
            controller.close()
        }

    /** A removal reports its count and the bytes measured as freed, then refreshes every figure. */
    @Test fun removalReportsCountAndFreedBytes() =
        runTest {
            val access = FakeStorageAccess().apply { removed = 3 }
            val controller = open(access)
            access.usage = access.usage.copy(attachmentBytes = 400)
            controller.perform(StorageAction.RemoveChat("a"))
            runCurrent()
            val state = controller.state.value
            assertEquals(StorageOutcome.Removed(count = 3, freedBytes = 600), state.outcome)
            assertEquals(400L, state.usage?.attachmentBytes)
            assertEquals(listOf("removeChat:a"), access.calls)
            assertFalse(state.busy)
            assertEquals(1, state.revision)
            controller.close()
        }

    /** A removal whose follow-up usage read fails still reports its count, but no freed space it could not measure. */
    @Test fun removalWithFailedUsageReadOmitsFreedBytes() =
        runTest {
            val access = FakeStorageAccess().apply { removed = 2 }
            val controller = open(access)
            access.failUsage = true
            controller.perform(StorageAction.RemoveChat("a"))
            runCurrent()
            assertEquals(StorageOutcome.Removed(count = 2, freedBytes = null), controller.state.value.outcome)
            controller.close()
        }

    /** After a failed read the kept figure is stale, so the next removal measures nothing rather than inflating it. */
    @Test fun staleUsageNeverAnchorsTheNextRemoval() =
        runTest {
            val access = FakeStorageAccess().apply { removed = 1 }
            val controller = open(access)
            access.failUsage = true
            controller.perform(StorageAction.RemoveChat("a"))
            runCurrent()
            controller.outcomeShown()
            access.failUsage = false
            access.usage = access.usage.copy(attachmentBytes = 850)
            controller.perform(StorageAction.RemoveChat("b"))
            runCurrent()
            assertEquals(StorageOutcome.Removed(count = 1, freedBytes = null), controller.state.value.outcome)
            controller.close()
        }

    /** Nothing matching is reported as such, while an unchanged Keep media window reports it was saved. */
    @Test fun emptyRemovalsAreDistinguishedFromSavedKeepMedia() =
        runTest {
            val access = FakeStorageAccess()
            val controller = open(access)
            controller.perform(StorageAction.RemoveOlder(cutoffSeconds = 100, groupIdHex = null))
            runCurrent()
            assertEquals(StorageOutcome.NothingRemoved, controller.state.value.outcome)
            controller.outcomeShown()
            controller.perform(StorageAction.SetKeepMedia(KeepMediaPeriod.OneYear))
            runCurrent()
            assertEquals(StorageOutcome.KeepMediaSaved, controller.state.value.outcome)
            assertEquals(KeepMediaPeriod.OneYear, controller.state.value.keepMedia)
            controller.close()
        }

    /** A failed action reports failure and keeps the last figures on screen. */
    @Test fun failedActionReportsFailure() =
        runTest {
            val access = FakeStorageAccess().apply { failActions = true }
            val controller = open(access)
            controller.perform(StorageAction.ClearCaches)
            runCurrent()
            assertEquals(StorageOutcome.Failed, controller.state.value.outcome)
            assertEquals(access.usage, controller.state.value.usage)
            controller.close()
        }

    /** Only one action runs at a time, a second request while busy is ignored. */
    @Test fun secondActionWhileBusyIsIgnored() =
        runTest {
            val access = FakeStorageAccess()
            val controller = open(access)
            controller.perform(StorageAction.RemoveChat("a"))
            controller.perform(StorageAction.RemoveChat("b"))
            runCurrent()
            assertEquals(listOf("removeChat:a"), access.calls)
            controller.close()
        }

    /** A result that arrives after the account changed is dropped and the screen becomes unavailable. */
    @Test fun lateResultsAfterOwnerChangeAreDropped() =
        runTest {
            val access = FakeStorageAccess()
            val controller = open(access)
            access.current = false
            controller.perform(StorageAction.RemoveChat("a"))
            controller.refresh()
            runCurrent()
            assertTrue(access.calls.isEmpty())
            assertFalse(controller.state.value.available)
            assertNull(controller.state.value.usage)
            controller.close()
        }

    /** A failed usage read is reported while Keep media and chats still load. */
    @Test fun usageFailureIsReportedSeparately() =
        runTest {
            val access = FakeStorageAccess().apply { failUsage = true }
            val controller = open(access)
            assertTrue(controller.state.value.usageFailed)
            assertNull(controller.state.value.usage)
            assertEquals(KeepMediaPeriod.Forever, controller.state.value.keepMedia)
            controller.close()
        }

    /** While attachments move between layouts, usage is read again on the migration cadence. */
    @Test fun runningMigrationPollsUsage() =
        runTest {
            val access = FakeStorageAccess()
            access.usage = access.usage.copy(migration = StorageMigration.Moving, migrationBytes = 50)
            val controller = open(access)
            val reads = access.usageReads
            advanceTimeBy(15_001)
            runCurrent()
            assertEquals(reads + 1, access.usageReads)
            controller.close()
        }

    /** Without an access the controller is unavailable and never loads. */
    @Test fun missingAccessIsUnavailable() =
        runTest {
            val controller = ManageStorageController(null, this)
            controller.refresh()
            runCurrent()
            assertFalse(controller.state.value.available)
            controller.close()
        }

    /** Builds a controller over [access] and runs its first refresh. */
    private fun TestScope.open(access: FakeStorageAccess): ManageStorageController =
        ManageStorageController(access, this).also {
            it.refresh()
            runCurrent()
        }
}

/** A chat entry of [bytes] with one attachment per byte, for ordering checks. */
internal fun chat(
    id: String,
    bytes: Long,
) = ChatStorageEntry(id, bytes, bytes)

/** An attachment in chat [group] of [bytes]. */
internal fun attachment(
    message: String,
    bytes: Long,
    group: String = "g",
) = StoredAttachment(group, message, message, 0, bytes, "image/jpeg", 1_700_000_000)

/** Scriptable [ManageStorageAccess] that records calls and hands out pages in order. */
internal class FakeStorageAccess : ManageStorageAccess {
    var current = true
    var usage = StorageUsage(1_000, 4, 200, 0, 50, 10)
    var keep = KeepMediaPeriod.Forever
    var chatPages: List<List<ChatStorageEntry>> = listOf(emptyList())
    var attachmentPages: List<List<StoredAttachment>> = listOf(emptyList())
    var removed = 0L
    var failActions = false
    var failUsage = false
    var failNextChatPage = false
    var usageReads = 0
    var cursorsClosed = 0
    val calls = mutableListOf<String>()

    /** Reports the scripted ownership. */
    override fun isCurrent(): Boolean = current

    /** Counts the read and returns the scripted usage, or fails. */
    override suspend fun usage(): StorageUsage {
        usageReads++
        check(!failUsage) { "usage" }
        return usage
    }

    /** Returns the scripted window. */
    override suspend fun keepMedia(): KeepMediaPeriod = keep

    /** Returns the scripted chat page after [cursor]. */
    override suspend fun chats(cursor: StorageCursor?): StoragePage<ChatStorageEntry> {
        if (failNextChatPage) {
            failNextChatPage = false
            error("chats")
        }
        return page(chatPages, cursor)
    }

    /** Returns the scripted attachment page after [cursor]. */
    override suspend fun attachments(
        groupIdHex: String?,
        cursor: StorageCursor?,
    ): StoragePage<StoredAttachment> = page(attachmentPages, cursor)

    /** Records the call and stores the window. */
    override suspend fun setKeepMedia(period: KeepMediaPeriod): Long = act("keep:$period") { keep = period }

    /** Records the call. */
    override suspend fun removeOlderThan(
        receivedBeforeSeconds: Long,
        groupIdHex: String?,
    ): Long = act("older:$receivedBeforeSeconds:$groupIdHex")

    /** Records the call. */
    override suspend fun removeChat(groupIdHex: String): Long = act("removeChat:$groupIdHex")

    /** Records the call. */
    override suspend fun removeAttachments(items: List<StoredAttachment>): Long = act("remove:${items.map { it.key }}")

    /** Records the call. */
    override suspend fun clearCaches() {
        act("clear")
    }

    /** Records [call], applies [effect] and returns the scripted count, or fails. */
    private fun act(
        call: String,
        effect: () -> Unit = {},
    ): Long {
        check(!failActions) { call }
        calls += call
        effect()
        return removed
    }

    /** Returns the page after [cursor]'s index, with a closing-tracked cursor when more pages follow. */
    private fun <T> page(
        pages: List<List<T>>,
        cursor: StorageCursor?,
    ): StoragePage<T> {
        val index = (cursor?.handle as PageIndex?)?.index ?: 0
        val next = if (index + 1 < pages.size) StorageCursor(PageIndex(index + 1) { cursorsClosed++ }) else null
        return StoragePage(pages[index], next)
    }

    private class PageIndex(
        val index: Int,
        val onClose: () -> Unit,
    ) : AutoCloseable {
        /** Counts the close. */
        override fun close() = onClose()
    }
}
