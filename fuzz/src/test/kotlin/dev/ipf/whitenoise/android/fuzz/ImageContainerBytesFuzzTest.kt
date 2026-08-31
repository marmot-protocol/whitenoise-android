package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.media.ImageContainerKind
import dev.ipf.whitenoise.android.media.imageContainerKind
import dev.ipf.whitenoise.android.media.stripGifMetadata
import dev.ipf.whitenoise.android.media.stripImageContainerMetadata
import dev.ipf.whitenoise.android.media.stripJpegMetadata
import dev.ipf.whitenoise.android.media.stripPngMetadata
import dev.ipf.whitenoise.android.media.stripWebpMetadata
import org.junit.jupiter.api.Tag

/** Fuzzes all Android-free image metadata walkers with bounded provider-controlled bytes. */
@Tag("fuzz-image-container")
class ImageContainerBytesFuzzTest {
    /** Lets uncaught parser failures reach Jazzer while asserting successful-output invariants. */
    @DictionaryEntries("hex:", "RIFF", "WEBP", "GIF87a", "GIF89a", "IEND", "EXIF", "XMP ")
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
                FuzzAssertions.assertEquals("accepted output must preserve its container kind", walkerKind, imageContainerKind(result))
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
