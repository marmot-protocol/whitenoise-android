package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.IDENTITY_IMAGE_SOURCE_MAX_BYTES
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropSource
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.ImageUploadPreparationException
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.loadGroupAttachmentCropSource
import dev.ipf.whitenoise.android.media.renderIdentityImageDraft
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.viewerGroupImageAlreadyCommitted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerGroupPictureSessionTest {
    private val page = MediaViewerPage("album", 1, reference(), false, "sender", 1uL)

    /** Gating is contextual to the current group and the visible media type. */
    @Test
    fun actionIsOnlyForAnEditableImageAndCurrentMemberAdmin() {
        assertTrue(canSetViewerGroupPicture(false, true, true, true, page))
        assertFalse(canSetViewerGroupPicture(true, true, true, true, page))
        assertFalse(canSetViewerGroupPicture(false, false, true, true, page))
        assertFalse(canSetViewerGroupPicture(false, true, false, true, page))
        assertFalse(canSetViewerGroupPicture(false, true, true, false, page))
        val video = page.copy(reference = reference().copy(mediaType = "video/mp4"))
        assertFalse(canSetViewerGroupPicture(false, true, true, true, video))
    }

    /** The selected second attachment stays bound through paging and duplicate taps; cancel never commits. */
    @Test
    fun pendingReadKeepsCapturedGalleryPageAndCancelIsSideEffectFree() =
        runTest {
            val session = ViewerGroupPictureSession({ true }, { true })
            val pending = CompletableDeferred<IdentityImageCropSource>()
            val reads = mutableListOf<MediaViewerPage>()
            val job =
                launch {
                    session.choose(page, {
                        reads += it
                        pending.await()
                    }, { throw it })
                }
            runCurrent()
            assertTrue(session.busy)
            session.choose(page.copy(attachmentIndex = 0), { error("duplicate read") }, { throw it })
            val original = source()
            pending.complete(original)
            job.join()
            assertEquals(listOf(page), reads)
            assertTrue(session.source === original)
            session.cancel()
            session.apply(
                IdentityImageCrop(),
                { _, _ -> error("cancelled render") },
                { _, _ -> error("cancelled commit") },
                { throw it },
            )
            assertNull(session.source)
            assertNull(session.failedDraft)
        }

    /** Permission loss and owner removal reject late read and apply callbacks. */
    @Test
    fun revocationReplacementAndLateCompletionCannotApplyOrShowSuccess() =
        runTest {
            var current = true
            var permitted = true
            val session = ViewerGroupPictureSession({ current }, { permitted })
            session.choose(page, { source() }, { throw it })
            permitted = false
            session.apply(
                IdentityImageCrop(),
                { _, _ -> error("revoked render") },
                { _, _ -> error("revoked commit") },
                { throw it },
            )
            permitted = true
            val completed = CompletableDeferred<Boolean>()
            val job =
                launch {
                    session.apply(IdentityImageCrop(), { _, _ -> draft() }, { _, _ -> completed.await() }, { throw it })
                }
            runCurrent()
            current = false
            session.close()
            completed.complete(true)
            job.join()
            assertNull(session.source)
            assertNull(session.failedDraft)
            assertFalse(session.busy)
            val replacement = ViewerGroupPictureSession({ true }, { true })
            val pending = CompletableDeferred<IdentityImageCropSource>()
            val read = launch { replacement.choose(page, { pending.await() }, { error("stale failure") }) }
            runCurrent()
            replacement.close()
            pending.complete(source())
            read.join()
            assertNull(replacement.source)
        }

    /** Retry uses identical prepared pixels and requires native reconciliation before another primary mutation. */
    @Test
    fun failedCommitRetriesCapturedPixelsWithReconciliationAndNoDuplicateSubmission() =
        runTest {
            val session = ViewerGroupPictureSession({ true }, { true })
            val original = source()
            session.choose(page, { original }, { throw it })
            var commits = 0
            session.apply(
                IdentityImageCrop(),
                { bytes, _ ->
                    assertArrayEquals(original.bytes, bytes)
                    draft()
                },
                { _, reconcile ->
                    assertFalse(reconcile)
                    commits++
                    false
                },
                { throw it },
            )
            val failed = requireNotNull(session.failedDraft)
            val pending = CompletableDeferred<Boolean>()
            val retry =
                launch {
                    session.retry { value, reconcile ->
                        assertTrue(reconcile)
                        assertEquals(failed, value)
                        commits++
                        pending.await()
                    }
                }
            runCurrent()
            session.retry { _, _ -> error("duplicate retry") }
            session.choose(page.copy(attachmentIndex = 0), { error("retarget retry") }, { throw it })
            pending.complete(true)
            retry.join()
            assertEquals(2, commits)
            assertNull(session.failedDraft)
            assertTrue(viewerGroupImageAlreadyCommitted(failed, true) { failed.plaintext.copyOf() })
            assertFalse(viewerGroupImageAlreadyCommitted(failed, false) { error("absent image read") })
            assertFalse(viewerGroupImageAlreadyCommitted(failed, true) { byteArrayOf(9) })
        }

    /** Real private bytes pass the existing crop/metadata/size pipeline; no transport URL becomes an avatar. */
    @Test
    fun originalAttachmentCropUsesBoundedOriginalPixelsAndRejectsUnsupportedInput() =
        runTest {
            val loaded = loadGroupAttachmentCropSource(png(), "image/png")
            val image = renderIdentityImageDraft(loaded.bytes, IdentityImageCrop(), MediaQuality.Standard)
            assertNull(image.sourceUrl)
            assertEquals("image/jpeg", image.mediaType)
            for (bytes in listOf(byteArrayOf(1, 2), ByteArray(IDENTITY_IMAGE_SOURCE_MAX_BYTES + 1))) {
                val error = runCatching { loadGroupAttachmentCropSource(bytes, "image/png") }.exceptionOrNull()
                assertTrue(error is ImageUploadPreparationException)
            }
            val unavailable = ViewerGroupPictureSession({ true }, { true })
            var failures = 0
            unavailable.choose(page, { throw ImageUploadPreparationException.DownloadFailed }, { failures++ })
            assertEquals(1, failures)
            assertNull(unavailable.source)
        }

    /** Builds an in-memory original source rather than a screenshot/thumbnail fixture. */
    private fun source(): IdentityImageCropSource {
        val preview = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        return IdentityImageCropSource(byteArrayOf(1, 2, 3), preview, EditorPixelSize(20, 20))
    }

    private fun draft() = ImageUploadDraft(byteArrayOf(1, 2, 3), "image/jpeg", null, "20x20", null)

    private fun png(): ByteArray =
        ByteArrayOutputStream()
            .also {
                Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()

    private fun reference() =
        MediaAttachmentReferenceFfi(
            emptyList(),
            "aa".repeat(32),
            "bb".repeat(32),
            "cc".repeat(12),
            "photo.png",
            "image/png",
            EncryptedMediaVersionFfi.V1,
            1uL,
            null,
            null,
        )
}
