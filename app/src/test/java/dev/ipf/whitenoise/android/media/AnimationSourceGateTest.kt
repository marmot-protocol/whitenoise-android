package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.anim
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.animatedWebp
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.anmf
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gifFrame
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.riff
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8l
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.vp8x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spies on the decoder dispatch used by `decodeMessageAttachmentImage` and the
 * native animated-decoder chokepoint used by `MediaPipeline.decodeAnimatedDrawable`.
 */
class AnimationSourceGateTest {
    private var animatedCalls = 0
    private var stillCalls = 0

    @Test
    fun refusedSourceReachesNeitherDecoder() {
        for (refused in refusedSources()) {
            assertNull(dispatch(refused, animatedResult = "animated", stillResult = "still"))
        }
        assertEquals(0, animatedCalls)
        assertEquals(0, stillCalls)
    }

    @Test
    fun admittedSourceUsesTheAnimatedDecoderWithoutAStill() {
        assertEquals("animated", dispatch(gif(), animatedResult = "animated", stillResult = "still"))
        assertEquals("animated", dispatch(animatedWebp(), animatedResult = "animated", stillResult = "still"))
        assertEquals(2, animatedCalls)
        assertEquals(0, stillCalls)
    }

    @Test
    fun admittedNativeFailureMayFallBackToTheSampledStill() {
        assertEquals("still", dispatch(gif(), animatedResult = null, stillResult = "still"))
        assertEquals(1, animatedCalls)
        assertEquals(1, stillCalls)
    }

    @Test
    fun notAnimationUsesOnlyTheSampledStill() {
        val pngPrefix = PNG_SIGNATURE + ByteArray(16)
        val stillWebp = riff(vp8l(2, 2))
        assertEquals("still", dispatch(pngPrefix, animatedResult = "animated", stillResult = "still"))
        assertEquals("still", dispatch(stillWebp, animatedResult = "animated", stillResult = "still"))
        assertNull(dispatch(ByteArray(0), animatedResult = "animated", stillResult = null))
        assertEquals(0, animatedCalls)
        assertEquals(3, stillCalls)
    }

    @Test
    fun nativeChokepointRunsOnlyForAdmittedSources() {
        var nativeCalls = 0
        val native = { bytes: ByteArray -> decodeAdmittedNativeAnimation(bytes) { "drawable".also { nativeCalls++ } } }
        refusedSources().forEach { assertNull(native(it)) }
        assertNull(native(riff(vp8l(2, 2))))
        assertNull(native(PNG_SIGNATURE + ByteArray(16)))
        assertEquals(0, nativeCalls)
        assertEquals("drawable", native(gif()))
        assertEquals("drawable", native(animatedWebp()))
        assertEquals(2, nativeCalls)
    }

    private fun dispatch(
        bytes: ByteArray,
        animatedResult: String?,
        stillResult: String?,
    ): String? =
        decodeAdmittedAttachmentImage(
            bytes = bytes,
            decodeAnimated = {
                animatedCalls++
                animatedResult
            },
            decodeStill = {
                stillCalls++
                stillResult
            },
        )

    /** One refusal of each kind, independent of whatever MIME type a sender advertised. */
    private fun refusedSources(): List<ByteArray> =
        listOf(
            gif(trailer = false),
            gif(width = MAX_ANIMATION_SOURCE_EDGE_PX + 1, height = 1),
            gif(width = 1, height = 1, frames = List(MAX_ANIMATION_SOURCE_FRAMES + 1) { gifFrame() }),
            gif(width = 2048, height = 2048, frames = List(9) { gifFrame() }),
            animatedWebp().copyOf(40),
            riff(vp8x(1, 1, 0), vp8l(1, 1), anim(), anmf()),
        )
}
