package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.VP8X_ANIMATION
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.alph
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.anim
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.animatedWebp
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.anmf
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.chunk
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gifExtension
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gifFrame
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gifLoopExtension
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.riff
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.u32le
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8l
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8x
import dev.ipf.whitenoise.android.media.AnimationSourceRefusal.CanvasLimit
import dev.ipf.whitenoise.android.media.AnimationSourceRefusal.FrameLimit
import dev.ipf.whitenoise.android.media.AnimationSourceRefusal.Malformed
import dev.ipf.whitenoise.android.media.AnimationSourceRefusal.WorkLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Pure admission walk over hand-built GIF/WebP containers; nothing here decodes pixels. */
class AnimationSourceAdmissionTest {
    // ---- non-animation inputs ------------------------------------------------

    @Test
    fun unrecognizedSignaturesAreNotAnimation() {
        assertNotAnimation(ByteArray(0))
        assertNotAnimation(PNG_SIGNATURE + ByteArray(32))
        assertNotAnimation(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        assertNotAnimation("GIF8".encodeToByteArray())
    }

    // ---- GIF -----------------------------------------------------------------

    @Test
    fun gif_completeSourcesAreAdmittedWithCanvasAndFrames() {
        assertGif(gif(), width = 4, height = 4, frames = 2)
        assertGif(gif(frames = listOf(gifFrame())), width = 4, height = 4, frames = 1)
        assertGif(twoFrameGif(64, 32, comment = "c"), width = 64, height = 32, frames = 2)
    }

    @Test
    fun gif_everyStrictPrefixIsRefusedOnceRecognized() {
        val source = gif()
        for (length in 0 until source.size) {
            val expected = if (length < GIF_SIGNATURE_BYTES) AnimationSourceAdmission.NotAnimation else MALFORMED
            assertEquals("prefix $length", expected, admitAnimationSource(source.copyOf(length)))
        }
    }

    @Test
    fun gif_trailerMustBeFinalAndFollowAFrame() {
        assertRefused(gif() + byteArrayOf(0))
        assertRefused(gif(frames = emptyList()))
        assertRefused(gif(trailer = false))
        assertRefused(gif(extensions = listOf(byteArrayOf(0x00))))
    }

    @Test
    fun gif_framesMustHavePositiveRectanglesInsideTheCanvas() {
        assertRefused(gif(frames = listOf(gifFrame(width = 0))))
        assertRefused(gif(frames = listOf(gifFrame(height = 0))))
        assertRefused(gif(frames = listOf(gifFrame(left = 3, width = 2))))
        assertRefused(gif(frames = listOf(gifFrame(top = 4))))
        assertRefused(gif(frames = listOf(gifFrame(left = 0xffff, width = 0xffff))))
        assertGif(gif(frames = listOf(gifFrame(left = 3, top = 3))), width = 4, height = 4, frames = 1)
    }

    @Test
    fun gif_zeroOrOversizedCanvasIsRefused() {
        assertRefused(gif(width = 0))
        assertRefused(gif(height = 0))
        assertRefused(gif(width = MAX_ANIMATION_SOURCE_EDGE_PX + 1, height = 1), CanvasLimit)
        assertRefused(gif(width = 2049, height = 2048), CanvasLimit)
        assertRefused(gif(width = 0xffff, height = 0xffff), CanvasLimit)
    }

    @Test
    fun gif_canvasAndWorkBoundariesAreInclusive() {
        val edge = MAX_ANIMATION_SOURCE_EDGE_PX
        val widest = gif(width = edge, height = 1024, frames = listOf(gifFrame()))
        assertGif(widest, width = edge, height = 1024, frames = 1)
        val atWork = gif(width = 2048, height = 2048, frames = List(MAX_CANVAS_FRAMES) { gifFrame() })
        assertGif(atWork, width = 2048, height = 2048, frames = MAX_CANVAS_FRAMES)
    }

    @Test
    fun gif_partialFramesAreChargedTheWholeCanvas() {
        val frames = List(MAX_CANVAS_FRAMES + 1) { gifFrame() }
        assertRefused(gif(width = 2048, height = 2048, frames = frames), WorkLimit)
    }

    @Test
    fun gif_frameCountBoundary() {
        val atLimit = List(MAX_ANIMATION_SOURCE_FRAMES) { gifFrame(graphicControl = false) }
        val maxFrames = MAX_ANIMATION_SOURCE_FRAMES
        assertGif(gif(width = 1, height = 1, frames = atLimit), width = 1, height = 1, frames = maxFrames)
        val overLimit = atLimit + listOf(gifFrame(graphicControl = false))
        assertRefused(gif(width = 1, height = 1, frames = overLimit), FrameLimit)
    }

    @Test
    fun gif_ordinaryChatShapesFitTheProvisionalWorkBudget() {
        for ((edge, frameCount) in listOf(480 to 24, 500 to 96, 320 to 300, 240 to 580)) {
            val source = gif(width = edge, height = edge, frames = List(frameCount) { gifFrame() })
            assertGif(source, width = edge, height = edge, frames = frameCount)
        }
    }

    @Test
    fun gif_extensionsAreWalkedAsBoundedMetadata() {
        val plainText = gifExtension(0x01, ByteArray(12), "hello".encodeToByteArray())
        val comment = gifExtension(0xfe, "note".encodeToByteArray())
        val unknown = gifExtension(0x99, ByteArray(3))
        val source = gif(extensions = listOf(gifLoopExtension(), plainText, comment, unknown))
        assertGif(source, width = 4, height = 4, frames = 2)
    }

    @Test
    fun gif_specifiedExtensionBlockSizesAreEnforced() {
        assertRefused(gif(extensions = listOf(gifExtension(0x01, ByteArray(11)))))
        assertRefused(gif(extensions = listOf(gifExtension(0xff, ByteArray(10)))))
        assertRefused(gif(extensions = listOf(gifExtension(0xf9, ByteArray(5)))))
        assertRefused(gif(extensions = listOf(byteArrayOf(0x21, 0xfe.toByte(), 9, 1))))
    }

    @Test
    fun gif_localColorTablesAndLzwHeaderAreChecked() {
        val fullLocalTable = gifFrame(packed = 0x87, localTableBytes = 768)
        assertGif(gif(frames = listOf(fullLocalTable)), width = 4, height = 4, frames = 1)
        assertRefused(gif(frames = listOf(gifFrame(packed = 0x87, localTableBytes = 10))))
        assertRefused(gif(frames = listOf(gifFrame(lzwMinimumCodeSize = 0))))
        assertRefused(gif(frames = listOf(gifFrame(lzwMinimumCodeSize = 1))))
        assertRefused(gif(frames = listOf(gifFrame(lzwMinimumCodeSize = 12))))
    }

    // ---- still WebP ------------------------------------------------------------

    @Test
    fun webp_simpleStillImagesAreNotAnimation() {
        assertNotAnimation(riff(vp8l(3, 2)))
        assertNotAnimation(riff(vp8(3, 2)))
        assertNotAnimation(riff(vp8l(3, 2), chunk("EXIF", ByteArray(3))))
    }

    @Test
    fun webp_simpleStillCannotHideAnimationOrSecondImage() {
        for (hidden in listOf(anim(), anmf(), vp8x(3, 2, 0), vp8l(3, 2), vp8(3, 2), alph(0, 7))) {
            assertRefused(riff(vp8l(3, 2), hidden))
        }
    }

    @Test
    fun webp_bitstreamHeadersAreChecked() {
        val shown = 1 shl 4
        assertRefused(riff(vp8(3, 2, frameTag = 1 or shown)))
        assertRefused(riff(vp8(3, 2, frameTag = 4 shl 5)))
        assertRefused(riff(vp8(3, 2, frameTag = shown or (4 shl 1))))
        assertRefused(riff(vp8(3, 2, frameTag = shown or (16 shl 5))))
        assertRefused(riff(vp8(3, 2, startCode = byteArrayOf(0, 0, 0))))
        assertRefused(riff(vp8(0, 2)))
        assertRefused(riff(vp8l(3, 2, version = 1)))
        assertRefused(riff(chunk("VP8L", byteArrayOf(0x2f, 0, 0))))
    }

    @Test
    fun webp_riffSizeAndPaddingMustBeExact() {
        val still = riff(vp8l(3, 2))
        assertRefused(still + byteArrayOf(0, 0))
        assertRefused(still.copyOf().also { u32le(still.size - 9L).copyInto(it, 4) })
        assertRefused(riff(vp8l(3, 2), chunk("XMP ", ByteArray(3), pad = false)))
        val nonzeroPadding = chunk("XMP ", ByteArray(3)).also { it[it.lastIndex] = 1 }
        assertRefused(riff(vp8l(3, 2), nonzeroPadding))
        assertNotAnimation(riff(vp8l(3, 2), chunk("XMP ", ByteArray(3))))
        assertRefused(riff(chunk("JUNK", ByteArray(2))))
        assertRefused(riff())
    }

    @Test
    fun webp_extendedStillImagesFollowTheStillGrammar() {
        assertNotAnimation(riff(vp8x(3, 2, 0), chunk("ICCP", ByteArray(4)), vp8l(3, 2)))
        assertNotAnimation(riff(vp8x(3, 2, 0xc1, reserved = 0xffffff), vp8l(3, 2)))
        assertNotAnimation(riff(vp8x(3, 2, 0x10), alph(0, 7), vp8(3, 2)))
        assertNotAnimation(riff(vp8x(3, 2, 0x10), alph(1, 4), vp8(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0x10), alph(0, 8), vp8(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0x10), alph(2, 4), vp8(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0x10), alph(1, 4), vp8l(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0x10), alph(1, 4), chunk("EXIF", ByteArray(2)), vp8(3, 2)))
        assertRefused(riff(vp8x(4, 2, 0), vp8l(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0), vp8l(3, 2), vp8l(3, 2)))
        assertRefused(riff(vp8x(3, 2, 0)))
        assertRefused(riff(chunk("VP8X", ByteArray(12)), vp8l(1, 1)))
    }

    @Test
    fun webp_stillHeaderCannotMasqueradeOverAnimationChunks() {
        assertRefused(riff(vp8x(1, 1, 0), anim(), anmf()))
        assertRefused(riff(vp8x(1, 1, 0), vp8l(1, 1), anmf()))
        assertRefused(riff(vp8x(1, 1, 0), vp8l(1, 1), anim()))
        assertRefused(riff(vp8x(1, 1, VP8X_ANIMATION), vp8l(1, 1)))
    }

    // ---- animated WebP ---------------------------------------------------------

    @Test
    fun webp_animatedSourceIsAdmittedWithCanvasAndFrames() {
        assertWebp(animatedWebp(), width = 4, height = 4, frames = 2)
    }

    @Test
    fun webp_everyStrictPrefixIsRefusedOnceRecognized() {
        val source = animatedWebp()
        for (length in 0 until source.size) {
            val expected = if (length < WEBP_SIGNATURE_BYTES) AnimationSourceAdmission.NotAnimation else MALFORMED
            assertEquals("prefix $length", expected, admitAnimationSource(source.copyOf(length)))
        }
    }

    @Test
    fun webp_animationChunkOrderIsEnforced() {
        val header = vp8x(4, 4, VP8X_ANIMATION)
        assertRefused(riff(header, anmf(), anim()))
        assertRefused(riff(header, anim(), anim(), anmf()))
        assertRefused(riff(header, anmf()))
        assertRefused(riff(header, anim()))
        assertRefused(riff(header))
        assertRefused(riff(header, anim(), anmf(), vp8l(4, 4)))
        assertRefused(riff(header, chunk("ANIM", ByteArray(8)), anmf()))
        val withMetadata = riff(header, chunk("ICCP", ByteArray(2)), anim(), anmf(), chunk("XMP ", ByteArray(5)))
        assertWebp(withMetadata, width = 4, height = 4, frames = 1)
    }

    @Test
    fun webp_frameRectanglesUseDoubledOffsetsAndStayInsideCanvas() {
        val corner = anmf(left = 2, top = 2, width = 2, height = 2)
        assertWebp(animatedWebp(frames = listOf(corner)), width = 4, height = 4, frames = 1)
        assertRefused(animatedWebp(frames = listOf(anmf(left = 4, width = 1))))
        assertRefused(animatedWebp(frames = listOf(anmf(top = 2, height = 3))))
        assertRefused(animatedWebp(frames = listOf(anmf(width = 5))))
    }

    @Test
    fun webp_frameBitstreamMustMatchItsRectangle() {
        assertRefused(animatedWebp(frames = listOf(anmf(width = 2, height = 2, frameData = listOf(vp8l(3, 3))))))
        assertWebp(animatedWebp(frames = listOf(anmf(width = 2, height = 2, frameData = listOf(vp8(2, 2))))), 4, 4, 1)
    }

    @Test
    fun webp_frameDataGrammarIsEnforced() {
        assertWebp(webpFrame(alph(0, 5), vp8(2, 2)), width = 4, height = 4, frames = 1)
        assertWebp(webpFrame(alph(1, 3), vp8(2, 2)), width = 4, height = 4, frames = 1)
        assertWebp(webpFrame(vp8(2, 2), chunk("UNKN", ByteArray(2))), width = 4, height = 4, frames = 1)
        assertRefused(webpFrame(alph(0, 6), vp8(2, 2)))
        assertRefused(webpFrame(alph(3, 3), vp8(2, 2)))
        assertRefused(webpFrame(alph(1, 3), vp8l(2, 2)))
        assertRefused(webpFrame(chunk("UNKN", ByteArray(2)), vp8(2, 2)))
        assertRefused(webpFrame(vp8(2, 2), vp8(2, 2)))
        assertRefused(webpFrame(vp8(2, 2), anmf()))
        assertRefused(webpFrame())
    }

    @Test
    fun webp_nestedChunksMustExactlyFillTheFramePayload() {
        val originPixel = ByteArray(16) // Zero offsets and a 1 x 1 rectangle.
        val overrun = chunk("ANMF", originPixel + "VP8L".encodeToByteArray() + u32le(64) + ByteArray(8))
        assertRefused(animatedWebp(frames = listOf(overrun)))
        assertRefused(animatedWebp(frames = listOf(chunk("ANMF", originPixel + vp8l(1, 1) + ByteArray(2)))))
        assertRefused(animatedWebp(frames = listOf(chunk("ANMF", ByteArray(14)))))
        val nonzeroPadding = chunk("UNKN", ByteArray(3)).also { it[it.lastIndex] = 1 }
        assertRefused(animatedWebp(frames = listOf(anmf(frameData = listOf(vp8l(1, 1), nonzeroPadding)))))
    }

    @Test
    fun webp_reservedBlendAndDisposeBitsAreIgnored() {
        assertWebp(animatedWebp(frames = listOf(anmf(flags = 0xff))), width = 4, height = 4, frames = 1)
    }

    @Test
    fun webp_canvasLimits() {
        assertRefused(animatedWebp(width = MAX_ANIMATION_SOURCE_EDGE_PX + 1, height = 1), CanvasLimit)
        assertRefused(animatedWebp(width = 2049, height = 2048), CanvasLimit)
        assertRefused(riff(vp8x(1 shl 24, 1 shl 24, VP8X_ANIMATION), anim(), anmf()), CanvasLimit)
    }

    @Test
    fun webp_frameAndWorkLimits() {
        val atWork = animatedWebp(width = 2048, height = 2048, frames = List(MAX_CANVAS_FRAMES) { anmf() })
        assertWebp(atWork, width = 2048, height = 2048, frames = MAX_CANVAS_FRAMES)
        val overWork = animatedWebp(width = 2048, height = 2048, frames = List(MAX_CANVAS_FRAMES + 1) { anmf() })
        assertRefused(overWork, WorkLimit)
        val overFrames = animatedWebp(width = 1, height = 1, frames = List(MAX_ANIMATION_SOURCE_FRAMES + 1) { anmf() })
        assertRefused(overFrames, FrameLimit)
    }

    // ---- bounded mutation sweep ------------------------------------------------

    @Test
    fun seededMutationsNeverThrowAndAdmittedResultsRespectEveryLimit() {
        val random = Random(MUTATION_SEED)
        val seeds = listOf(gif(), animatedWebp(), riff(vp8x(3, 2, 0x10), alph(0, 7), vp8(3, 2)), twoFrameGif(8, 8))
        repeat(MUTATION_ROUNDS) {
            val mutated = mutate(seeds[random.nextInt(seeds.size)], random)
            val result = admitAnimationSource(mutated)
            if (result is AnimationSourceAdmission.Admitted) assertWithinLimits(mutated, result)
            assertEquals(result, admitAnimationSource(mutated))
        }
    }

    private fun webpFrame(vararg data: ByteArray): ByteArray =
        animatedWebp(frames = listOf(anmf(width = 2, height = 2, frameData = data.toList())))

    private fun mutate(
        source: ByteArray,
        random: Random,
    ): ByteArray {
        val bytes = source.copyOf()
        repeat(1 + random.nextInt(4)) {
            bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
        }
        return if (random.nextBoolean()) bytes else bytes.copyOf(random.nextInt(bytes.size + 1))
    }

    private fun assertWithinLimits(
        bytes: ByteArray,
        result: AnimationSourceAdmission.Admitted,
    ) {
        val expectedKind = if (isGif(bytes)) AnimationSourceKind.Gif else AnimationSourceKind.Webp
        assertEquals(expectedKind, result.kind)
        assertTrue(result.canvasWidth in 1..MAX_ANIMATION_SOURCE_EDGE_PX)
        assertTrue(result.canvasHeight in 1..MAX_ANIMATION_SOURCE_EDGE_PX)
        val canvas = result.canvasWidth.toLong() * result.canvasHeight
        assertTrue(canvas <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS)
        assertTrue(result.frameCount in 1..MAX_ANIMATION_SOURCE_FRAMES)
        assertTrue(canvas * result.frameCount <= MAX_ANIMATION_SOURCE_WORK_PIXELS)
    }

    private fun assertNotAnimation(bytes: ByteArray) {
        assertEquals(AnimationSourceAdmission.NotAnimation, admitAnimationSource(bytes))
    }

    private fun assertRefused(
        bytes: ByteArray,
        reason: AnimationSourceRefusal = Malformed,
    ) {
        assertEquals(AnimationSourceAdmission.Refused(reason), admitAnimationSource(bytes))
    }

    private fun assertGif(
        bytes: ByteArray,
        width: Int,
        height: Int,
        frames: Int,
    ) {
        val expected = AnimationSourceAdmission.Admitted(AnimationSourceKind.Gif, width, height, frames)
        assertEquals(expected, admitAnimationSource(bytes))
    }

    private fun assertWebp(
        bytes: ByteArray,
        width: Int,
        height: Int,
        frames: Int,
    ) {
        val expected = AnimationSourceAdmission.Admitted(AnimationSourceKind.Webp, width, height, frames)
        assertEquals(expected, admitAnimationSource(bytes))
    }

    private companion object {
        val MALFORMED = AnimationSourceAdmission.Refused(Malformed)
        const val GIF_SIGNATURE_BYTES = 6
        const val WEBP_SIGNATURE_BYTES = 12
        const val MUTATION_SEED = 3040L
        const val MUTATION_ROUNDS = 4000

        /** 2048 x 2048 is the 4 Mi-pixel canvas maximum; eight such frames reach the 32 Mi-pixel budget. */
        const val MAX_CANVAS_FRAMES = 8
    }
}
