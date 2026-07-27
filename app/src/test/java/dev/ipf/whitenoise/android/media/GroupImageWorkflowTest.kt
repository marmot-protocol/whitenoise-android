package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupImageWorkflowTest {
    @Test
    fun initialGroupImageKeepsEncryptedBytesButNeverPublishesLegacySourceUrl() {
        val bytes = byteArrayOf(1, 2, 3)
        val initial =
            ImageUploadDraft(
                plaintext = bytes,
                mediaType = "image/jpeg",
                sourceUrl = "https://example.com/source.jpg",
                dim = "320x240",
                thumbhash = "hash",
            ).initialGroupImage()

        assertArrayEquals(bytes, initial.plaintext)
        assertEquals("image/jpeg", initial.mediaType)
        assertEquals("320x240", initial.dim)
        assertEquals("hash", initial.thumbhash)
        assertNull(initial.sourceUrl)
    }

    @Test
    fun mutationKeyIsStableForRetriesAndChangesWithTheRequestedImage() {
        val first =
            ImageUploadDraft(
                plaintext = byteArrayOf(1, 2, 3),
                mediaType = "image/jpeg",
                sourceUrl = null,
                dim = null,
                thumbhash = null,
            )
        val retry = first.copy(plaintext = first.plaintext.copyOf())
        val replacement = first.copy(plaintext = byteArrayOf(1, 2, 4))

        assertEquals(first.mutationKey(), retry.mutationKey())
        assertNotEquals(first.mutationKey(), replacement.mutationKey())
        assertNotEquals(REMOVE_GROUP_IMAGE_MUTATION_KEY, first.mutationKey())
    }

    @Test
    fun uploadDraftEqualityUsesByteContent() {
        val first =
            ImageUploadDraft(
                plaintext = byteArrayOf(1, 2, 3),
                mediaType = "image/jpeg",
                sourceUrl = "https://example.com/image.jpg",
                dim = "320x240",
                thumbhash = "hash",
            )
        val sameContent = first.copy(plaintext = first.plaintext.copyOf())

        assertEquals(first, sameContent)
        assertEquals(first.hashCode(), sameContent.hashCode())
    }

    @Test
    fun primaryMutationRetryDecisionDistinguishesReplacementAndRemoval() {
        val firstUpload = "upload:first"

        assertEquals(
            false,
            shouldCommitPrimaryGroupImageMutation(
                requestedMutationKey = firstUpload,
                pendingLegacyClearMutationKey = firstUpload,
                hasProjectedEncryptedImage = false,
            ),
        )
        assertEquals(
            true,
            shouldCommitPrimaryGroupImageMutation(
                requestedMutationKey = "upload:replacement",
                pendingLegacyClearMutationKey = firstUpload,
                hasProjectedEncryptedImage = false,
            ),
        )
        assertEquals(
            true,
            shouldCommitPrimaryGroupImageMutation(
                requestedMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                pendingLegacyClearMutationKey = firstUpload,
                hasProjectedEncryptedImage = false,
            ),
        )
        assertEquals(
            false,
            shouldCommitPrimaryGroupImageMutation(
                requestedMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                pendingLegacyClearMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                hasProjectedEncryptedImage = true,
            ),
        )
        assertEquals(
            false,
            shouldCommitPrimaryGroupImageMutation(
                requestedMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                pendingLegacyClearMutationKey = null,
                hasProjectedEncryptedImage = false,
            ),
        )
    }

    @Test
    fun cleanupFailureClassificationBelongsToTheCurrentMutation() {
        assertEquals(
            GroupImageMutationFailure.Primary,
            classifyGroupImageMutationFailure(
                requestedMutationKey = "upload:replacement",
                pendingLegacyClearMutationKey = "upload:previous",
                attemptedLegacyClear = false,
            ),
        )
        assertEquals(
            GroupImageMutationFailure.UploadCleanup,
            classifyGroupImageMutationFailure(
                requestedMutationKey = "upload:replacement",
                pendingLegacyClearMutationKey = "upload:replacement",
                attemptedLegacyClear = true,
            ),
        )
        assertEquals(
            GroupImageMutationFailure.RemovalCleanup,
            classifyGroupImageMutationFailure(
                requestedMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                pendingLegacyClearMutationKey = REMOVE_GROUP_IMAGE_MUTATION_KEY,
                attemptedLegacyClear = true,
            ),
        )
    }
}
