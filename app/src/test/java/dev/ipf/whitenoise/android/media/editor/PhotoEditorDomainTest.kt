package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Which worker domain each photo-editor decode names (#3373). Every decode of a pick, its inspection, the editor's
 * preview and its unedited or edited render, runs in its conversation's shared outgoing worker, which never sees
 * another conversation's picks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorDomainTest {
    private val seen = Collections.synchronizedList(mutableListOf<Pair<MediaTrustDomain, Int>>())

    /** Installs a codec that records each request's declared domain and source size, then decodes in-process. */
    @Before
    fun recordDomains() {
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun inspect(bytes: ByteArray): ImageInspection? {
                    seen += MediaTrustScope.current() to bytes.size
                    return TestMediaDecodeTransport.inspect(bytes)
                }

                override fun decode(
                    bytes: ByteArray,
                    edge: Int,
                    oriented: Boolean,
                    operation: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current() to bytes.size
                    return TestMediaDecodeTransport.decode(bytes, edge, oriented, operation)
                }

                override fun region(
                    bytes: ByteArray,
                    region: MediaImageRegion,
                    edge: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current() to bytes.size
                    return TestMediaDecodeTransport.region(bytes, region, edge)
                }
            },
        )
    }

    /** Restores the shared in-process codec for the next test. */
    @After
    fun restoreCodec() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    /**
     * Inspection, the editor preview and an unedited or edited render of a pick all run in its conversation's shared
     * outgoing domain, the one the draft names.
     */
    @Test
    fun everyEditorDecodeOfAPickRunsInItsConversationsSharedWorker() =
        runTest {
            val renderer = renderer()
            val source = source(EDGE_A)
            renderer.inspect(source, PICK_A)
            renderer.decodePreview(source, PICK_A)?.recycle()
            renderer.render(source, PhotoEditRecipe.Original, MediaQuality.High, PICK_A)
            val cropped = PhotoEditRecipe(crop = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f))
            renderer.render(source, cropped, MediaQuality.High, PICK_A)
            renderer.render(source, PhotoEditRecipe(quarterTurnsClockwise = 1), MediaQuality.High, PICK_A)
            assertTrue("the codec saw no request", seen.isNotEmpty())
            assertEquals(setOf(PICK_A), seen.map { it.first }.toSet())
        }

    /**
     * Editing a pick of one conversation while a pick of another renders never puts either source in the other
     * conversation's outgoing worker.
     */
    @Test
    fun editingInOneConversationNeverReachesAnotherConversationsWorker() =
        runTest {
            val renderer = renderer()
            val first = source(EDGE_A)
            val second = source(EDGE_B)
            coroutineScope {
                repeat(ROUNDS) {
                    launch(Dispatchers.Default) {
                        renderer.render(first, PhotoEditRecipe(quarterTurnsClockwise = 1), MediaQuality.High, PICK_A)
                        renderer.decodePreview(first, PICK_A)?.recycle()
                    }
                    launch(Dispatchers.Default) {
                        renderer.render(second, PhotoEditRecipe.Original, MediaQuality.High, PICK_B)
                        renderer.decodePreview(second, PICK_B)?.recycle()
                    }
                }
            }
            val byDomain = seen.toList().groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
            assertEquals(setOf(PICK_A, PICK_B), byDomain.keys)
            assertTrue("A's source reached B's worker", first.size !in byDomain.getValue(PICK_B))
            assertTrue("B's source reached A's worker", second.size !in byDomain.getValue(PICK_A))
        }

    /** A renderer that encodes with an ample memory budget. */
    private fun renderer() =
        PhotoEditorRenderer(
            renderDispatcher = UnconfinedTestDispatcher(),
            memoryBudgetBytes = { 256L * 1024L * 1024L },
        )

    /** A small opaque PNG of [edge] pixels the in-process codec can inspect and decode, identified by its size. */
    private fun source(edge: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        /** A pick's domain in conversation A, the conversation's shared outgoing worker. */
        val PICK_A = MediaTrustDomain.outgoing("account", "conversation-a")

        /** A pick's domain in conversation B. */
        val PICK_B = MediaTrustDomain.outgoing("account", "conversation-b")
        const val EDGE_A = 64
        const val EDGE_B = 96
        const val ROUNDS = 4
    }
}
