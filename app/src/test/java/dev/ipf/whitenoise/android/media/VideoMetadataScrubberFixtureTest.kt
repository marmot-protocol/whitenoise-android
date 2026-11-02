package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Real-container proof for #3376: tagged fixtures produced by ffmpeg lose every private field the policy names, keep
 * their exact size and every audio and video sample byte, and stay unchanged when scrubbed again.
 */
class VideoMetadataScrubberFixtureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** MP4 tags, 3GPP location, handler names, compressor name and every header time go, and samples stay. */
    @Test
    fun taggedMp4LosesItsPrivateFieldsAndKeepsEverySample() {
        val original = videoFixture("mp4-tagged.mp4")
        val private = listOf("WNPrivateTitle", "WNPrivateAuthor", "WNPrivateComment", "WNPrivateVideoHandler", "Lavf")
        assertAllPresent(original, private + "WNPrivateAudioHandler" + "libx264")
        val boxes = TestIso(original).descendants()
        assertTrue("the fixture must carry a 3GPP location box", boxes.any { it.type == "loci" })

        val scrubbed = scrubbedCopy(original)

        assertNonePresent(scrubbed, private + "WNPrivateAudioHandler" + "libx264")
        assertNoMetadataBoxes(scrubbed)
        assertHeaderTimesZero(scrubbed)
        assertMediaSamplesUnchanged(original, scrubbed)
    }

    /** QuickTime make, model, software, ISO 6709 location and creation date go, and the 90° matrix stays. */
    @Test
    fun quickTimeKeysAreRemovedAndTheRotationMatrixIsKept() {
        val original = videoFixture("mov-quicktime-keys.mov")
        val private =
            listOf(
                "WNPrivateMake",
                "WNPrivateModel",
                "WNPrivateSoftware",
                "+12.3456-065.4321",
                "2024-05-06",
                "Lavf",
                "libx264",
            )
        assertAllPresent(original, private)

        val scrubbed = scrubbedCopy(original)

        assertNonePresent(scrubbed, private)
        assertNoMetadataBoxes(scrubbed)
        assertHeaderTimesZero(scrubbed)
        assertMediaSamplesUnchanged(original, scrubbed)
        val before = TestIso(original)
        val after = TestIso(scrubbed)
        val tkhdBefore = before.tracks().indices.map { trackHeader(before, it) }
        val tkhdAfter = after.tracks().indices.map { trackHeader(after, it) }
        tkhdBefore.zip(tkhdAfter).forEach { (old, new) ->
            assertArrayEquals(
                "the display matrix and dimensions must survive",
                matrixAndSize(original, old),
                matrixAndSize(scrubbed, new),
            )
        }
    }

    /**
     * QuickTime vendor codes are removed everywhere the policy names them: handler component manufacturers and the
     * vendor of every audio and video sample entry, while every sample stays identical.
     */
    @Test
    fun appleVendorCodesAreZeroedInHandlersAndSampleEntries() {
        val original = appleVendorMov()
        assertTrue("four handlers and two sample entries carry the code", original.countAscii("appl") >= 6)

        val scrubbed = scrubbedCopy(original)

        assertEquals(0, scrubbed.countAscii("appl"))
        assertMediaSamplesUnchanged(original, scrubbed)
    }

    /** The timecode track's time-of-day sample is zeroed in place while video and audio samples stay identical. */
    @Test
    fun timecodeTrackSamplesAreZeroedAndMediaSamplesAreKept() {
        val original = videoFixture("mp4-timecode.mp4")
        val timecode = TestIso(original).tracks().single { it.handler == "tmcd" }
        val timeOfDay = timecode.samples.any { range -> range.any { original[it] != 0.toByte() } }
        assertTrue("the fixture's timecode must not already be zero", timeOfDay)

        val scrubbed = scrubbedCopy(original)

        val after = TestIso(scrubbed).tracks().single { it.handler == "tmcd" }
        assertEquals(timecode.samples, after.samples)
        after.samples.forEach { range -> range.forEach { assertEquals(0.toByte(), scrubbed[it]) } }
        assertNonePresent(scrubbed, listOf("WNPrivateComment", "Lavf"))
        assertHeaderTimesZero(scrubbed)
        assertMediaSamplesUnchanged(original, scrubbed)
    }

    /** Matroska Tags, attachment, title, date and track names go, app strings stay empty and clusters stay. */
    @Test
    fun matroskaTagsAttachmentsAndNamesAreVoidedAndClustersAreKept() {
        assertMatroskaScrubbed(
            "mkv-tagged.mkv",
            listOf(
                "WNPrivateTitle",
                "WNPrivateComment",
                "WNPrivateAuthor",
                "WNPrivateTrackName",
                "WNPrivateAudioName",
                "WNPrivateAttachmentNote",
                "+48.8584",
                "Lavf",
                "libx264",
            ),
        )
    }

    /** A live WebM with an unknown-size Segment is scrubbed the same way. */
    @Test
    fun unknownSizeWebmSegmentIsScrubbed() {
        val original = videoFixture("webm-live-tagged.webm")
        val unknownSize = original.indexOf(beBytes(0x01FFFFFFFFFFFFFFL, 8))
        assertTrue("the fixture must have an unknown-size Segment", unknownSize > 0)
        assertMatroskaScrubbed(
            "webm-live-tagged.webm",
            listOf(
                "WNPrivateTitle",
                "WNPrivateComment",
                "WNPrivateAuthor",
                "WNPrivateTrackName",
                "+48.8584",
                "Lavf",
                "libvpx",
            ),
        )
    }

    /** Scrubbing an already scrubbed fixture reports success and changes no byte. */
    @Test
    fun scrubbingTwiceEqualsScrubbingOnce() {
        VIDEO_FIXTURES.forEach { name ->
            val once = scrubbedCopy(videoFixture(name))
            val twice = once.copyOf()
            assertTrue(name, VideoMetadataScrubber.scrub(twice) is VideoScrubResult.Scrubbed)
            assertArrayEquals(name, once, twice)
        }
    }

    /** The file, in-place array and copy-on-write entry points produce the same bytes. */
    @Test
    fun fileArrayAndCopyEntryPointsAgree() {
        VIDEO_FIXTURES.forEach { name ->
            val original = videoFixture(name)
            val file = File(temporary.root, name).apply { writeBytes(original) }
            assertTrue(name, VideoMetadataScrubber.scrub(file) is VideoScrubResult.Scrubbed)
            val inPlace = original.copyOf().also { VideoMetadataScrubber.scrub(it) }
            val untouched = original.copyOf()
            val copied = checkNotNull(VideoMetadataScrubber.scrubbedOrNull(untouched)) { name }
            assertArrayEquals(name, inPlace, file.readBytes())
            assertArrayEquals(name, inPlace, copied)
            assertArrayEquals("$name: the copy entry point must not mutate its input", original, untouched)
            assertNotSame(name, untouched, copied)
            val again = VideoMetadataScrubber.scrubbedOrNull(copied)
            assertSame("$name: clean input is reused without a copy", copied, again)
        }
    }

    /** Truncations and random corruption of every fixture end in a result, never an exception or a size change. */
    @Test
    fun corruptedFixturesNeverThrow() {
        val random = Random(3376)
        VIDEO_FIXTURES.forEach { name ->
            val original = videoFixture(name)
            for (cut in listOf(1, 4, 8, 16, 33, original.size / 3, original.size / 2, original.size - 1)) {
                val truncated = original.copyOf(cut)
                VideoMetadataScrubber.scrub(truncated)
                assertEquals(cut, truncated.size)
            }
            repeat(MUTATIONS_PER_FIXTURE) {
                val mutated = original.copyOf()
                repeat(1 + random.nextInt(8)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
                VideoMetadataScrubber.scrub(mutated)
                assertEquals(original.size, mutated.size)
            }
        }
    }

    /** The instrumentation assets carry exactly the JVM fixtures, so device and JVM tests prove the same files. */
    @Test
    fun deviceAssetsMatchTheJvmFixtures() {
        VIDEO_FIXTURES.forEach { name ->
            val asset = File("src/androidTest/assets/video-metadata/$name.b64").readBytes()
            val stream = checkNotNull(javaClass.getResourceAsStream("/video-metadata/$name.b64"))
            val resource = stream.use { it.readBytes() }
            assertArrayEquals(name, resource, asset)
        }
    }

    /** Scrubs a copy of [original] and checks the result and the unchanged size. */
    private fun scrubbedCopy(original: ByteArray): ByteArray {
        val copy = original.copyOf()
        val result = VideoMetadataScrubber.scrub(copy)
        assertTrue("expected a scrubbed result, got $result", result is VideoScrubResult.Scrubbed)
        assertEquals(original.size, copy.size)
        return copy
    }

    /** Checks the shared Matroska expectations for the fixture [name] and its [private] strings. */
    private fun assertMatroskaScrubbed(
        name: String,
        private: List<String>,
    ) {
        val original = videoFixture(name)
        assertAllPresent(original, private)

        val scrubbed = scrubbedCopy(original)

        assertNonePresent(scrubbed, private)
        val before = TestEbml(original).segmentChildren()
        val after = TestEbml(scrubbed).segmentChildren()
        assertTrue(before.any { it.id == TestEbml.TAGS })
        val removed = setOf(TestEbml.TAGS, TestEbml.ATTACHMENTS, TestEbml.CHAPTERS)
        assertFalse("Tags, Attachments and Chapters must be gone", after.any { it.id in removed })
        val clustersBefore = before.filter { it.id == TestEbml.CLUSTER }
        assertEquals(clustersBefore, after.filter { it.id == TestEbml.CLUSTER })
        clustersBefore.forEach { cluster ->
            val range = cluster.start until cluster.end
            assertArrayEquals(original.sliceArray(range), scrubbed.sliceArray(range))
        }
        val ebml = TestEbml(scrubbed)
        val info = ebml.children(after.single { it.id == TestEbml.INFO }).map { it.id }
        assertFalse("DateUTC and Title must be gone", info.any { it == TestEbml.DATE_UTC || it == TestEbml.TITLE })
        val applications = TestEbml.MUXING_APP in info && TestEbml.WRITING_APP in info
        assertTrue("the mandatory application strings stay", applications)
        val entries = ebml.children(after.single { it.id == TestEbml.TRACKS }).filter { it.id == TestEbml.TRACK_ENTRY }
        assertTrue(entries.isNotEmpty())
        entries.forEach { entry ->
            assertFalse("track names must be gone", ebml.children(entry).any { it.id == TestEbml.NAME })
        }
    }

    /** Every movie, track and media header time is zero. */
    private fun assertHeaderTimesZero(scrubbed: ByteArray) {
        val iso = TestIso(scrubbed)
        val headers = iso.descendants().filter { it.type in setOf("mvhd", "tkhd", "mdhd") }
        assertTrue(headers.size >= 3)
        headers.forEach { header -> assertTrue(header.type, iso.headerTimes(header).all { it == 0.toByte() }) }
    }

    /** No `udta`, `meta` or location box is reachable through the movie structure. */
    private fun assertNoMetadataBoxes(scrubbed: ByteArray) {
        val types = TestIso(scrubbed).descendants().map { it.type }
        assertFalse(types.toString(), types.any { it in setOf("udta", "meta", "loci", "keys", "ilst", "uuid") })
    }

    /** Every video and audio sample keeps its offset, size and bytes. */
    private fun assertMediaSamplesUnchanged(
        original: ByteArray,
        scrubbed: ByteArray,
    ) {
        val before = TestIso(original).tracks()
        val after = TestIso(scrubbed).tracks()
        assertEquals(before.map { it.handler }, after.map { it.handler })
        before.zip(after).filter { (track, _) -> track.handler in setOf("vide", "soun") }.forEach { (old, new) ->
            assertEquals(old.samples, new.samples)
            assertTrue(old.samples.isNotEmpty())
            old.samples.forEach { range ->
                assertArrayEquals(original.sliceArray(range), scrubbed.sliceArray(range))
            }
        }
    }

    /** The `tkhd` of track [index]. */
    private fun trackHeader(
        iso: TestIso,
        index: Int,
    ): TestBox {
        val moov = iso.top().first { it.type == "moov" }
        val trak = iso.children(moov.payload, moov.end).filter { it.type == "trak" }[index]
        return checkNotNull(iso.child(trak, "tkhd"))
    }

    /** The display matrix, width and height at the end of a version 0 or 1 `tkhd`. */
    private fun matrixAndSize(
        bytes: ByteArray,
        tkhd: TestBox,
    ): ByteArray = bytes.copyOfRange(tkhd.end - 44, tkhd.end)

    /** Every string in [values] occurs in [bytes], so a passing absence check is not vacuous. */
    private fun assertAllPresent(
        bytes: ByteArray,
        values: List<String>,
    ) = values.forEach { assertTrue("fixture must contain $it", bytes.containsAscii(it)) }

    /** No string in [values] occurs in [bytes]. */
    private fun assertNonePresent(
        bytes: ByteArray,
        values: List<String>,
    ) = values.forEach { assertFalse("scrubbed output still contains $it", bytes.containsAscii(it)) }

    private companion object {
        const val MUTATIONS_PER_FIXTURE = 400
    }
}
