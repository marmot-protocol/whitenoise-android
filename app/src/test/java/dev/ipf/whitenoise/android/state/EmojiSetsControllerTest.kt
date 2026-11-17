package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EmojiSetEmojiFfi
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.marmotkit.EmojiSetInstallProgressFfi
import dev.ipf.marmotkit.EmojiSetInstallResultFfi
import dev.ipf.marmotkit.EmojiSkipFfi
import dev.ipf.marmotkit.EmojiSkipReasonFfi
import dev.ipf.marmotkit.InstalledCustomEmojiFfi
import dev.ipf.marmotkit.InstalledEmojiSetStateFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiSetsControllerTest {
    /** A cancelled update reaches the install handle, clears progress, and keeps the previous installed snapshot. */
    @Test
    fun cancelWhileProgressIsVisible() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            val previous = InstalledEmojiSetStateFfi("previous", 0u, 0u, 1u, 100u, true)
            backend.set = backend.set.copy(installed = previous)
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)
            val job = launch { controller.install() }

            runCurrent()
            assertEquals(0u, controller.state.progress?.completedEmoji)
            backend.install.report = EmojiSetInstallProgressFfi(1u, 2u, 100u)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1u, controller.state.progress?.completedEmoji)

            controller.cancelInstall()
            assertTrue(controller.state.cancelling)
            job.join()

            assertEquals(EmojiSetNotice.Cancelled, controller.state.notice)
            assertNull(controller.state.progress)
            assertEquals(previous, controller.state.preview?.installed)
            assertTrue(backend.install.closed)
            assertEquals(0, changes)
        }

    /** Closing during the native metadata read retires the request before it creates an install handle. */
    @Test
    fun closeDuringMetadataReadPreventsInstallAdmission() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            val metadataReady = CompletableDeferred<Unit>()
            backend.beforeCurrent = { metadataReady.await() }
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)
            val job = launch { controller.install() }
            runCurrent()
            assertTrue(controller.state.busy)
            assertEquals(0, backend.newInstallCalls)

            controller.close()
            metadataReady.complete(Unit)
            job.join()

            assertEquals(0, backend.newInstallCalls)
            assertTrue(backend.install.attempts.isEmpty())
            assertFalse(controller.state.busy)
            assertNull(controller.state.progress)
            assertEquals(0, changes)
        }

    /** A close during handle creation cancels and releases that handle before its native run starts. */
    @Test
    fun closeDuringHandleCreationCancelsBeforeRun() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            backend.beforeNewInstall = controller::close
            controller.show(backend.set)

            controller.install()

            assertEquals(1, backend.newInstallCalls)
            assertEquals(1, backend.install.cancelCalls)
            assertTrue(backend.install.closed)
            assertTrue(backend.install.attempts.isEmpty())
            assertEquals(EmojiSetNotice.Cancelled, controller.state.notice)
            assertFalse(controller.state.busy)
            assertNull(controller.state.progress)
            assertEquals(0, changes)
        }

    /** A changed set reopens the new version and never starts an install without another decision. */
    @Test
    fun newVersionRequiresAnotherInstallTap() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)
            val newVersion = backend.set.copy(eventIdHex = "new")
            backend.set = newVersion
            backend.install.result.completeExceptionally(MarmotKitException.EmojiSetChanged("new"))

            controller.install()

            assertTrue(backend.install.attempts.isEmpty())
            assertEquals(newVersion, controller.state.preview)
            assertEquals(EmojiSetNotice.Changed, controller.state.notice)
            assertFalse(controller.state.busy)
            assertEquals(0, changes)
        }

    /** The picker is invalidated after install and after removal of the installed snapshot. */
    @Test
    fun installAndRemoveRefreshPickerAndBrowser() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)
            val installed = backend.set.copy(installed = InstalledEmojiSetStateFfi("old", 0u, 0u, 1u, 100u, false))
            backend.install.result.complete(EmojiSetInstallResultFfi(installed, 1u, emptyList()))

            controller.install()
            assertEquals(installed, controller.state.preview)
            assertEquals(1, changes)

            controller.remove()
            assertNull(controller.state.preview?.installed)
            assertEquals(listOf(backend.set), controller.state.sets)
            assertEquals(2, changes)
        }

    /** A first install can still publish its available artwork and report the remaining failed downloads. */
    @Test
    fun partialFirstInstallReportsSkipsAndRefreshesThePicker() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            backend.set =
                backend.set.copy(
                    emoji =
                        backend.set.emoji +
                            backend.set.emoji
                                .first()
                                .copy(shortcode = "forest-bear"),
                )
            val partial =
                backend.set.copy(installed = InstalledEmojiSetStateFfi(backend.set.eventIdHex, 0u, 0u, 1u, 100u, false))
            backend.install.result.complete(
                EmojiSetInstallResultFfi(
                    partial,
                    1u,
                    listOf(EmojiSkipFfi(null, "forest-bear", EmojiSkipReasonFfi.DOWNLOAD_FAILED)),
                ),
            )
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)

            controller.install()

            assertEquals(listOf(backend.set.eventIdHex), backend.install.attempts)
            assertEquals(partial, controller.state.preview)
            assertEquals(1, controller.state.installedWithSkips)
            assertEquals(1, changes)
        }

    /** A stale Install action preserves current partial and complete snapshots, including stale update flags. */
    @Test
    fun sameVersionInstallPreservesPartialAndCompleteSnapshots() =
        runTest {
            val set =
                emojiSetFixture().let {
                    it.copy(emoji = it.emoji + it.emoji.first().copy(shortcode = "forest-bear"))
                }
            for (count in listOf(1u, 2u)) {
                for (updateAvailable in listOf(false, true)) {
                    val backend = FakeEmojiSetsBackend()
                    val installed = InstalledEmojiSetStateFfi(set.eventIdHex, 0u, 0u, count, 100u, updateAvailable)
                    val current = set.copy(installed = installed)
                    backend.set = current
                    backend.install.result.complete(EmojiSetInstallResultFfi(current, count, emptyList()))
                    var changes = 0
                    val controller = EmojiSetsController(backend) { changes++ }
                    controller.show(set)

                    controller.install()

                    assertTrue(backend.install.attempts.isEmpty())
                    assertEquals(current, controller.state.preview)
                    assertEquals(0, backend.removals)
                    assertEquals(0, changes)
                    assertNull(controller.state.progress)
                    assertFalse(controller.state.busy)
                }
            }
        }

    /** An installed older version can still be updated to a different advertised event. */
    @Test
    fun newerAvailableVersionInstallsAndRefreshesThePicker() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            val previous = InstalledEmojiSetStateFfi("previous", 0u, 0u, 1u, 100u, true)
            backend.set = backend.set.copy(installed = previous)
            val updated =
                backend.set.copy(
                    installed = previous.copy(eventIdHex = backend.set.eventIdHex, updateAvailable = false),
                )
            backend.install.result.complete(EmojiSetInstallResultFfi(updated, 1u, emptyList()))
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(backend.set)

            controller.install()

            assertEquals(listOf(backend.set.eventIdHex), backend.install.attempts)
            assertEquals(updated, controller.state.preview)
            assertEquals(1, changes)
            assertEquals(0, backend.removals)
        }

    /** Set collisions are deterministic and built-ins cannot be shadowed by relay-provided entries. */
    @Test
    fun installedCodesMatchSendableTagAlphabetAndPrecedence() {
        val first = installedEmojiFixture("ship-it", "first")
        val second = installedEmojiFixture("ship-it", "second")
        val long = installedEmojiFixture("a".repeat(64), "first")
        val builtin = installedEmojiFixture("marmot", "first")

        assertEquals(
            mapOf(":ship-it:" to first, ":${long.shortcode}:" to long),
            installedEmojiByCode(listOf(first, second, long, builtin)),
        )
    }

    /** A completed current version cannot be replaced by a partial result, even from a stale preview. */
    @Test
    fun stalePartialPreviewCannotReinstallACompletedSet() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            val set =
                backend.set.copy(
                    emoji =
                        backend.set.emoji +
                            backend.set.emoji
                                .first()
                                .copy(shortcode = "forest-bear"),
                )
            val partial = set.copy(installed = InstalledEmojiSetStateFfi("old", 0u, 0u, 1u, 100u, false))
            val complete = partial.copy(installed = partial.installed!!.copy(emojiCount = 2u))
            var changes = 0
            val controller = EmojiSetsController(backend) { changes++ }
            controller.show(partial)
            backend.set = complete
            backend.install.result.complete(EmojiSetInstallResultFfi(partial, 1u, emptyList()))

            controller.install()

            assertTrue(backend.install.attempts.isEmpty())
            assertEquals(complete, controller.state.preview)
            assertEquals(0, changes)
        }

    /** The opening sync leaves the rows usable, holds back Find and lands its reread when the relays answer. */
    @Test
    fun backgroundSyncKeepsRowsUsable() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            val relays = CompletableDeferred<Boolean>()
            backend.onDiscover = { relays.await() }
            val controller = EmojiSetsController(backend) {}

            val sync = async { controller.discoverInBackground() }
            runCurrent()
            assertTrue(controller.state.discovering)
            assertFalse(controller.state.busy)
            controller.discover("forest")
            assertEquals(1, backend.discoverCalls)
            controller.show(backend.set)
            assertEquals(backend.set, controller.state.preview)

            relays.complete(true)
            assertTrue(sync.await())
            assertFalse(controller.state.discovering)
            assertEquals(listOf(backend.set), controller.state.sets)
        }

    /** An incomplete or failed opening sync reports itself only when no preview is open. */
    @Test
    fun backgroundSyncNoticeNeverCoversAPreview() =
        runTest {
            val outcomes: List<suspend () -> Boolean> = listOf({ false }, { error("relay failure") })
            val notices = listOf(EmojiSetNotice.DiscoveryIncomplete, EmojiSetNotice.Error)
            outcomes.zip(notices).forEach { (outcome, notice) ->
                listOf(false, true).forEach { previewOpen ->
                    val backend = FakeEmojiSetsBackend()
                    backend.onDiscover = outcome
                    val controller = EmojiSetsController(backend) {}
                    if (previewOpen) controller.show(backend.set)

                    assertFalse(controller.discoverInBackground())
                    assertEquals(if (previewOpen) null else notice, controller.state.notice)
                    assertFalse(controller.state.discovering)
                }
            }
        }

    /** A slow opening reread cannot replace the list a later removal already reread. */
    @Test
    fun staleSyncReadCannotOverwriteANewerList() =
        runTest {
            val backend = FakeEmojiSetsBackend()
            backend.set = backend.set.copy(installed = InstalledEmojiSetStateFfi("old", 0u, 0u, 1u, 100u, false))
            val staleRead = CompletableDeferred<Unit>()
            backend.beforeSets = { staleRead.await() }
            val controller = EmojiSetsController(backend) {}
            val sync = launch { controller.discoverInBackground() }
            runCurrent()

            backend.beforeSets = {}
            controller.show(backend.set)
            controller.remove()
            staleRead.complete(Unit)
            sync.join()

            assertEquals(listOf(backend.set), controller.state.sets)
            assertNull(controller.state.sets.single().installed)
        }
}

private class FakeEmojiSetsBackend : EmojiSetsBackend {
    var set = emojiSetFixture()
    val install = FakeEmojiSetInstall()
    var removals = 0
    var newInstallCalls = 0
    var beforeCurrent: suspend () -> Unit = {}
    var beforeNewInstall: () -> Unit = {}

    var beforeSets: suspend () -> Unit = {}
    var onDiscover: suspend () -> Boolean = { true }
    var discoverCalls = 0

    /** Snapshots the projection before [beforeSets], so a held read returns what it saw when it started. */
    override suspend fun sets(search: String?): List<EmojiSetFfi> {
        val snapshot = listOf(set)
        beforeSets()
        return snapshot
    }

    /** Counts relay syncs; [onDiscover] can hold or fail one. */
    override suspend fun discover(search: String?): Boolean {
        discoverCalls++
        return onDiscover()
    }

    override suspend fun fetch(input: String) = set

    /** Allows the metadata read to remain suspended while the screen closes. */
    override suspend fun current(coordinate: String): EmojiSetFfi {
        beforeCurrent()
        return set
    }

    override suspend fun preview(set: EmojiSetFfi) = byteArrayOf()

    /** Exercises disposal before the new handle is attached to its controller. */
    override fun newInstall(): EmojiSetInstall {
        newInstallCalls++
        beforeNewInstall()
        return install
    }

    override suspend fun remove(coordinate: String) {
        removals++
        set = set.copy(installed = null)
    }
}

private class FakeEmojiSetInstall : EmojiSetInstall {
    var report = EmojiSetInstallProgressFfi(0u, 2u, 0u)
    val attempts = mutableListOf<String>()
    val result = CompletableDeferred<EmojiSetInstallResultFfi>()
    var closed = false
    var cancelCalls = 0

    override suspend fun run(set: EmojiSetFfi): EmojiSetInstallResultFfi {
        attempts += set.eventIdHex
        return result.await()
    }

    override fun progress() = report

    /** Records native cancellation and releases any pending run with its typed outcome. */
    override fun cancel() {
        cancelCalls++
        result.completeExceptionally(MarmotKitException.EmojiSetInstallCancelled())
    }

    override fun close() {
        closed = true
    }
}

internal fun emojiSetFixture() =
    EmojiSetFfi(
        coordinate = "30030:${"1".repeat(64)}:forest",
        kind = 30030u,
        authorPubkeyHex = "1".repeat(64),
        identifier = "forest",
        eventIdHex = "old",
        createdAt = 0u,
        title = "Forest friends",
        description = "A few small creatures for your messages.",
        imageUrl = "https://emoji.example/cover.png",
        emoji = listOf(EmojiSetEmojiFfi("forest-fox", "https://emoji.example/fox.png")),
        skipped = emptyList(),
        skippedCount = 0u,
        installed = null,
    )

internal fun installedEmojiFixture(
    code: String,
    set: String,
) = InstalledCustomEmojiFfi(
    setCoordinate = set,
    setTitle = "Forest friends",
    authorPubkeyHex = "1".repeat(64),
    shortcode = code,
    mime = "image/png",
    width = 32u,
    height = 32u,
    frames = 1u,
    sha256 = "a".repeat(64),
    byteLen = 100u,
    shortcodeCollision = false,
)
