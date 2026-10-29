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
        assertEquals(VideoContent.UnconfirmedContainer, VideoMetadataScrubber.classify(audio))

        val fragmentedWithTelemetry =
            syntheticMovie(
                listOf(SyntheticTrack("vide", emptyList()), SyntheticTrack("meta", emptyList())),
                movieExtras = listOf(isoBox("mvex", isoFullBox("trex", 0, 0, ByteArray(20)))),
                topLevelExtras = listOf(isoBox("moof", isoFullBox("mfhd", 0, 0, beBytes(1, 4))), isoBox("mdat")),
            ).bytes
        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(fragmentedWithTelemetry.copyOf()))
        assertEquals(VideoContent.VideoContainer, VideoMetadataScrubber.classify(fragmentedWithTelemetry))
    }

    /**
     * Text whose bytes 4 to 7 happen to spell a box type is not a container at all, because its very first box does
     * not parse. The same holds for bytes that merely start with the EBML magic.
     */
    @Test
    fun inputWhoseFirstBoxDoesNotParseIsOther() {
        listOf(
            "The wide river ran past the old mill and on to the sea.\n",
            "let skipped = 0;\n",
            "/** free software, see LICENSE */\n",
            "abc moov is a word too\n",
        ).forEach { text -> assertEquals(text, VideoContent.Other, VideoMetadataScrubber.classify(ascii(text))) }
        val badEbml = concat(beBytes(0x1A45DFA3L, 4), byteArrayOf(0, 0x42, 0x86.toByte()))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(badEbml))
    }

    /**
     * A non-box trailer after the movie cannot hide its video track: the movie is walked before the trailer refuses
     * the file, so a tagged MP4 with a trailer is still a video, which the scrubber then refuses.
     */
    @Test
    fun aTrailerCannotHideTheVideoTrack() {
        listOf("mp4-tagged.mp4", "mp4-timecode.mp4").forEach { name ->
            val trailers = listOf(byteArrayOf(1), ascii("SEFH\u0001\u0000WNPrivateTrailerSEFT"))
            trailers.forEach { trailer ->
                val withTrailer = concat(videoFixture(name), trailer)
                assertEquals(name, VideoContent.VideoContainer, VideoMetadataScrubber.classify(withTrailer))
                assertEquals(name, VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(withTrailer.copyOf()))
            }
        }
    }

    /**
     * A recognised container whose walk is refused as malformed, or before it could read the track list, is
     * unreadable, never "without video": a video-handler movie of unknown brand with a trailer, a WebM refused before
     * its track list, a WebM whose second Segment is refused before its tracks and a compressed QuickTime movie. A
     * refusal after a complete track list without a video track is audio-only, so an audio-only movie with a trailer
     * or an ID3v1 tag and an audio-only WebM with stray bytes stay unconfirmed, as do a well-formed audio-only file and
     * a HEIC still without a movie. A still-image brand stays a document even when its walk is refused.
     */
    @Test
    fun refusedWalksAreUnreadableUnlessACompleteTrackListHadNoVideo() {
        val audioOnly = syntheticMovie(listOf(SyntheticTrack("soun", samples(listOf(8), 0x31)))).bytes
        val unknownBrandVideo =
            syntheticMovie(listOf(SyntheticTrack("vide", samples(listOf(8), 0x21))), brands = listOf("wxyz", "isom"))
        val compressed = syntheticMovie(emptyList(), movieExtras = listOf(isoBox("cmov", ByteArray(12)))).bytes
        val unreadable =
            mapOf(
                "unknown-brand video movie with a trailer" to concat(unknownBrandVideo.bytes, byteArrayOf(1)),
                "WebM refused before its tracks" to matroskaRefusedBeforeTracks(),
                "audio Segment, then a Segment refused before its tracks" to audioSegmentThenRefusedWebm(),
                "compressed movie" to compressed,
            )
        unreadable.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnreadableContainer, VideoMetadataScrubber.classify(bytes))
        }
        val unconfirmed =
            mapOf(
                "audio-only movie" to audioOnly,
                "audio-only movie with a trailer" to concat(audioOnly, byteArrayOf(1)),
                "m4a with an ID3v1 tag" to m4aWithId3v1Tag(),
                "audio-only WebM" to audioOnlyWebm(),
                "audio-only WebM with stray bytes" to audioOnlyWebmWithJunk(),
                "HEIC still" to heicShapedImage(),
                "CR3 with a trailer" to concat(cr3ShapedPhoto(), byteArrayOf(1)),
            )
        unconfirmed.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnconfirmedContainer, VideoMetadataScrubber.classify(bytes))
        }
        listOf(m4aWithId3v1Tag(), audioOnlyWebmWithJunk()).forEach { bytes ->
            assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(bytes.copyOf()))
        }
    }

    /**
     * Content proves a video only under a video brand. A Canon CR3 raw photo, an AVIF sequence and a HEIC still are
     * unconfirmed even with a video-handler track, and so is a video-handler file whose brand is unknown or whose
     * compatible brands name a still image. Old QuickTime without `ftyp`, and 3GPP, are videos.
     */
    @Test
    fun onlyVideoBrandsLetContentProveAVideo() {
        val video = listOf(SyntheticTrack("vide", samples(listOf(8), 0x21)))
        val unconfirmed =
            mapOf(
                "CR3" to cr3ShapedPhoto(),
                "AVIF sequence" to avifSequenceShaped(),
                "HEIC" to heicShapedImage(),
                "unknown brand" to syntheticMovie(video, brands = listOf("wxyz", "isom")).bytes,
                "still compatible brand" to syntheticMovie(video, brands = listOf("isom", "isom", "mif1")).bytes,
            )
        unconfirmed.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnconfirmedContainer, VideoMetadataScrubber.classify(bytes))
        }
        val proven =
            mapOf(
                "QuickTime without ftyp" to syntheticMovie(video, brands = emptyList()).bytes,
                "3GPP" to syntheticMovie(video, brands = listOf("3gp5", "3gp5", "isom")).bytes,
                "QuickTime" to syntheticMovie(video, brands = listOf("qt  ", "qt  ")).bytes,
                "MP4" to syntheticMovie(video, brands = listOf("mp42", "mp42", "isom", "dby1")).bytes,
            )
        proven.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.VideoContainer, VideoMetadataScrubber.classify(bytes))
        }
    }

    /** Matroska is a video container only when a TrackEntry's TrackType is video. */
    @Test
    fun matroskaNeedsAVideoTrack() {
        assertEquals(VideoContent.VideoContainer, VideoMetadataScrubber.classify(matroskaWithTrackType(1)))
        assertEquals(VideoContent.UnconfirmedContainer, VideoMetadataScrubber.classify(matroskaWithTrackType(2)))
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
                "DV" to bytes(0x1F, 0x07, 0x00, 0x3F),
                "DV with its variable bit set" to bytes(0x1F, 0x07, 0x00, 0xBF),
            )
        signatures.forEach { (name, signature) ->
            assertEquals(name, VideoContent.UnsupportedContainer, VideoMetadataScrubber.classify(signature))
            val short = signature.copyOf(signature.size - 1)
            assertEquals("$name one byte short", VideoContent.Other, VideoMetadataScrubber.classify(short))
        }
        val wave = concat(ascii("RIFF"), beBytes(100, 4), ascii("WAVE"))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(wave))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes('F'.code, 'L'.code, 'V'.code, 0x02)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes(0x1F, 0x07, 0x01, 0x3F)))
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
                VideoContent.UnconfirmedContainer to listOf(Scrub, Keep, Keep),
                VideoContent.UnsupportedContainer to listOf(Refuse, Keep, Keep),
                VideoContent.UnreadableContainer to listOf(Refuse, Refuse, Refuse),
                VideoContent.Other to listOf(Keep, Keep, Keep),
            )
        expected.forEach { (content, actions) ->
            listOf("video/mp2t", "audio/ogg", "application/octet-stream").zip(actions).forEach { (mime, action) ->
                assertEquals("$content reported $mime", action, documentVideoAction(content, mime))
            }
        }
        assertEquals(Scrub, documentVideoAction(VideoContent.UnconfirmedContainer, "VIDEO/MP4"))
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
