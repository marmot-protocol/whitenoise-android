package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.media.MediaPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Device proof that a queued video's poster is really extracted from its retained bytes (#2732).
 *
 * The JVM cases for this path can only reach the fallback: Robolectric's `MediaMetadataRetriever`
 * shadow decodes nothing, so they feed it bytes that are deliberately undecodable and assert the
 * empty frame. That leaves the branch the feature actually exists for — a real retriever pulling a
 * real frame out of an in-memory [android.media.MediaDataSource] — unexercised. These cases run it
 * against a real codec on a real device, with a real MP4 fixture, and check that what comes back is
 * a usable poster: present, the right shape, and inside the bubble's thumbnail bound.
 */
@RunWith(AndroidJUnit4::class)
class PendingVideoPosterFrameAndroidTest {
    private lateinit var fixtureBytes: ByteArray

    /** Loads and hash-verifies the shared MP4 fixture before each case. */
    @Before
    fun readFixture() {
        fixtureBytes = readVerifiedVideoFixture()
    }

    /** Retained video bytes yield a real poster frame, bounded by the bubble's thumbnail edge. */
    @Test
    fun retainedVideoBytesDecodeARealPosterFrame() {
        val frame = pendingVideoPosterFrame(fixtureBytes, extractPoster = true)

        val poster = checkNotNull(frame.bitmap) { "a valid MP4 must produce a poster frame" }
        assertTrue("poster must have pixels", poster.width > 0 && poster.height > 0)
        assertTrue(
            "poster long edge ${maxOf(poster.width, poster.height)} exceeds the thumbnail bound",
            maxOf(poster.width, poster.height) <= MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
        )
        assertTrue(
            "poster ${poster.width}x${poster.height} does not keep the fixture's aspect ratio",
            abs(poster.width.toFloat() / poster.height - FIXTURE_ASPECT_RATIO) <= ASPECT_RATIO_TOLERANCE,
        )
        assertTrue("the fixture's duration must be read from the same bytes", frame.durationMs >= MIN_DURATION_MS)
        poster.recycle()
    }

    /** A smaller requested edge really binds, so a 4K clip cannot hold a full-size bitmap per bubble. */
    @Test
    fun posterExtractionScalesDownToTheRequestedEdge() {
        val frame = pendingVideoPosterFrame(fixtureBytes, extractPoster = true, maxEdgePx = SMALL_EDGE_PX)

        val poster = checkNotNull(frame.bitmap) { "a bounded request must still produce a poster" }
        assertTrue("poster must have pixels", poster.width > 0 && poster.height > 0)
        assertTrue(
            "poster ${poster.width}x${poster.height} ignored the $SMALL_EDGE_PX px bound",
            maxOf(poster.width, poster.height) <= SMALL_EDGE_PX,
        )
        poster.recycle()
    }

    /** Duration is still read when a caller already holds a poster and asks for no frame. */
    @Test
    fun durationIsReadWithoutExtractingAFrame() {
        val frame = pendingVideoPosterFrame(fixtureBytes, extractPoster = false)

        assertNull("no frame may be decoded when none was asked for", frame.bitmap)
        assertTrue("duration must still come back", frame.durationMs >= MIN_DURATION_MS)
    }

    /** A real retriever rejecting undecodable bytes is an empty frame, not a thrown failure. */
    @Test
    fun undecodableBytesFallBackToAnEmptyFrame() {
        val frame = pendingVideoPosterFrame(fixtureBytes.copyOf(TRUNCATED_FIXTURE_BYTES), extractPoster = true)

        assertNull("a truncated MP4 has no poster to show", frame.bitmap)
        assertEquals(0L, frame.durationMs)
    }

    /** Decodes and hash-verifies AndroidX Media's Apache-2.0 video fixture. */
    private fun readVerifiedVideoFixture(): ByteArray {
        val encoded =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open(VIDEO_FIXTURE_ASSET)
                .bufferedReader()
                .use { it.readText() }
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        assertEquals(VIDEO_FIXTURE_SHA256, decoded.sha256Hex())
        return decoded
    }

    /** Computes the lowercase SHA-256 used to reject corrupted or replaced fixture bytes. */
    private fun ByteArray.sha256Hex(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
            }

    private companion object {
        const val VIDEO_FIXTURE_ASSET = "silent-black-10s.mp4.b64"
        const val VIDEO_FIXTURE_SHA256 = "83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51"

        /** The fixture's `tkhd`/`stsd` boxes both give 320x240, so its frames are 4:3. */
        const val FIXTURE_ASPECT_RATIO = 320f / 240f
        const val ASPECT_RATIO_TOLERANCE = 0.05f
        const val MIN_DURATION_MS = 9_000L

        /** Below the fixture's own 320px width, so the bound has to do real work. */
        const val SMALL_EDGE_PX = 96

        /** Enough for the ftyp box and nothing a codec can open. */
        const val TRUNCATED_FIXTURE_BYTES = 32
    }
}
