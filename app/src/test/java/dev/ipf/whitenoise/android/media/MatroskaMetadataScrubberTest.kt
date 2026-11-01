package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic Matroska and WebM structures for #3376: unknown-size Segments and Clusters, Void replacement in both
 * size forms, CRC-32 handling, and malformed or unsupported EBML. Each case states the result and what changed.
 */
class MatroskaMetadataScrubberTest {
    /** Open-ended Segments and Clusters end at the next segment-level element. Metadata goes and frames stay. */
    @Test
    fun unknownSizeSegmentAndClustersAreScrubbedAndTheirFramesKept() {
        val firstCluster = openCluster(0, "frame-one")
        val secondCluster = openCluster(9, "frame-two")
        val file =
            concat(
                ebmlHeader("webm"),
                ebmlUnknown(
                    SEGMENT,
                    info(),
                    tracks(),
                    firstCluster,
                    tags(),
                    secondCluster,
                    ebml(ATTACHMENTS, ebml(0x61A7L, ebml(0x466EL, ascii("WNPrivateFile.txt")))),
                ),
            )
        val scrubbed = file.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        PRIVATE.forEach { assertFalse(it, scrubbed.containsAscii(it)) }
        assertEquals("the recording date is voided", -1, scrubbed.indexOf(beBytes(PRIVATE_DATE, 8)))
        val first = file.indexOf(firstCluster)
        assertArrayEquals(firstCluster, scrubbed.sliceArray(first until first + firstCluster.size))
        assertEquals(file.indexOf(secondCluster), scrubbed.indexOf(secondCluster))
        val ids = TestEbml(scrubbed).segmentChildren().map { it.id }
        assertFalse(ids.contains(TAGS) || ids.contains(ATTACHMENTS))
        assertEquals(scrubbed.size, file.size)
    }

    /** An element longer than 128 bytes becomes a Void with an eight-byte size, ending exactly where it did. */
    @Test
    fun largeElementsBecomeVoidsWithTheLongSizeForm() {
        val tags = ebml(TAGS, ByteArray(300) { 'x'.code.toByte() })
        val file = concat(ebmlHeader(), ebml(SEGMENT, info(), tags))
        val scrubbed = file.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        val children = TestEbml(scrubbed).segmentChildren()
        val void = children.last()
        assertEquals(VOID, void.id)
        assertEquals(scrubbed.size, void.end)
        assertEquals(9, void.dataStart - void.start)
        assertTrue(scrubbed.sliceArray(void.dataStart until void.end).all { it == 0.toByte() })
    }

    /** CRC-32 elements of the rewritten masters are voided, so readers that verify them find nothing stale. */
    @Test
    fun crcOfRewrittenMastersIsVoided() {
        val crc = ebml(CRC32, byteArrayOf(1, 2, 3, 4))
        val file = concat(ebmlHeader("matroska"), ebml(SEGMENT, ebml(INFO, crc, ebml(TITLE, ascii("WNPrivateTitle")))))
        val scrubbed = file.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        assertEquals(-1, scrubbed.indexOf(crc))
        val ebml = TestEbml(scrubbed)
        val info = ebml.children(ebml.segmentChildren().single { it.id == INFO })
        assertEquals(listOf(VOID, VOID), info.map { it.id })
    }

    /** The mandatory application strings stay as zero-filled strings and existing Void bodies are cleared. */
    @Test
    fun applicationStringsAreEmptiedAndVoidBodiesCleared() {
        val file =
            concat(
                ebmlHeader(),
                ebml(SEGMENT, ebml(VOID, ascii("WNPrivateLeftover")), info()),
                ebml(VOID, ascii("WNPrivateTail")),
            )
        val scrubbed = file.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        PRIVATE.forEach { assertFalse(it, scrubbed.containsAscii(it)) }
        assertFalse(scrubbed.containsAscii("WNPrivateLeftover") || scrubbed.containsAscii("WNPrivateTail"))
        val ebml = TestEbml(scrubbed)
        val info = ebml.children(ebml.segmentChildren().single { it.id == INFO }).map { it.id }
        assertTrue(MUXING_APP in info && WRITING_APP in info)
    }

    /** An unknown segment-level element is voided rather than kept. */
    @Test
    fun unknownSegmentLevelElementsAreVoided() {
        val file = concat(ebmlHeader(), ebml(SEGMENT, info(), ebml(0x1B538667L, ascii("WNPrivateVendorData"))))
        val scrubbed = file.copyOf()

        assertTrue(VideoMetadataScrubber.scrub(scrubbed) is VideoScrubResult.Scrubbed)

        assertFalse(scrubbed.containsAscii("WNPrivateVendorData"))
        assertEquals(VOID, TestEbml(scrubbed).segmentChildren().last().id)
    }

    /** Only Matroska and WebM document types are understood. */
    @Test
    fun otherDocumentTypesAreUnsupported() {
        val file = concat(ebmlHeader("x-private-doc"), ebml(SEGMENT, info()))

        assertEquals(VideoScrubResult.Unsupported, VideoMetadataScrubber.scrub(file))
    }

    /** Invalid IDs and sizes, overruns, open-ended masters other than Segment and Cluster, and no Segment fail. */
    @Test
    fun malformedEbmlIsRefusedWithoutThrowing() {
        val header = ebmlHeader()
        val cases =
            mapOf(
                "zero id byte" to concat(header, byteArrayOf(0, 0x81.toByte(), 0)),
                "element beyond its parent" to
                    concat(header, ebml(SEGMENT, concat(ebmlId(INFO), ebmlSize(500), ByteArray(4)))),
                "open-ended Info" to concat(header, ebmlUnknown(SEGMENT, ebmlUnknown(INFO, ebml(TITLE, ascii("x"))))),
                "no Segment" to header,
                "sized Segment with a nested Segment" to concat(header, ebml(SEGMENT, ebml(SEGMENT))),
                "truncated size" to concat(header, ebmlId(SEGMENT), byteArrayOf(0x01)),
                "open-ended Cluster child" to
                    concat(header, ebmlUnknown(SEGMENT, ebmlUnknown(CLUSTER, ebmlUnknown(0xA0L, ByteArray(4))))),
            )
        cases.forEach { (name, bytes) ->
            assertEquals(name, VideoScrubResult.Malformed, VideoMetadataScrubber.scrub(bytes))
        }
    }

    /** Zero padding after the last element is accepted, and another open-ended Segment ends the previous one. */
    @Test
    fun paddingAndChainedSegmentsAreAccepted() {
        val padded = concat(ebmlHeader(), ebml(SEGMENT, info()), ByteArray(12))
        assertTrue(VideoMetadataScrubber.scrub(padded) is VideoScrubResult.Scrubbed)

        val chained = concat(ebmlHeader(), ebmlUnknown(SEGMENT, info()), ebmlUnknown(SEGMENT, info()))
        assertTrue(VideoMetadataScrubber.scrub(chained) is VideoScrubResult.Scrubbed)
        PRIVATE.forEach { assertFalse(it, chained.containsAscii(it)) }
    }

    /** An Info element carrying every private field the policy names. */
    private fun info(): ByteArray =
        ebml(
            INFO,
            ebml(0x2AD7B1L, beBytes(1_000_000, 3)),
            ebml(0x7384L, ascii("WNPrivateSegment.mkv")),
            ebml(0x4461L, beBytes(PRIVATE_DATE, 8)),
            ebml(TITLE, ascii("WNPrivateTitle")),
            ebml(MUXING_APP, ascii("WNPrivateMuxer")),
            ebml(WRITING_APP, ascii("WNPrivateWriter")),
        )

    /** Tags with one simple tag holding a private location. */
    private fun tags(): ByteArray =
        ebml(
            TAGS,
            ebml(0x7373L, ebml(0x67C8L, ebml(0x45A3L, ascii("LOCATION")), ebml(0x4487L, ascii("WNPrivatePlace")))),
        )

    /** An unknown-size Cluster holding one timestamp and one frame. */
    private fun openCluster(
        timestamp: Int,
        frame: String,
    ): ByteArray {
        val time = ebml(TIMESTAMP, byteArrayOf(timestamp.toByte()))
        return ebmlUnknown(CLUSTER, time, ebml(SIMPLE_BLOCK, ascii(frame)))
    }

    /** Tracks with one entry carrying a private name and codec name. */
    private fun tracks(): ByteArray =
        ebml(
            0x1654AE6BL,
            ebml(
                0xAEL,
                ebml(0xD7L, byteArrayOf(1)),
                ebml(0x86L, ascii("V_VP8")),
                ebml(0x536EL, ascii("WNPrivateTrack")),
                ebml(0x258688L, ascii("WNPrivateCodecName")),
            ),
        )

    private companion object {
        const val SEGMENT = 0x18538067L
        const val INFO = 0x1549A966L
        const val CLUSTER = 0x1F43B675L
        const val TAGS = 0x1254C367L
        const val ATTACHMENTS = 0x1941A469L
        const val TIMESTAMP = 0xE7L
        const val SIMPLE_BLOCK = 0xA3L
        const val VOID = 0xECL
        const val CRC32 = 0xBFL
        const val TITLE = 0x7BA9L
        const val MUXING_APP = 0x4D80L
        const val WRITING_APP = 0x5741L
        const val PRIVATE_DATE = 0x0123456789ABCDEFL

        val PRIVATE =
            listOf(
                "WNPrivateSegment",
                "WNPrivateTitle",
                "WNPrivateMuxer",
                "WNPrivateWriter",
                "WNPrivateTrack",
                "WNPrivateCodecName",
                "WNPrivatePlace",
                "WNPrivateFile",
            )
    }
}
