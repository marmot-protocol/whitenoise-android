package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.marmotkit.EmojiSetEmojiFfi
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.marmotkit.InstalledEmojiSetStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.emojiSetFixture
import dev.ipf.whitenoise.android.ui.EmojiArt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consent, routing, cancellation, retry and memory bounds of the set preview's transient images. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmojiSetImagesTest {
    @Test
    fun remoteSetLoadsNothingUntilRequested() =
        runTest {
            val source = RecordingSource()
            val images = EmojiSetImages(set(3), source, art)
            images.load("e0")
            images.loadCover()
            assertEquals("No host is contacted before consent", emptyList<String>(), source.calls)
            images.request()
            images.load("e0")
            images.loadCover()
            assertTrue(images["e0"] is EmojiSetImage.Ready)
            assertEquals(listOf("remote:e0", "cover"), source.calls)
            images.load("e0")
            assertEquals("A loaded tile is not fetched again", 2, source.calls.size)
        }

    @Test
    fun exactInstalledVersionReadsLocallyWithoutCover() =
        runTest {
            val base = set(2)
            val source = RecordingSource()
            val images =
                EmojiSetImages(base.copy(installed = installed(base.eventIdHex, update = false)), source, art)
            assertTrue("The installed version needs no consent", images.requested)
            images.load("e1")
            images.loadCover()
            assertEquals(listOf("local:e1"), source.calls)
        }

    @Test
    fun updateAvailableStaysRemoteAndAsksAgain() {
        val base = set(2)
        val images = EmojiSetImages(base.copy(installed = installed("older", update = true)), RecordingSource(), art)
        assertFalse(images.local)
        assertFalse(images.requested)
    }

    @Test
    fun cancelledLoadClearsTheTileAndStartsANewAttempt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = RecordingSource(hold = gate)
            val images = EmojiSetImages(set(1), source, art).apply { request() }
            val job = launch { images.load("e0") }
            runCurrent()
            assertEquals(EmojiSetImage.Loading, images["e0"])
            assertTrue(images.hasLoadingTiles)
            job.cancel()
            runCurrent()
            assertNull("A tile that scrolled away starts over", images["e0"])
            assertEquals("Its effect restarts if it is back on screen", 1, images.attempt("e0"))
            assertFalse(images.hasLoadingTiles)
        }

    @Test
    fun failedRemoteTileRetriesButLocalOneDoesNot() =
        runTest {
            val remote = EmojiSetImages(set(1), RecordingSource(failing = true), art).apply { request() }
            remote.load("e0")
            assertEquals(EmojiSetImage.Failed, remote["e0"])
            remote.retry("e0")
            assertNull(remote["e0"])
            assertEquals(1, remote.attempt("e0"))

            val base = set(1)
            val local =
                EmojiSetImages(
                    base.copy(installed = installed(base.eventIdHex, update = false)),
                    RecordingSource(failing = true),
                    art,
                )
            local.load("e0")
            local.retry("e0")
            assertEquals("Stored bytes will not change, so there is no retry", EmojiSetImage.Failed, local["e0"])
        }

    @Test
    fun atMostFourLoadsRunAtOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = RecordingSource(hold = gate)
            val images = EmojiSetImages(set(8), source, art).apply { request() }
            repeat(8) { launch { images.load("e$it") } }
            runCurrent()
            assertEquals(4, source.calls.size)
            gate.complete(Unit)
            runCurrent()
            assertEquals(8, source.calls.size)
        }

    @Test
    fun decodedImagesAreBounded() =
        runTest {
            val images = EmojiSetImages(set(200), RecordingSource(), art).apply { request() }
            repeat(200) { images.load("e$it") }
            val ready = (0 until 200).count { images["e$it"] is EmojiSetImage.Ready }
            assertEquals(160, ready)
            assertNull("The oldest image is dropped first", images["e0"])
            assertEquals("Eviction does not restart an effect", 0, images.attempt("e0"))
            images.load("e0")
            assertTrue(images["e0"] is EmojiSetImage.Ready)
        }

    @Test
    fun cacheKeepsAllTilesThatAreStillAttached() =
        runTest {
            val images = EmojiSetImages(set(161), RecordingSource(), art).apply { request() }
            repeat(161) {
                images.attach("e$it")
                images.load("e$it")
            }
            assertEquals(161, (0 until 161).count { images["e$it"] is EmojiSetImage.Ready })
            images.detach("e0")
            assertNull(images["e0"])
            assertEquals(0, images.attempt("e0"))
        }

    @Test
    fun machineIdentifiersNeverReadAsTitles() {
        listOf(
            "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "0xdeadbeefcafe1234",
            "123e4567-e89b-12d3-a456-426614174000",
            "1712345678",
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "   ",
        ).forEach { assertFalse(it, it.isReadableIdentifier()) }
        listOf("forest", "cats-and-dogs", "Blobs 2024", "my_reactions", "pepe").forEach {
            assertTrue(it, it.isReadableIdentifier())
        }
    }

    private class RecordingSource(
        private val failing: Boolean = false,
        private val hold: CompletableDeferred<Unit>? = null,
    ) : EmojiSetImageSource {
        val calls = mutableListOf<String>()

        override suspend fun cover(set: EmojiSetFfi): ByteArray {
            calls += "cover"
            return byteArrayOf(0)
        }

        override suspend fun emoji(
            set: EmojiSetFfi,
            shortcode: String,
            local: Boolean,
        ): ByteArray {
            calls += (if (local) "local:" else "remote:") + shortcode
            hold?.await()
            check(!failing) { "unavailable" }
            return byteArrayOf(0)
        }
    }
}

private val art: suspend (ByteArray) -> EmojiArt? = { EmojiArt.Bundled(R.drawable.ic_category) }

private fun set(count: Int) =
    emojiSetFixture().copy(
        eventIdHex = "current",
        emoji = List(count) { EmojiSetEmojiFfi("e$it", "https://emoji.example/e$it.png") },
    )

private fun installed(
    eventIdHex: String,
    update: Boolean,
) = InstalledEmojiSetStateFfi(eventIdHex, 0u, 0u, 1u, 100u, update)
