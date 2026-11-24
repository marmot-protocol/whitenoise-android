package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AttachmentEntryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A share's availability check reads MDK's attachment history, never the rows the gallery happens to show. */
class SelectedPhotoHistoryTest {
    private val rows = (40 downTo 1).map { historyEntry(it) }

    /** While a refresh has emptied the gallery to reload it, the selected photos still resolve. */
    @Test
    fun aReloadingGalleryStillResolvesItsSelectedPhotos() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            val selected = tiles(rows.take(2))
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hold = true
            source.beforePage = {
                if (hold) {
                    hold = false
                    reached.complete(Unit)
                    release.await()
                }
            }
            source.rows = listOf(historyEntry(41)) + rows
            source.additions++
            val refreshing = async { pager.refresh() }
            reached.await()
            assertTrue(pager.state.entries.isEmpty())

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(emptyList<SharedMediaTile>(), unavailableSelectedPhotos(selected, current))
            release.complete(Unit)
            refreshing.await()
        }

    /** A photo on a page the gallery doesn't hold, as after a restart keeps only the first page, still resolves. */
    @Test
    fun aPhotoBeyondTheLoadedPagesStillResolves() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            val selected = tiles(listOf(rows[30]))

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(emptyList<SharedMediaTile>(), unavailableSelectedPhotos(selected, current))
            assertEquals(13, pager.state.entries.size)
        }

    /** A photo MDK no longer lists is reported unavailable. */
    @Test
    fun aPhotoGoneFromHistoryIsUnavailable() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            val selected = tiles(listOf(rows[2], rows[5]))
            source.rows = rows - rows[5]
            source.removals++

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(tiles(listOf(rows[5])), unavailableSelectedPhotos(selected, current))
        }

    /** The read stops at the page that completes the selection instead of draining the history. */
    @Test
    fun theReadStopsOnceEverySelectedPhotoIsFound() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)

            pager.selectedPhotoResolver { ACCOUNT }(tiles(rows.take(3)))

            assertEquals(1, source.calls)
            assertAllHandlesClosed(source)
        }

    /** A removal between pages restarts the read from the head once, and every native handle is released. */
    @Test
    fun aRemovalDuringTheReadStartsOver() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            val selected = tiles(listOf(rows[20]))
            source.beforePage = {
                if (source.calls == 2) {
                    source.rows = rows - rows[0]
                    source.removals++
                }
            }

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(emptyList<SharedMediaTile>(), unavailableSelectedPhotos(selected, current))
            assertAllHandlesClosed(source)
        }

    /** A removal while the found photos are projected reads the history again, so the removed photo is reported. */
    @Test
    fun aRemovalWhileProjectingStartsOver() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            val selected = tiles(listOf(rows[2], rows[5]))
            // Version read 1 is the baseline and 2 the recheck after projection, so the removal lands mid-projection.
            source.beforeVersion = {
                if (source.versionCalls == 2) {
                    source.rows = rows - rows[5]
                    source.removals++
                }
            }

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(tiles(listOf(rows[5])), unavailableSelectedPhotos(selected, current))
            assertEquals(4, source.versionCalls)
            assertAllHandlesClosed(source)
        }

    /** A new photo between pages keeps the read going, since additions never remove a selected slot. */
    @Test
    fun anAdditionDuringTheReadDoesNotStartOver() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            val selected = tiles(listOf(rows[20]))
            source.beforePage = {
                if (source.calls == 2) {
                    source.rows = listOf(historyEntry(41)) + rows
                    source.additions++
                }
            }

            val current = pager.selectedPhotoResolver { ACCOUNT }(selected)

            assertEquals(emptyList<SharedMediaTile>(), unavailableSelectedPhotos(selected, current))
            assertEquals(2, source.calls)
        }

    /** A history that keeps changing fails the check instead of reporting present photos as removed. */
    @Test
    fun aHistoryThatKeepsChangingFailsTheCheck() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            source.beforePage = { source.removals++ }

            val resolve = pager.selectedPhotoResolver { ACCOUNT }
            val failure = runCatching { resolve(tiles(rows.take(1))) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertAllHandlesClosed(source)
        }

    /** A gallery that closed, for example on an account switch, cancels the read. */
    @Test
    fun aClosedGalleryCancelsTheRead() =
        runTest {
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            pager.close()
            val resolve = pager.selectedPhotoResolver { ACCOUNT }

            val failure = runCatching { resolve(tiles(rows.take(1))) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(0, source.calls)
        }

    /** The gallery tiles a user would have selected for [entries]. */
    private fun tiles(entries: List<AttachmentEntryFfi>) = attachmentLibraryTiles(entries, ACCOUNT).visuals

    /** Every cursor and version the fixture handed out was closed exactly once. */
    private fun assertAllHandlesClosed(source: GroupAttachmentFixture) {
        assertTrue(source.cursors.all { it.closes == 1 })
        assertTrue(source.versions.all { it.closes == 1 })
    }

    private companion object {
        const val ACCOUNT = "account"
    }
}
