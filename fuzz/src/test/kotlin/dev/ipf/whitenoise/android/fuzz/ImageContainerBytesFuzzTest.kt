package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.AnimationSourceKind
import dev.ipf.whitenoise.android.media.ImageContainerKind
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_CANVAS_PIXELS
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_EDGE_PX
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_FRAMES
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_WORK_PIXELS
import dev.ipf.whitenoise.android.media.admitAnimationSource
import dev.ipf.whitenoise.android.media.imageContainerKind
import dev.ipf.whitenoise.android.media.stripGifMetadata
import dev.ipf.whitenoise.android.media.stripImageContainerMetadata
import dev.ipf.whitenoise.android.media.stripJpegMetadata
import dev.ipf.whitenoise.android.media.stripPngMetadata
import dev.ipf.whitenoise.android.media.stripWebpMetadata
import org.junit.jupiter.api.Tag

/** Fuzzes all Android-free image metadata walkers and animation admission with bounded provider-controlled bytes. */
@Tag("fuzz-image-container")
class ImageContainerBytesFuzzTest {
    /** Lets uncaught parser failures reach Jazzer while asserting successful-output invariants. */
    @DictionaryEntries(
        "hex:", "RIFF", "WEBP", "GIF87a", "GIF89a", "IEND", "EXIF", "XMP ",
        "VP8X", "ANIM", "ANMF", "ALPH", "VP8L",
    )
    @DictionaryFile(resourcePath = "/fuzz-grammar.dict")
    @FuzzTest
    fun fuzzImageContainerBytes(data: FuzzedDataProvider) {
        data.consumeSubtarget(ImageContainerSubtarget.COUNT)
        val raw = data.consumeRemainingAsBytes()
        val bounded = if (raw.size <= MAX_CONTAINER_BYTES) raw else raw.copyOf(MAX_CONTAINER_BYTES)
        exerciseImageContainer(imageContainerFuzzInput(bounded))
    }

    /** Runs every walker and verifies that accepted output stays bounded, stable, and same-kind. */
    private fun exerciseImageContainer(bytes: ByteArray) {
        val sourceKind = imageContainerKind(bytes)
        val directResults =
            listOf(
                ImageContainerKind.Jpeg to stripJpegMetadata(bytes),
                ImageContainerKind.Png to stripPngMetadata(bytes),
                ImageContainerKind.Webp to stripWebpMetadata(bytes),
                ImageContainerKind.Gif to stripGifMetadata(bytes),
            )

        directResults.forEach { (walkerKind, result) ->
            if (walkerKind != sourceKind) {
                FuzzAssertions.assertNull("a mismatched walker must reject the container", result)
            }
            if (result != null) {
                FuzzAssertions.assertEquals(
                    "accepted output must preserve its container kind",
                    walkerKind,
                    imageContainerKind(result),
                )
                FuzzAssertions.assertTrue("metadata removal must not expand its source", result.size <= bytes.size)
                FuzzAssertions.assertTrue(
                    "metadata removal must be idempotent",
                    result.contentEquals(stripImageContainerMetadata(result)),
                )
            }
        }

        val dispatched = stripImageContainerMetadata(bytes)
        val direct = directResults.firstOrNull { it.first == sourceKind }?.second
        FuzzAssertions.assertTrue(
            "the production dispatcher must match the positively identified walker",
            dispatched?.contentEquals(direct) ?: (direct == null),
        )
        exerciseAnimationAdmission(bytes, sourceKind)
    }

    /** Runs the exact production animation admission walk and checks its content-derived limits. */
    private fun exerciseAnimationAdmission(
        bytes: ByteArray,
        sourceKind: ImageContainerKind?,
    ) {
        val admission = admitAnimationSource(bytes)
        FuzzAssertions.assertEquals("admission must be deterministic", admission, admitAnimationSource(bytes))
        if (sourceKind == ImageContainerKind.Gif) {
            FuzzAssertions.assertTrue(
                "a recognized GIF must be admitted or refused, never treated as a still",
                admission != AnimationSourceAdmission.NotAnimation,
            )
        }
        if (admission !is AnimationSourceAdmission.Admitted) return
        val expectedKind =
            if (sourceKind == ImageContainerKind.Gif) AnimationSourceKind.Gif else AnimationSourceKind.Webp
        FuzzAssertions.assertTrue(
            "only GIF or WebP content may be admitted",
            sourceKind == ImageContainerKind.Gif || sourceKind == ImageContainerKind.Webp,
        )
        FuzzAssertions.assertEquals("admitted kind must match the container signature", expectedKind, admission.kind)
        val canvas = admission.canvasWidth.toLong() * admission.canvasHeight.toLong()
        FuzzAssertions.assertTrue(
            "admitted canvas must stay inside the edge and area limits",
            admission.canvasWidth in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
                admission.canvasHeight in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
                canvas <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS,
        )
        FuzzAssertions.assertTrue(
            "admitted frames must stay inside the frame and aggregate work limits",
            admission.frameCount in 1..MAX_ANIMATION_SOURCE_FRAMES &&
                canvas * admission.frameCount <= MAX_ANIMATION_SOURCE_WORK_PIXELS,
        )
        FuzzAssertions.assertTrue(
            "a truncated admitted source must not be admitted",
            admitAnimationSource(bytes.copyOf(bytes.size - 1)) !is AnimationSourceAdmission.Admitted,
        )
    }

    private companion object {
        const val MAX_CONTAINER_BYTES = 65_536
    }
}

/** Decodes reviewable `hex:` corpus seeds; all other mutations remain arbitrary raw bytes. */
internal fun imageContainerFuzzInput(input: ByteArray): ByteArray {
    if (input.size < HEX_PREFIX.size || !HEX_PREFIX.indices.all { input[it] == HEX_PREFIX[it] }) return input
    var end = input.size
    while (end > HEX_PREFIX.size && input[end - 1].toInt().toChar().isWhitespace()) end--
    val digitCount = end - HEX_PREFIX.size
    if (digitCount == 0 || digitCount % 2 != 0) return input
    val decoded = ByteArray(digitCount / 2)
    decoded.indices.forEach { index ->
        val high = hexNibble(input[HEX_PREFIX.size + index * 2]) ?: return input
        val low = hexNibble(input[HEX_PREFIX.size + index * 2 + 1]) ?: return input
        decoded[index] = ((high shl 4) or low).toByte()
    }
    return decoded
}

/** Maps one ASCII hexadecimal digit without accepting locale-sensitive characters. */
private fun hexNibble(value: Byte): Int? =
    when (val unsigned = value.toInt() and 0xff) {
        in '0'.code..'9'.code -> unsigned - '0'.code
        in 'a'.code..'f'.code -> unsigned - 'a'.code + 10
        in 'A'.code..'F'.code -> unsigned - 'A'.code + 10
        else -> null
    }

private val HEX_PREFIX = "hex:".encodeToByteArray()
