package dev.ipf.whitenoise.android.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.parseMediaImetaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

@RunWith(AndroidJUnit4::class)
class MediaReferenceSupportFuzzIntegrationTest {
    @Test
    fun packagedMarmotKitPreservesValidOrderAndOmitsMalformedTags() {
        val tags =
            listOf(
                MessageTagFfi(listOf("p", "not-media")),
                validTag(EncryptedMediaVersionFfi.V1, marker = 1),
                MessageTagFfi(listOf("imeta", "v encrypted-media-v1")),
                validTag(EncryptedMediaVersionFfi.V2, marker = 2),
            )

        val parsed = MediaReferenceSupport.parseAllImetaTags(tags, SOURCE_EPOCH)

        assertEquals(2, parsed.size)
        assertEquals(listOf("fixture-1.bin", "fixture-2.bin"), parsed.map { it.fileName })
        assertEquals(listOf(EncryptedMediaVersionFfi.V1, EncryptedMediaVersionFfi.V2), parsed.map { it.version })
        assertTrue(parsed.all { it.sourceEpoch == SOURCE_EPOCH })
    }

    @Test
    fun packagedMarmotKitSurvivesTenThousandBoundedSyntheticTagLists() {
        val random = Random(FUZZ_SEED)
        var nativeInvocations = 0
        val exercisedShapes = mutableSetOf<TagShape>()
        val exercisedMutations = mutableSetOf<TagMutation>()

        repeat(LOCAL_ITERATIONS) { iteration ->
            val tags = syntheticTags(random, iteration, exercisedShapes, exercisedMutations)
            check(tags.size <= MAX_TAGS)
            check(tags.all { it.values.size <= MAX_VALUES_PER_TAG })

            val expected =
                tags.mapNotNull { tag ->
                    if (tag.values.firstOrNull() != "imeta") return@mapNotNull null
                    nativeInvocations++
                    parseDirectlyOrNull(tag, SOURCE_EPOCH)
                }
            val actual = MediaReferenceSupport.parseAllImetaTags(tags, SOURCE_EPOCH)

            assertEquals("MarmotKit verdict/order drift at synthetic iteration $iteration", expected, actual)
        }

        assertTrue("The native MarmotKit parser was not invoked", nativeInvocations > 0)
        assertEquals("Synthetic media shape coverage drifted", TagShape.entries.toSet(), exercisedShapes)
        assertEquals("Synthetic media mutation coverage drifted", TagMutation.entries.toSet(), exercisedMutations)
    }

    private fun syntheticTags(
        random: Random,
        iteration: Int,
        exercisedShapes: MutableSet<TagShape>,
        exercisedMutations: MutableSet<TagMutation>,
    ): List<MessageTagFfi> {
        val forcedMutation = forcedMutation(iteration)
        val count =
            when (iteration) {
                0 -> 0
                1 -> MAX_TAGS
                in 2..TagShape.entries.size -> random.nextInt(8) + 1
                in MUTATION_ITERATION_START until MUTATION_ITERATION_END -> random.nextInt(8) + 1
                else -> random.nextInt(9)
            }
        return List(count) { index ->
            val shape =
                when {
                    index == 0 && iteration in 1..TagShape.entries.size -> TagShape.entries[iteration - 1]
                    index == 0 && forcedMutation != null -> TagShape.MutatedV1
                    else -> TagShape.entries[random.nextInt(TagShape.entries.size)]
                }
            exercisedShapes += shape
            syntheticTag(
                random = random,
                iteration = iteration,
                index = index,
                shape = shape,
                forcedMutation = forcedMutation.takeIf { index == 0 },
                exercisedMutations = exercisedMutations,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive dispatcher for the named synthetic wire-shape corpus.
    private fun syntheticTag(
        random: Random,
        iteration: Int,
        index: Int,
        shape: TagShape,
        forcedMutation: TagMutation?,
        exercisedMutations: MutableSet<TagMutation>,
    ): MessageTagFfi =
        when (shape) {
            TagShape.ValidV1 -> validTag(EncryptedMediaVersionFfi.V1, marker(iteration, index))
            TagShape.ValidV2 -> validTag(EncryptedMediaVersionFfi.V2, marker(iteration, index))
            TagShape.Empty -> MessageTagFfi(emptyList())
            TagShape.Truncated -> MessageTagFfi(listOf("imeta"))
            TagShape.MixedNonImeta -> MessageTagFfi(listOf("e", randomText(random, 32)))
            TagShape.MutatedV1 ->
                mutateValidTag(
                    tag = validTag(EncryptedMediaVersionFfi.V1, marker(iteration, index)),
                    mutation = forcedMutation ?: TagMutation.entries[random.nextInt(TagMutation.entries.size)],
                    exercisedMutations = exercisedMutations,
                )
            TagShape.MutatedV2 ->
                mutateValidTag(
                    tag = validTag(EncryptedMediaVersionFfi.V2, marker(iteration, index)),
                    mutation = forcedMutation ?: TagMutation.entries[random.nextInt(TagMutation.entries.size)],
                    exercisedMutations = exercisedMutations,
                )
            TagShape.TruncatedLocator ->
                MessageTagFfi(
                    listOf(
                        "imeta",
                        "v encrypted-media-v1",
                        "locator blossom-v1 https://blossom.example/invalid",
                    ),
                )
            TagShape.MaxValues ->
                MessageTagFfi(List(MAX_VALUES_PER_TAG) { value -> if (value == 0) "imeta" else "field-$value" })
            TagShape.OptionalV1 ->
                validTag(
                    EncryptedMediaVersionFfi.V1,
                    marker(iteration, index),
                    includeOptionalFields = true,
                )
            TagShape.OptionalV2 ->
                validTag(
                    EncryptedMediaVersionFfi.V2,
                    marker(iteration, index),
                    includeOptionalFields = true,
                )
            TagShape.DuplicateFields -> {
                val tag = validTag(EncryptedMediaVersionFfi.V1, marker(iteration, index))
                MessageTagFfi(tag.values + tag.values.drop(1))
            }
            TagShape.OversizedField -> MessageTagFfi(listOf("imeta", randomText(random, 4_096)))
            TagShape.ArbitraryValues ->
                MessageTagFfi(List(random.nextInt(MAX_VALUES_PER_TAG + 1)) { randomText(random, 96) })
        }

    private fun mutateValidTag(
        tag: MessageTagFfi,
        mutation: TagMutation,
        exercisedMutations: MutableSet<TagMutation>,
    ): MessageTagFfi {
        exercisedMutations += mutation
        val values = tag.values.toMutableList()
        when (mutation) {
            TagMutation.Version -> values[1] = "v encrypted-media-v3"
            TagMutation.Locator -> values[2] = "locator blossom-v1 http://127.0.0.1/blob"
            TagMutation.CiphertextHash -> values[3] = "ciphertext_sha256 ${"0".repeat(63)}"
            TagMutation.PlaintextHash -> values[4] = "plaintext_sha256 ${"z".repeat(64)}"
            TagMutation.Nonce -> values[5] = "nonce ${"0".repeat(22)}"
            TagMutation.Mime -> values[6] = "m Text/Plain"
            TagMutation.FileName -> values[7] = "filename "
            TagMutation.Dimension -> values += "dim 0x999999999"
            TagMutation.Thumbhash -> values += "thumbhash not-base64!"
        }
        return MessageTagFfi(values)
    }

    private fun forcedMutation(iteration: Int): TagMutation? =
        (iteration - MUTATION_ITERATION_START)
            .takeIf { it in TagMutation.entries.indices }
            ?.let(TagMutation.entries::get)

    private fun validTag(
        version: EncryptedMediaVersionFfi,
        marker: Int,
        includeOptionalFields: Boolean = false,
    ): MessageTagFfi {
        val ciphertextHash = markerByte(marker).repeat(32)
        val plaintextHash = markerByte(marker + 1).repeat(32)
        val nonce = markerByte(marker + 2).repeat(12)
        val values =
            mutableListOf(
                "imeta",
                "v ${if (version == EncryptedMediaVersionFfi.V1) "encrypted-media-v1" else "encrypted-media-v2"}",
                "locator blossom-v1 https://blossom.example/$ciphertextHash",
                "ciphertext_sha256 $ciphertextHash",
                "plaintext_sha256 $plaintextHash",
                "nonce $nonce",
                "m application/octet-stream",
                "filename fixture-$marker.bin",
            )
        if (includeOptionalFields) {
            values += "dim 320x240"
            values += "thumbhash 1QcSHQRnh493V4dIh4eXh1h4kJUI"
        }
        return MessageTagFfi(values)
    }

    private fun parseDirectlyOrNull(
        tag: MessageTagFfi,
        sourceEpoch: ULong,
    ): MediaAttachmentReferenceFfi? =
        try {
            parseMediaImetaTag(tag, sourceEpoch)
        } catch (_: MarmotKitException) {
            null
        }

    private fun marker(
        iteration: Int,
        index: Int,
    ): Int = ((iteration * 31L + index) % 250L).toInt() + 1

    private fun markerByte(marker: Int): String = (marker % 256).toString(16).padStart(2, '0')

    private fun randomText(
        random: Random,
        maxLength: Int,
    ): String {
        val length = random.nextInt(maxLength + 1)
        return buildString(length) {
            repeat(length) {
                append(RANDOM_ALPHABET[random.nextInt(RANDOM_ALPHABET.length)])
            }
        }
    }

    private companion object {
        const val LOCAL_ITERATIONS = 10_000
        const val MAX_TAGS = 64
        const val MAX_VALUES_PER_TAG = 64
        const val FUZZ_SEED = 0x2117_4D45_4449_41L
        const val SOURCE_EPOCH = 2117uL
        const val RANDOM_ALPHABET = "imeta vlocrsphfnd0123456789:/._-☃"
        val MUTATION_ITERATION_START = TagShape.entries.size + 1
        val MUTATION_ITERATION_END = MUTATION_ITERATION_START + TagMutation.entries.size
    }

    private enum class TagShape {
        ValidV1,
        ValidV2,
        Empty,
        Truncated,
        MixedNonImeta,
        MutatedV1,
        MutatedV2,
        TruncatedLocator,
        MaxValues,
        OptionalV1,
        OptionalV2,
        DuplicateFields,
        OversizedField,
        ArbitraryValues,
    }

    private enum class TagMutation {
        Version,
        Locator,
        CiphertextHash,
        PlaintextHash,
        Nonce,
        Mime,
        FileName,
        Dimension,
        Thumbhash,
    }
}
