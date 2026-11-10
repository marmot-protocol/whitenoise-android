package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.FakeReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.POLL_RESPONSE_KIND
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.StoreReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.controller
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.holdingFirstRead
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.keys
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.page
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Loaded message results are rechecked after relevant changes, without chaining rereads (#1944). */
class GlobalSearchResultsRecheckTest {
    /** A refresh drops a row deleted while listed, adds a newer match, and keeps the other rows in place. */
    @Test
    fun refreshDropsDeletedRowsAndAddsNewMatches() =
        runTest {
            val store = StoreReader((0 until 5).map { record("g", "m$it", at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertEquals(listOf("g:m4", "g:m3", "g:m2", "g:m1", "g:m0"), keys(controller))

            store.replace("m2") { it.copy(deleted = true) }
            store.add(record("g", "fresh", at = 200uL))
            controller.refresh()
            advanceUntilIdle()

            assertEquals(listOf("g:fresh", "g:m4", "g:m3", "g:m1", "g:m0"), keys(controller))
            assertFalse(controller.state.failed)
        }

    /** A refresh asked for while a later page is loading runs once that page lands. */
    @Test
    fun refreshDuringALoadingPageRunsAfterIt() =
        runTest {
            val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            store.replace("m59") { it.copy(deleted = true) }
            controller.loadMore()
            controller.refresh()
            advanceUntilIdle()

            assertFalse(controller.state.results.any { it.messageIdHex == "m59" })
            assertEquals(59, controller.state.results.size)
        }

    /** A failed background refresh keeps every row and shows no failure boundary. */
    @Test
    fun failedRefreshKeepsRowsWithoutAFailure() =
        runTest {
            val first = page(record("g", "a", at = 2uL), record("g", "b", at = 1uL))
            val reader = FakeReader({ first }, { error("local read failed") })
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.refresh()
            advanceUntilIdle()

            assertEquals(listOf("g:a", "g:b"), keys(controller))
            assertFalse(controller.state.failed)
            assertFalse(controller.state.loading)
        }

    /** A tap on Retry or Search older during a background refresh runs after it instead of being dropped. */
    @Test
    fun pageAskedForDuringARefreshRunsAfterIt() =
        runTest {
            val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertEquals(50, controller.state.results.size)

            controller.refresh()
            assertTrue(controller.state.refreshing)
            assertFalse(controller.state.loading)
            controller.loadMore()
            advanceUntilIdle()

            assertEquals(60, controller.state.results.size)
            assertTrue(controller.state.endReached)
        }

    /** A new query forgets a refresh queued for the previous one. */
    @Test
    fun newQueryDropsAQueuedRefresh() =
        runTest {
            val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.loadMore()
            controller.refresh()
            controller.search(GlobalSearchResultsQuery(needle = "there"))
            advanceUntilIdle()

            // The new query reads its first page once; the old query's queued refresh never runs after it.
            assertEquals(1, store.queries.count { it.search == "there" })
            assertEquals(50, controller.state.results.size)
        }

    /** Only changes that can alter the list wake a reread: not reactions, receipts or unrelated messages. */
    @Test
    fun onlyRelevantProjectionUpdatesTouchTheResults() =
        runTest {
            val listed = record("g", "listed", at = 5uL, text = "hello there")
            val controller = controller(FakeReader({ page(listed) }))
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()

            fun upsert(
                record: TimelineMessageRecordFfi,
                trigger: TimelineUpdateTriggerFfi,
            ) = update(record.groupIdHex, TimelineMessageChangeFfi.Upsert(trigger, record))

            assertFalse(controller.isTouchedBy(upsert(listed, TimelineUpdateTriggerFfi.REACTION_ADDED)))
            assertFalse(controller.isTouchedBy(upsert(listed, TimelineUpdateTriggerFfi.RECEIPT_CHANGED)))
            val deleted = listed.copy(deleted = true)
            assertTrue(controller.isTouchedBy(upsert(deleted, TimelineUpdateTriggerFfi.MESSAGE_DELETED)))
            assertTrue(
                controller.isTouchedBy(
                    update("g", TimelineMessageChangeFfi.Remove("listed", TimelineRemoveReasonFfi.entries.first())),
                ),
            )
            val other = record("g", "other", at = 6uL, text = "unrelated words")
            assertFalse(controller.isTouchedBy(upsert(other, TimelineUpdateTriggerFfi.NEW_MESSAGE)))
            val match = record("g2", "match", at = 7uL, text = "hello again")
            assertTrue(controller.isTouchedBy(upsert(match, TimelineUpdateTriggerFfi.NEW_MESSAGE)))
            assertFalse(controller.isTouchedBy(upsert(match, TimelineUpdateTriggerFfi.REACTION_ADDED)))
        }

    /** A row deleted while its page is still being read is not left listed: the queued recheck drops it. */
    @Test
    fun deletionDuringAPageReadIsRecheckedAfterIt() =
        runTest {
            val store = StoreReader((0 until 3).map { record("g", "m$it", at = 100uL + it.toULong()) })
            // The first page is read from the store at once, then held, as an IO read in flight would be.
            val (reader, gate) = holdingFirstRead(store)
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertTrue(controller.state.loading)

            store.replace("m1") { it.copy(deleted = true) }
            val deleted = store.row("m1")
            val change = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.MESSAGE_DELETED, deleted)
            assertTrue(controller.isTouchedBy(update("g", change)))
            controller.refresh()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("g:m2", "g:m0"), keys(controller))
        }

    /** A removal arriving during the very first page queues a recheck instead of being dropped. */
    @Test
    fun removalDuringTheFirstPageIsQueued() =
        runTest {
            val store = StoreReader((0 until 2).map { record("g", "m$it", at = 100uL + it.toULong()) })
            val (reader, gate) = holdingFirstRead(store)
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertEquals(0, controller.state.batches)

            store.replace("m1") { it.copy(deleted = true) }
            val removal = TimelineMessageChangeFfi.Remove("m1", TimelineRemoveReasonFfi.entries.first())
            assertTrue(controller.isTouchedBy(update("g", removal)))
            controller.refresh()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, store.queries.size)
            assertEquals(listOf("g:m0"), keys(controller))
        }

    /** During a background refresh, unrelated or non-matching new messages never queue another refresh. */
    @Test
    fun unrelatedTrafficDuringARefreshDoesNotChainRefreshes() =
        runTest {
            val store = StoreReader((0 until 2).map { record("chosen", "m$it", at = 100uL + it.toULong()) })
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val reader =
                GlobalSearchTimelineReader { query ->
                    val page = store.page(query)
                    if (++reads == 2) gate.await()
                    page
                }
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = setOf("chosen")))
            advanceUntilIdle()
            controller.refresh()
            advanceUntilIdle()
            assertTrue(controller.state.refreshing)

            fun touched(
                group: String,
                id: String,
                text: String,
                trigger: TimelineUpdateTriggerFfi,
            ) = controller.isTouchedBy(
                update(group, TimelineMessageChangeFfi.Upsert(trigger, record(group, id, at = 900uL, text = text))),
            )

            assertFalse(touched("elsewhere", "e1", "hello from elsewhere", TimelineUpdateTriggerFfi.NEW_MESSAGE))
            assertFalse(touched("chosen", "n1", "unrelated words", TimelineUpdateTriggerFfi.NEW_MESSAGE))
            assertFalse(touched("chosen", "n2", "hello", TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED))
            assertFalse(touched("elsewhere", "e2", "hello", TimelineUpdateTriggerFfi.MESSAGE_DELETED))
            assertTrue(touched("chosen", "n3", "hello again", TimelineUpdateTriggerFfi.NEW_MESSAGE))
            assertTrue(touched("chosen", "old", "hello", TimelineUpdateTriggerFfi.MESSAGE_DELETED))

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, reads)
        }

    /** A moderator deletion delivered as a snapshot reprojection of a held row queues a recheck that drops it. */
    @Test
    fun snapshotReprojectionDuringAPageReadIsRecheckedAfterIt() =
        runTest {
            val store = StoreReader((0 until 3).map { record("g", "m$it", at = 100uL + it.toULong()) })
            val (reader, gate) = holdingFirstRead(store)
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()

            store.replace("m2") { it.copy(deleted = true) }
            val change = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH, store.row("m2"))
            assertTrue(controller.isTouchedBy(update("g", change)))
            controller.refresh()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("g:m1", "g:m0"), keys(controller))
        }

    /** Reprojections of rows search never lists, such as poll responses, do not queue a refresh during one. */
    @Test
    fun nonSearchableReprojectionsDuringARefreshDoNotQueueAnother() =
        runTest {
            val store = StoreReader((0 until 2).map { record("g", "m$it", at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.refresh()
            assertTrue(controller.state.refreshing)

            val pollResponse = record("g", "vote", at = 200uL, text = "hello", kind = POLL_RESPONSE_KIND)
            val reprojected = TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED
            val change = TimelineMessageChangeFfi.Upsert(reprojected, pollResponse)
            assertFalse(controller.isTouchedBy(update("g", change)))
            val snapshot = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH, pollResponse)
            assertFalse(controller.isTouchedBy(update("g", snapshot)))
        }

    /** The observer asks whether to wake only after subscribing, so a resume begun meanwhile still counts. */
    @Test
    fun observerAsksWhetherToWakeAfterSubscribing() =
        runTest {
            val order = mutableListOf<String>()
            val runtime =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, _ ->
                    order += method.name
                    // No event stream here; the probe decision is what is under test.
                    error("no subscription in this test")
                } as MarmotInterface
            val wakeups = Channel<Unit>(Channel.CONFLATED)
            val opened =
                observeLibraryChanges(runtime, "account", wakeups) {
                    order += "wakeOnOpen"
                    true
                }

            assertFalse(opened)
            assertEquals(listOf("subscribeEvents", "wakeOnOpen"), order.map { it.substringBefore('-') })
            assertTrue(wakeups.tryReceive().isSuccess)
        }

    /** A returning session reports resuming as soon as its search starts, before any page is read. */
    @Test
    fun resumeIsVisibleAsSoonAsSearchStarts() =
        runTest {
            val controller = controller(StoreReader(listOf(record("g", "m", at = 1uL))))
            controller.search(GlobalSearchResultsQuery(needle = "hello"), resumeTarget = 40)

            assertEquals(0, controller.state.batches)
            assertTrue(controller.state.resuming)
            advanceUntilIdle()
        }
}
