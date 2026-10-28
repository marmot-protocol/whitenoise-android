package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.ui.conversation.media.DocumentVideoAction.Keep
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentVideoAction.Refuse
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentVideoAction.Scrub
import dev.ipf.whitenoise.android.ui.conversation.media.documentVideoAction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Content classification for Files picks (#3376): every routing row, the signatures of uncleanable video containers
 * and inputs one byte too short to match them. Classification runs the scrubber's own walk and never changes input.
 */
class VideoContentClassifierTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** Every committed fixture is a video container, and classifying it leaves its bytes untouched. */
    @Test
    fun fixturesAreVideoContainersAndStayUnchanged() {
        VIDEO_FIXTURES.forEach { name ->
            val bytes = videoFixture(name)
            val before = bytes.copyOf()
            assertEquals(name, VideoContent.VideoContainer, VideoMetadataScrubber.classify(bytes))
            assertArrayEquals(name, before, bytes)
        }
    }

    /** A read-only file is classified without being opened for writing or changed. */
    @Test
    fun readOnlyFilesAreClassifiedWithoutChange() {
        val bytes = videoFixture("mkv-tagged.mkv")
        val file = File(temporary.root, "clip.mkv").apply { writeBytes(bytes) }
        file.setWritable(false)

        assertEquals(VideoContent.VideoContainer, VideoMetadataScrubber.classify(file))

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(File(temporary.root, "missing.mp4")))
    }

    /** ISO BMFF is a video container only when a track's media handler is video, even if the scrub would refuse it. */
    @Test
    fun isoBmffNeedsAVideoTrack() {
        val audio = syntheticMovie(listOf(SyntheticTrack("soun", samples(listOf(8), 0x31)))).bytes
        assertEquals(VideoContent.ContainerWithoutVideo, VideoMetadataScrubber.classify(audio))

        val fragmentedWithTelemetry =
            syntheticMovie(
                listOf(SyntheticTrack("vide", emptyList()), SyntheticTrack("meta", emptyList())),
                movieExtras = listOf(isoBox("mvex", isoFullBox("trex", 0, 0, ByteArray(20)))),
                topLevelExtras = listOf(isoBox("moof", isoFullBox("mfhd", 0, 0, beBytes(1, 4))), isoBox("mdat")),
            ).bytes
        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(fragmentedWithTelemetry.copyOf()))
        assertEquals(VideoContent.VideoContainer, VideoMetadataScrubber.classify(fragmentedWithTelemetry))

        val textThatLooksLikeABox = ascii("The wide river ran past the old mill and on to the sea.\n")
        assertEquals(VideoContent.ContainerWithoutVideo, VideoMetadataScrubber.classify(textThatLooksLikeABox))
    }

    /** Matroska is a video container only when a TrackEntry's TrackType is video. */
    @Test
    fun matroskaNeedsAVideoTrack() {
        assertEquals(VideoContent.VideoContainer, VideoMetadataScrubber.classify(matroskaWithTrackType(1)))
        assertEquals(VideoContent.ContainerWithoutVideo, VideoMetadataScrubber.classify(matroskaWithTrackType(2)))
    }

    /** Each uncleanable video container is recognised, and the same signature one byte short is not. */
    @Test
    fun uncleanableContainersNeedTheirWholeSignature() {
        val signatures =
            mapOf(
                "AVI" to concat(ascii("RIFF"), beBytes(100, 4), ascii("AVI ")),
                "MPEG-PS" to bytes(0x00, 0x00, 0x01, 0xBA),
                "ASF" to
                    concat(
                        bytes(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11),
                        bytes(0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C),
                    ),
                "FLV" to bytes('F'.code, 'L'.code, 'V'.code, 0x01),
                "Ogg" to ascii("OggS"),
                "RealMedia" to ascii(".RMF"),
                "MXF" to bytes(0x06, 0x0E, 0x2B, 0x34),
            )
        signatures.forEach { (name, signature) ->
            assertEquals(name, VideoContent.UnsupportedContainer, VideoMetadataScrubber.classify(signature))
            val short = signature.copyOf(signature.size - 1)
            assertEquals("$name one byte short", VideoContent.Other, VideoMetadataScrubber.classify(short))
        }
        val wave = concat(ascii("RIFF"), beBytes(100, 4), ascii("WAVE"))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(wave))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes('F'.code, 'L'.code, 'V'.code, 0x02)))
    }

    /**
     * MPEG-TS needs a sync byte at 0, 188 and 376, and M2TS, whose 192-byte packets carry a 4-byte prefix, at 4, 196
     * and 388. One byte less, or one missing sync, is not a transport stream.
     */
    @Test
    fun transportStreamsNeedThreeSyncedPackets() {
        assertEquals(VideoContent.UnsupportedContainer, VideoMetadataScrubber.classify(synced(0, 188, 377)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(synced(0, 188, 376)))
        assertEquals(VideoContent.UnsupportedContainer, VideoMetadataScrubber.classify(synced(4, 192, 389)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(synced(4, 192, 388)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(synced(4, 188, 389)))
        val broken = synced(0, 188, 377).also { it[188] = 0 }
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(broken))
    }

    /** Text, empty input and random bytes are ordinary content. */
    @Test
    fun otherContentIsOther() {
        listOf(
            ascii("export const greeting: string = 'hello';\n"),
            ByteArray(0),
            ByteArray(3),
            ByteArray(512) { (it * 31 + 7).toByte() },
        ).forEach { bytes -> assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes)) }
    }

    /** The Files routing table: content decides first, and the reported type only gates the uncertain rows. */
    @Test
    fun documentActionsFollowContentThenReportedType() {
        val expected =
            mapOf(
                VideoContent.VideoContainer to listOf(Scrub, Scrub, Scrub),
                VideoContent.ContainerWithoutVideo to listOf(Scrub, Keep, Keep),
                VideoContent.UnsupportedContainer to listOf(Refuse, Keep, Keep),
                VideoContent.Other to listOf(Keep, Keep, Keep),
            )
        expected.forEach { (content, actions) ->
            listOf("video/mp2t", "audio/ogg", "application/octet-stream").zip(actions).forEach { (mime, action) ->
                assertEquals("$content reported $mime", action, documentVideoAction(content, mime))
            }
        }
        assertEquals(Scrub, documentVideoAction(VideoContent.ContainerWithoutVideo, "VIDEO/MP4"))
    }

    /** A minimal Matroska file with one TrackEntry of [trackType]. */
    private fun matroskaWithTrackType(trackType: Int): ByteArray {
        val entry = ebml(0xAEL, ebml(0xD7L, byteArrayOf(1)), ebml(0x83L, byteArrayOf(trackType.toByte())))
        return concat(ebmlHeader("webm"), ebml(0x18538067L, ebml(0x1654AE6BL, entry)))
    }

    /** [size] zero bytes with a sync byte at [first] and one and two [packet] lengths later, where they fit. */
    private fun synced(
        first: Int,
        packet: Int,
        size: Int,
    ): ByteArray =
        ByteArray(size).also { bytes ->
            (0 until 3).map { first + it * packet }.filter { it < size }.forEach { bytes[it] = 0x47 }
        }

    /** [values] as bytes. */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
