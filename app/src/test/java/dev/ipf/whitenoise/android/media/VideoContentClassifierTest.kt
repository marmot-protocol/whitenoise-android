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
        // A snapshot that cannot be read was never checked, so it must be refused rather than kept.
        assertEquals(
            VideoContent.UnreadableContainer,
            VideoMetadataScrubber.classify(File(temporary.root, "missing.mp4")),
        )
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
                "Matroska video with an unknown DocType" to matroskaWithTrackType(1, docType = "matroskax"),
                "Matroska video with an overlong DocType" to matroskaWithTrackType(1, docType = "w".repeat(33)),
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

    /**
     * AVI, MPEG-PS, MXF and DV are video by their signature alone, and the same signature one byte short is not
     * recognised. RIFF WAVE audio is not AVI. FLV, Ogg, ASF and RealMedia need their stream headers, so a bare
     * signature is recognised but undetermined, and one byte short is not recognised at all.
     */
    @Test
    fun uncleanableContainersNeedTheirWholeSignature() {
        val video =
            mapOf(
                "AVI" to concat(ascii("RIFF"), beBytes(100, 4), ascii("AVI ")),
                "MPEG-PS" to bytes(0x00, 0x00, 0x01, 0xBA),
                "MXF" to bytes(0x06, 0x0E, 0x2B, 0x34),
                "DV" to bytes(0x1F, 0x07, 0x00, 0x3F),
                "DV with its variable bit set" to bytes(0x1F, 0x07, 0x00, 0xBF),
            )
        val undetermined =
            mapOf(
                "ASF" to AsfGuids.header,
                "FLV" to bytes('F'.code, 'L'.code, 'V'.code, 0x01),
                "Ogg" to ascii("OggS"),
                "RealMedia" to ascii(".RMF"),
            )
        val expected =
            video.mapValues { VideoContent.UnsupportedVideo } +
                undetermined.mapValues { VideoContent.UnsupportedUndetermined }
        (video + undetermined).forEach { (name, signature) ->
            assertEquals(name, expected.getValue(name), VideoMetadataScrubber.classify(signature))
            val short = signature.copyOf(signature.size - 1)
            assertEquals("$name one byte short", VideoContent.Other, VideoMetadataScrubber.classify(short))
        }
        val wave = concat(ascii("RIFF"), beBytes(100, 4), ascii("WAVE"))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(wave))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes('F'.code, 'L'.code, 'V'.code, 0x02)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(bytes(0x1F, 0x07, 0x01, 0x3F)))
    }

    /**
     * FLV: the video flag is a video. An audio-only flag is audio when the tags read hold no video tag and end with
     * the file, and a video tag behind it is still a video. No flag, a header one byte short, or a tag running past
     * the end is undetermined.
     */
    @Test
    fun flvFlagsAndTagsDecide() {
        val cases =
            mapOf(
                "video flag" to (flvFile(0x01, 9) to VideoContent.UnsupportedVideo),
                "audio and video flags" to (flvFile(0x05, 18, 8, 9) to VideoContent.UnsupportedVideo),
                "audio flag, audio tags" to (flvFile(0x04, 18, 8, 8) to VideoContent.UnsupportedAudio),
                "audio flag, a video tag" to (flvFile(0x04, 18, 8, 9) to VideoContent.UnsupportedVideo),
                "no stream flag" to (flvFile(0x00, 8) to VideoContent.UnsupportedUndetermined),
                "header one byte short" to (flvFile(0x04).copyOf(8) to VideoContent.UnsupportedUndetermined),
            )
        cases.forEach { (name, case) -> assertEquals(name, case.second, VideoMetadataScrubber.classify(case.first)) }
        val cutTag = flvFile(0x04, 8).let { it.copyOf(it.size - 1) }
        assertEquals(VideoContent.UnsupportedUndetermined, VideoMetadataScrubber.classify(cutTag))
    }

    /**
     * Ogg: the beginning-of-stream pages name every stream's codec. Vorbis, Opus, FLAC and Speex, with or without a
     * Skeleton, are audio. A Theora stream among them is a video. An unknown codec, a Skeleton alone, or a page one
     * byte short is undetermined, and a video codec name inside a later data page changes nothing.
     */
    @Test
    fun oggBeginningOfStreamPagesDecide() {
        val audio =
            mapOf(
                "Vorbis" to oggFile(OggPackets.vorbis),
                "Opus" to oggFile(OggPackets.opus),
                "FLAC" to oggFile(OggPackets.flac),
                "Speex" to oggFile(OggPackets.speex),
                "Skeleton and Vorbis" to oggFile(OggPackets.skeleton, OggPackets.vorbis),
                "Theora name in a data page" to
                    concat(oggFile(OggPackets.opus), oggPage(OggPackets.theora, beginsStream = false)),
            )
        audio.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnsupportedAudio, VideoMetadataScrubber.classify(bytes))
        }
        val video =
            mapOf(
                "Theora" to oggFile(OggPackets.theora),
                "Vorbis and Theora" to oggFile(OggPackets.vorbis, OggPackets.theora),
            )
        video.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnsupportedVideo, VideoMetadataScrubber.classify(bytes))
        }
        val firstPage = oggPage(OggPackets.vorbis, beginsStream = true)
        val undetermined =
            mapOf(
                "unknown codec" to oggFile(OggPackets.unknown),
                "Vorbis and an unknown codec" to oggFile(OggPackets.vorbis, OggPackets.unknown),
                "Skeleton alone" to oggFile(OggPackets.skeleton),
                "first page one byte short" to firstPage.copyOf(firstPage.size - 1),
            )
        undetermined.forEach { (name, bytes) ->
            assertEquals(name, VideoContent.UnsupportedUndetermined, VideoMetadataScrubber.classify(bytes))
        }
        assertEquals(VideoContent.UnsupportedAudio, VideoMetadataScrubber.classify(firstPage))
    }

    /**
     * ASF: Stream Properties Objects in the header, and any embedded in its Header Extension Object, declare audio or
     * video by GUID, stored with the first three fields little-endian. Audio streams alone, even beside a large
     * metadata object, are audio. Any video stream is a video. No stream, an unknown stream type, or a header one byte
     * short of its declared size is undetermined.
     */
    @Test
    fun asfStreamTypesDecide() {
        assertArrayEquals(AsfGuids.header, asfGuid("75B22630-668E-11CF-A6D9-00AA0062CE6C"))
        assertArrayEquals(AsfGuids.videoMedia, asfGuid("BC19EFC0-5B4D-11CF-A8FD-00805F5C442B"))
        assertArrayEquals(AsfGuids.audioMedia, asfGuid("F8699E40-5B4D-11CF-A8FD-00805F5C442B"))
        val audio = asfStream(AsfGuids.audioMedia)
        val video = asfStream(AsfGuids.videoMedia)
        val coverArt = asfObject(AsfGuids.contentDescription, ByteArray(200_000) { 0x55 })
        val audioOnly = asfFile(coverArt, audio)
        val cases =
            mapOf(
                "audio only" to (audioOnly to VideoContent.UnsupportedAudio),
                "audio and video" to (asfFile(audio, video) to VideoContent.UnsupportedVideo),
                "video embedded in the header extension" to
                    (asfFile(audio, asfExtension(ByteArray(64), video)) to VideoContent.UnsupportedVideo),
                "audio embedded in the header extension" to
                    (asfFile(audio, asfExtension(ByteArray(64), audio)) to VideoContent.UnsupportedAudio),
                "no stream" to (asfFile(coverArt) to VideoContent.UnsupportedUndetermined),
                "unknown stream type" to
                    (asfFile(audio, asfStream(ByteArray(16) { 0x66 })) to VideoContent.UnsupportedUndetermined),
                "header one byte short" to
                    (audioOnly.copyOf(audioOnly.size - 65) to VideoContent.UnsupportedUndetermined),
            )
        cases.forEach { (name, case) -> assertEquals(name, case.second, VideoMetadataScrubber.classify(case.first)) }
    }

    /**
     * RealMedia: each Media Properties chunk before the Data chunk names its stream's MIME type, and a `VIDO` tag in
     * the type-specific data marks video too. Audio streams, with or without file information, are audio. A video
     * stream is a video. An unknown type, or a header that ends before the Data chunk, is undetermined.
     */
    @Test
    fun realMediaStreamTypesDecide() {
        val vido = concat(beBytes(12, 4), ascii("VIDO"), ascii("RV40"))
        val audio = "audio/x-pn-realaudio"
        val unknown = "x-unknown/stream"
        val cases =
            mapOf(
                "audio" to (realMediaFile(listOf(audio)) to VideoContent.UnsupportedAudio),
                "file information and audio" to
                    (realMediaFile(listOf("logical-fileinfo", audio)) to VideoContent.UnsupportedAudio),
                "video" to (realMediaFile(listOf(audio, "video/x-pn-realvideo")) to VideoContent.UnsupportedVideo),
                "VIDO tag" to (realMediaFile(listOf(unknown), vido) to VideoContent.UnsupportedVideo),
                "unknown type" to (realMediaFile(listOf(unknown)) to VideoContent.UnsupportedUndetermined),
                "no Data chunk" to
                    (realMediaFile(listOf(audio), withData = false) to VideoContent.UnsupportedUndetermined),
            )
        cases.forEach { (name, case) -> assertEquals(name, case.second, VideoMetadataScrubber.classify(case.first)) }
    }

    /**
     * MPEG-TS needs a sync byte at 0, 188 and 376, and M2TS, whose 192-byte packets carry a 4-byte prefix, at 4, 196
     * and 388. One byte less, or one missing sync, is not a transport stream.
     */
    @Test
    fun transportStreamsNeedThreeSyncedPackets() {
        assertEquals(VideoContent.UnsupportedVideo, VideoMetadataScrubber.classify(synced(0, 188, 377)))
        assertEquals(VideoContent.Other, VideoMetadataScrubber.classify(synced(0, 188, 376)))
        assertEquals(VideoContent.UnsupportedVideo, VideoMetadataScrubber.classify(synced(4, 192, 389)))
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

    /**
     * The Files routing table: content decides first, and the reported type only gates the rows content leaves
     * uncertain or proves to be audio. Uncleanable video and undetermined streams are refused under every type.
     */
    @Test
    fun documentActionsFollowContentThenReportedType() {
        val expected =
            mapOf(
                VideoContent.VideoContainer to listOf(Scrub, Scrub, Scrub),
                VideoContent.UnconfirmedContainer to listOf(Scrub, Keep, Keep),
                VideoContent.UnsupportedVideo to listOf(Refuse, Refuse, Refuse),
                VideoContent.UnsupportedAudio to listOf(Refuse, Keep, Keep),
                VideoContent.UnsupportedUndetermined to listOf(Refuse, Refuse, Refuse),
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

    /** A minimal Matroska file with one TrackEntry of [trackType], declaring [docType]. */
    private fun matroskaWithTrackType(
        trackType: Int,
        docType: String = "webm",
    ): ByteArray {
        val entry = ebml(0xAEL, ebml(0xD7L, byteArrayOf(1)), ebml(0x83L, byteArrayOf(trackType.toByte())))
        return concat(ebmlHeader(docType), ebml(0x18538067L, ebml(0x1654AE6BL, entry)))
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
