package dev.ipf.whitenoise.android.ui.medialibrary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Late native callbacks cannot restore protocol rows after their route or identity owner ends. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaLibraryLifecycleTest {
    /** Recomposition with an A-bound controller while B is active must not construct another A pager. */
    @Test
    fun reboundActiveAccountCannotAdmitTheOldController() {
        assertEquals("A", attachmentLibraryAccount("A", "A"))
        assertNull(attachmentLibraryAccount("A", "B"))
        assertNull(attachmentLibraryAccount("A", null))
        assertEquals("B", attachmentLibraryAccount("B", "B"))
    }

    /** Off-window removals still wake the library, while other accounts and groups cannot refresh it. */
    @Test
    fun oldRowInvalidationUsesAccountAndGroupProjectionWakeup() {
        val event =
            dev.ipf.marmotkit.MarmotEventFfi.ProjectionUpdated(
                dev.ipf.marmotkit.RuntimeProjectionUpdateFfi(
                    "id",
                    "A",
                    dev.ipf.marmotkit.TimelineProjectionUpdateFfi(
                        "group",
                        emptyList(),
                        listOf(
                            dev.ipf.marmotkit.TimelineMessageChangeFfi.Remove(
                                "off-window-row",
                                dev.ipf.marmotkit.TimelineRemoveReasonFfi.INVALIDATED,
                            ),
                        ),
                        null,
                        dev.ipf.marmotkit.ChatListUpdateTriggerFfi.NEW_GROUP,
                    ),
                ),
            )
        assertTrue(attachmentProjectionTouched(event, "A", "group"))
        org.junit.Assert.assertFalse(attachmentProjectionTouched(event, "B", "group"))
        org.junit.Assert.assertFalse(attachmentProjectionTouched(event, "A", "other"))
    }

    /** Disposal fences even a native read that returns after its coroutine was cancelled. */
    @Test
    fun routeDisposalCancelsAndFencesLatePage() =
        runTest {
            val released = CompletableDeferred<Unit>()
            val source = GroupAttachmentFixture(listOf(historyEntry(1)))
            source.beforePage = { withContext(NonCancellable) { released.await() } }
            val pager = GroupAttachmentPager(source)
            val job = launch { pager.refresh() }
            runCurrent()
            pager.close()
            job.cancel()
            released.complete(Unit)
            job.join()
            assertTrue(pager.state.entries.isEmpty())
            assertTrue(source.versions.all { it.closes == 1 })
            val replacement = GroupAttachmentPager(GroupAttachmentFixture(listOf(historyEntry(2))))
            replacement.refresh()
            assertEquals(
                "message-2",
                replacement.state.entries
                    .single()
                    .messageIdHex,
            )
            replacement.close()
        }

    /** The account, group and runtime predicates each independently reject old responses. */
    @Test
    fun accountGroupAndRuntimeChangesFenceLatePages() =
        runTest {
            listOf("account", "group", "runtime").forEach { owner ->
                var current = owner
                val released = CompletableDeferred<Unit>()
                val source = GroupAttachmentFixture(listOf(historyEntry(1)))
                source.beforePage = { released.await() }
                val pager = GroupAttachmentPager(source, isCurrent = { current == owner })
                val job = launch { pager.refresh() }
                runCurrent()
                current = "replacement"
                released.complete(Unit)
                job.join()
                assertTrue(pager.state.entries.isEmpty())
                pager.close()
                assertTrue(source.versions.all { it.closes == 1 })
            }
        }

    /** Borrowed cursors stay alive until an in-flight native call has returned, even on disposal. */
    @Test
    fun disposalDoesNotCloseBorrowedCursorDuringRead() =
        runTest {
            val source = GroupAttachmentFixture((30 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            val cursor = source.cursors.single()
            val released = CompletableDeferred<Unit>()
            source.beforePage = { released.await() }
            val job = launch { pager.loadMore() }
            runCurrent()
            pager.close()
            assertEquals(0, cursor.closes)
            released.complete(Unit)
            job.join()
            assertTrue(source.cursors.all { it.closes == 1 })
            assertTrue(source.versions.all { it.closes == 1 })
            assertTrue(pager.state.entries.isEmpty())
        }
}
