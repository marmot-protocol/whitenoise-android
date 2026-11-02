package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic ISO BMFF structures for #3376: every sample-table form, largesize and to-the-end boxes, fragments,
 * external references and malformed sizes. Each case states the result and what changed, and never throws.
 */
class IsoBmffMetadataScrubberTest {
    /** Timed-metadata samples are zeroed through every size and offset table form, and media samples stay. */
    @Test
    fun timedMetadataSamplesAreZeroedThroughEverySampleTableForm() {
        val forms =
            listOf(
                SyntheticTrack("meta", samples(listOf(5, 9, 3), 0x51), SizeTable.Variable),
                SyntheticTrack("meta", samples(listOf(6, 6, 6), 0x51), SizeTable.Constant),
                SyntheticTrack("meta", samples(listOf(5, 9, 3), 0x51), SizeTable.Compact4),
                SyntheticTrack("meta", samples(listOf(5, 9, 3), 0x51), SizeTable.Compact8),
                SyntheticTrack("meta", samples(listOf(300, 9, 3), 0x51), SizeTable.Compact16),
                SyntheticTrack("tmcd", samples(listOf(4), 0x51), SizeTable.Variable, wideOffsets = true),
                SyntheticTrack("camm", samples(listOf(7, 2), 0x51), SizeTable.Variable),
            )
        forms.forEach { metadata ->
            val video = SyntheticTrack("vide", samples(listOf(11, 13), 0x21))
            val audio = SyntheticTrack("soun", samples(listOf(3, 4, 5), 0x31))
            val movie = syntheticMovie(listOf(video, metadata, audio))
            val scrubbed = movie.bytes.copyOf()

            val result = VideoMetadataScrubber.scrub(scrubbed)

            assertTrue("$metadata: $result", result is VideoScrubResult.Scrubbed)
            val zeroed = movie.samples[1].sumOf { it.count() }.toLong()
            assertEquals(zeroed, (result as VideoScrubResult.Scrubbed).summary.zeroedSampleBytes)
            movie.samples[1].forEach { range -> assertTrue(scrubbed.sliceArray(range).all { it == 0.toByte() }) }
            (movie.samples[0] + movie.samples[2]).forEach { range ->
                assertArrayEquals(movie.bytes.sliceArray(range), scrubbed.sliceArray(range))
            }
        }
    }

    /** Header times, handler names and the video vendor and compressor name are zeroed, and layout and size stay. */
    @Test
    fun headerTimesHandlerNamesAndCompressorNamesAreZeroed() {
        val movie = syntheticMovie(listOf(SyntheticTrack("vide", samples(listOf(8), 0x21))))
        assertTrue(movie.bytes.containsAscii("WNPrivateHandler") && movie.bytes.containsAscii("WNPrivateCompressor"))
        val scrubbed = movie.bytes.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        listOf("WNPrivateHandler", "WNPrivateCompressor", "WNVN").forEach { private ->
            assertFalse(private, scrubbed.containsAscii(private))
        }
        val iso = TestIso(scrubbed)
        val headers = iso.descendants().filter { it.type in setOf("mvhd", "tkhd", "mdhd") }
        assertEquals(3, headers.size)
        headers.forEach { header -> assertTrue(header.type, iso.headerTimes(header).all { it == 0.toByte() }) }
        assertEquals(listOf("vide"), iso.tracks().map { it.handler })
        assertEquals(movie.bytes.size, scrubbed.size)
    }

    /** Metadata boxes with a 64-bit largesize become `free` boxes that keep their size fields. */
    @Test
    fun largesizeMetadataBoxesAreNeutralizedInPlace() {
        val topXmp = isoLargeBox("uuid", ByteArray(16) { 7 }, ascii("<x:xmpmeta>WNPrivateXmp</x:xmpmeta>"))
        val movieUdta = isoLargeBox("udta", isoBox("©xyz", ascii("+01.2345+006.7890/")))
        val movie =
            syntheticMovie(
                listOf(SyntheticTrack("vide", samples(listOf(8), 0x21))),
                movieExtras = listOf(movieUdta),
                topLevelExtras = listOf(topXmp),
            )
        val scrubbed = movie.bytes.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        assertFalse(scrubbed.containsAscii("WNPrivateXmp"))
        assertFalse(scrubbed.containsAscii("+01.2345"))
        val iso = TestIso(scrubbed)
        val top = iso.top()
        assertEquals(listOf("ftyp", "mdat", "moov", "free"), top.map { it.type })
        assertEquals("the largesize field is kept", 16, top.last().payload - top.last().start)
        assertTrue(iso.descendants().none { it.type == "udta" || it.type == "uuid" })
    }

    /** A media data box with size zero runs to the end of the file, but inside a container that size is refused. */
    @Test
    fun sizeZeroIsOnlyAcceptedAtTheTopLevel() {
        val movie = syntheticMovie(listOf(SyntheticTrack("meta", samples(listOf(6), 0x51))), mediaDataLast = true)
        val scrubbed = movie.bytes.copyOf()
        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)
        assertTrue(scrubbed.sliceArray(movie.samples[0][0]).all { it == 0.toByte() })

        val nested = syntheticMovie(emptyList(), movieExtras = listOf(concat(beBytes(0, 4), ascii("udta"))))
        assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(nested.bytes.copyOf()))
    }

    /** A fragmented movie whose tracks are all media is scrubbed, including metadata inside fragments. */
    @Test
    fun fragmentedMovieWithOnlyMediaTracksIsScrubbed() {
        val fragment =
            isoBox(
                "moof",
                isoFullBox("mfhd", 0, 0, beBytes(1, 4)),
                isoBox("traf", isoFullBox("tfhd", 0, 0, beBytes(1, 4)), isoBox("udta", ascii("WNPrivateFragmentNote"))),
            )
        val movie =
            syntheticMovie(
                listOf(SyntheticTrack("vide", emptyList()), SyntheticTrack("soun", emptyList())),
                movieExtras = listOf(isoBox("mvex", isoFullBox("trex", 0, 0, ByteArray(20)))),
                topLevelExtras =
                    listOf(isoFullBox("prft", 0, 0, ascii("WNPrivateClock")), fragment, isoBox("mdat", ByteArray(32))),
            )
        val scrubbed = movie.bytes.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        assertFalse(scrubbed.containsAscii("WNPrivateFragmentNote"))
        assertFalse(scrubbed.containsAscii("WNPrivateClock"))
        assertTrue(TestIso(scrubbed).top().any { it.type == "moof" })
    }

    /** Fragment sample tables are not followed, so a fragmented movie with timed metadata is unsupported. */
    @Test
    fun fragmentedMovieWithTimedMetadataIsUnsupported() {
        val movie =
            syntheticMovie(
                listOf(SyntheticTrack("vide", emptyList()), SyntheticTrack("meta", emptyList())),
                movieExtras = listOf(isoBox("mvex", isoFullBox("trex", 0, 0, ByteArray(20)))),
                topLevelExtras = listOf(isoBox("moof", isoFullBox("mfhd", 0, 0, beBytes(1, 4))), isoBox("mdat")),
            )

        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(movie.bytes.copyOf()))
    }

    /** Samples kept in another file, a compressed movie box and a file without a movie are all unsupported. */
    @Test
    fun externalCompressedAndMovielessFilesAreUnsupported() {
        val external = syntheticMovie(listOf(SyntheticTrack("vide", samples(listOf(8), 0x21), selfContained = false)))
        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(external.bytes.copyOf()))

        val compressed = syntheticMovie(emptyList(), movieExtras = listOf(isoBox("cmov", ByteArray(12))))
        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(compressed.bytes.copyOf()))

        val heif = isoBox("ftyp", ascii("heic"), ByteArray(4))
        val image = concat(heif, isoFullBox("meta", 0, 0), isoBox("mdat", ByteArray(4)))
        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(image))
    }

    /** Unknown top-level boxes are neutralized while the boxes that make up the movie layout are kept. */
    @Test
    fun unknownTopLevelBoxesAreNeutralizedAndLayoutBoxesKept() {
        val sidx = isoFullBox("sidx", 0, 0, ByteArray(24) { 3 })
        val movie =
            syntheticMovie(
                listOf(SyntheticTrack("vide", samples(listOf(8), 0x21))),
                topLevelExtras =
                    listOf(isoBox("emsg", ascii("WNPrivateEvent")), sidx, isoBox("jumb", ascii("WNPrivateClaim"))),
            )
        val scrubbed = movie.bytes.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        assertEquals(listOf("ftyp", "mdat", "moov", "free", "sidx", "free"), TestIso(scrubbed).top().map { it.type })
        assertFalse(scrubbed.containsAscii("WNPrivateEvent") || scrubbed.containsAscii("WNPrivateClaim"))
        assertTrue(scrubbed.indexOf(sidx) > 0)
    }

    /** Zero padding after the last box is accepted, and anything else after it is refused. */
    @Test
    fun onlyZeroPaddingMayFollowTheLastBox() {
        val movie = syntheticMovie(listOf(SyntheticTrack("vide", samples(listOf(8), 0x21))))
        assertTrue(VideoMetadataScrubber.scrub(concat(movie.bytes, ByteArray(5))) is VideoScrubResult.Scrubbed)
        assertTrue(VideoMetadataScrubber.scrub(concat(movie.bytes, ByteArray(40))) is VideoScrubResult.Scrubbed)
        assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(concat(movie.bytes, ascii("SEFT"))))
    }

    /** Sizes that overrun the file or their parent, impossible sizes and inconsistent tables are malformed. */
    @Test
    fun inconsistentStructureIsMalformedWithoutThrowing() {
        val ftyp = isoBox("ftyp", ascii("isom"), ByteArray(4))
        val shortHandler = isoBox("trak", isoBox("mdia", isoFullBox("hdlr", 0, 0, ByteArray(4))))
        val cases =
            mapOf(
                "box beyond the file" to concat(ftyp, beBytes(4096, 4), ascii("moov"), ByteArray(8)),
                "size below the header" to concat(ftyp, beBytes(4, 4), ascii("moov")),
                "largesize beyond the file" to concat(ftyp, beBytes(1, 4), ascii("moov"), beBytes(Long.MAX_VALUE, 8)),
                "negative largesize" to concat(ftyp, beBytes(1, 4), ascii("moov"), beBytes(-1L, 8)),
                "child beyond its parent" to concat(ftyp, isoBox("moov", beBytes(64, 4), ascii("udta"), ByteArray(8))),
                "two movies" to concat(ftyp, isoBox("moov", movieHeader()), isoBox("moov", movieHeader())),
                "unknown header version" to concat(ftyp, isoBox("moov", isoFullBox("mvhd", 2, 0, ByteArray(96)))),
                "short handler" to concat(ftyp, isoBox("moov", shortHandler)),
            )
        cases.forEach { (name, bytes) ->
            assertEquals(name, VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(bytes))
        }
    }

    /** Declared table lengths far beyond their boxes are refused before any allocation depends on them. */
    @Test
    fun hugeDeclaredTablesAreMalformedWithoutAllocating() {
        val huge = beBytes(0xFFFFFFFFL, 4)
        val tables =
            listOf(
                "stsz" to isoFullBox("stsz", 0, 0, beBytes(0, 4), huge),
                "stsz" to isoFullBox("stz2", 0, 0, byteArrayOf(0, 0, 0, 16), huge),
                "stsc" to isoFullBox("stsc", 0, 0, huge),
                "stco" to isoFullBox("stco", 0, 0, huge),
            )
        tables.forEach { (replaced, table) ->
            val movie = syntheticMovie(listOf(SyntheticTrack("meta", samples(listOf(4), 0x51))))
            val bytes = replaceTable(movie.bytes, replaced, table)
            assertEquals(replaced, VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(bytes))
        }
    }

    /** A timed-metadata chunk that points outside the media data is refused and its target bytes are untouched. */
    @Test
    fun samplesOutsideTheMediaDataAreNeverZeroed() {
        val movie = syntheticMovie(listOf(SyntheticTrack("meta", samples(listOf(6), 0x51))))
        val moov = TestIso(movie.bytes).top().first { it.type == "moov" }
        val intoMovie = isoFullBox("stco", 0, 0, beBytes(1, 4), beBytes(moov.start.toLong(), 4))
        val outside = replaceTable(movie.bytes, "stco", intoMovie)
        val before = outside.copyOf()

        assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(outside))

        val target = moov.start until moov.start + 6
        assertArrayEquals(before.sliceArray(target), outside.sliceArray(target))
    }

    /** A sample-to-chunk table that does not start at chunk 1 or declares too few samples is refused. */
    @Test
    fun inconsistentSampleToChunkRunsAreMalformed() {
        val movie = syntheticMovie(listOf(SyntheticTrack("meta", samples(listOf(6, 6), 0x51))))
        val late = isoFullBox("stsc", 0, 0, beBytes(1, 4), beBytes(2, 4), beBytes(2, 4), beBytes(1, 4))
        assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(replaceTable(movie.bytes, "stsc", late)))
        val short = isoFullBox("stsc", 0, 0, beBytes(1, 4), beBytes(1, 4), beBytes(1, 4), beBytes(1, 4))
        assertEquals(VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(replaceTable(movie.bytes, "stsc", short)))
    }

    /** Containers that are not ISO BMFF or Matroska are recognized by content and refused as unsupported. */
    @Test
    fun otherContainersAreUnsupported() {
        val avi = concat(ascii("RIFF"), beBytes(100, 4), ascii("AVI LIST"), ByteArray(88))
        val transportStream = ByteArray(188 * 3) { if (it % 188 == 0) 0x47 else 0 }
        val png = concat(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), ByteArray(32))
        listOf(avi, transportStream, png, ByteArray(0), ByteArray(3), ByteArray(64) { 0x7F }).forEach { bytes ->
            assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(bytes))
        }
    }

    /** The walk budget refuses more structural elements, or deeper nesting, than any real file needs. */
    @Test
    fun walkBudgetRefusesBeyondItsBounds() {
        val budget = ScrubBudget()
        val refusal =
            runCatching {
                repeat(4_000_001) { budget.visit() }
            }.exceptionOrNull()
        assertEquals(VideoScrubResult.Malformed, (refusal as VideoScrubRefusal).result)
        val depth = runCatching { ScrubBudget().checkDepth(17) }.exceptionOrNull()
        assertEquals(VideoScrubResult.Malformed, (depth as VideoScrubRefusal).result)
    }

    /** Replaces the first box of [type] in [bytes] with [replacement], fixing every enclosing size. */
    private fun replaceTable(
        bytes: ByteArray,
        type: String,
        replacement: ByteArray,
    ): ByteArray {
        val iso = TestIso(bytes)
        val target = iso.descendants().first { it.type == type }
        val delta = replacement.size - (target.end - target.start)
        val out = concat(bytes.copyOfRange(0, target.start), replacement, bytes.copyOfRange(target.end, bytes.size))
        iso.descendants().filter { it.start < target.start && it.end >= target.end }.forEach { parent ->
            val size = iso.be(parent.start, 4) + delta
            beBytes(size, 4).copyInto(out, parent.start)
        }
        return out
    }
}
