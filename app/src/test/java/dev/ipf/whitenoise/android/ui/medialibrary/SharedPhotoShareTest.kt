package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.ui.conversation.media.OUTBOUND_SHARE_MAX_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.OutboundShareTooLargeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Photos selected in Shared Media leave through one share sheet as exactly the selected, still-current set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPhotoShareTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val beach = photo("message-1", 0, "beach.jpg", JPEG)
    private val dunes = photo("message-1", 1, "dunes.png", PNG)
    private val unselectedSibling = photo("message-1", 2, "caption-shot.jpg", JPEG)
    private val harbour = photo("message-2", 0, "harbour.jpg", JPEG)
    private val gallery = listOf(beach, dunes, unselectedSibling, harbour, video("message-3", 0))

    /** Two photos of one message and one of another arrive as three readable grants, without captions or siblings. */
    @Test
    fun photosFromTwoMessagesShareAsOneSetWithNothingElse() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            val read = mutableListOf<SharedMediaTile>()

            shareSelectedPhotos(context, "account", listOf(beach, dunes, harbour), "Share", { gallery }) { photo ->
                read += photo
                AttachmentPlaintext.Bytes(bytesOf(photo))
            }

            assertEquals(listOf(beach, dunes, harbour), read)
            val send = sentIntent()
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals("image/*", send.type)
            assertNull("no caption travels with the photos", send.getStringExtra(Intent.EXTRA_TEXT))
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val streams = requireNotNull(send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java))
            val clip = requireNotNull(send.clipData)
            assertEquals(streams, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
            assertEquals(listOf("beach.jpg", "dunes.png", "harbour.jpg"), streams.map(::displayName))
            streams.zip(listOf(beach, dunes, harbour)).forEach { (uri, photo) ->
                assertArrayEquals(bytesOf(photo), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
            assertEquals(3, sharedFiles().size)
        }

    /** A photo hidden while the set is staged cancels the whole share and deletes every staged file. */
    @Test
    fun aPhotoHiddenDuringStagingSharesNothingAndDeletesTheSet() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            var current = gallery

            val failure =
                runCatching {
                    val selected = listOf(beach, dunes, harbour)
                    shareSelectedPhotos(context, "account", selected, "Share", { current }) { photo ->
                        if (photo == harbour) current = gallery - dunes
                        AttachmentPlaintext.Bytes(bytesOf(photo))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(listOf(dunes), (failure as SharedPhotosUnavailableException).photos)
            assertNoShare()
        }

    /** A selection that no longer resolves when Share is tapped reads nothing at all. */
    @Test
    fun aSelectionUnavailableAtActivationReadsNothing() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            var reads = 0

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery - harbour }) {
                        reads += 1
                        AttachmentPlaintext.Bytes(bytesOf(it))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(0, reads)
            assertNoShare()
        }

    /** A set over the existing share cache budget is refused before the oversized photo is read or written. */
    @Test
    fun aSetOverTheStagingBudgetSharesNothing() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            var oversizedCopied = false
            var oversizedClosed = false
            val oversized =
                object : AttachmentPlaintext {
                    override val size = OUTBOUND_SHARE_MAX_BYTES

                    override fun copyTo(output: OutputStream) {
                        oversizedCopied = true
                    }

                    override fun close() {
                        oversizedClosed = true
                    }
                }

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) { photo ->
                        if (photo == harbour) oversized else AttachmentPlaintext.Bytes(bytesOf(photo))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is OutboundShareTooLargeException)
            assertFalse(oversizedCopied)
            assertTrue(oversizedClosed)
            assertNoShare()
        }

    /** A photo whose verified source cannot be read fails the set, naming that photo, and leaves nothing staged. */
    @Test
    fun anUnavailableSourceSharesNothing() =
        runTest {
            clearSharedFiles()
            registerShareTarget()

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) { photo ->
                        if (photo == harbour) throw IOException("blob missing")
                        AttachmentPlaintext.Bytes(bytesOf(photo))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(listOf(harbour), (failure as SharedPhotosUnavailableException).photos)
            assertTrue(failure.unreadable)
            assertEquals("blob missing", failure.cause?.message)
            assertNoShare()
        }

    /** A streamed photo that fails while it is copied is named as unavailable, as a failed read is. */
    @Test
    fun aStreamFailingMidCopyNamesItsPhoto() =
        runTest {
            clearSharedFiles()
            registerShareTarget()

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) { photo ->
                        if (photo != harbour) return@shareSelectedPhotos AttachmentPlaintext.Bytes(bytesOf(photo))
                        object : AttachmentPlaintext {
                            override val size = 4L

                            /** Fails as a dropped download stream does. */
                            override fun copyTo(output: OutputStream) = throw IOException("stream dropped")

                            /** Holds nothing to release. */
                            override fun close() = Unit
                        }
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(listOf(harbour), (failure as SharedPhotosUnavailableException).photos)
            assertTrue(failure.unreadable)
            assertNoShare()
        }

    /** A photo whose cached file is reclaimed after it was acquired cannot be moved or read, and is named. */
    @Test
    fun aReclaimedPhotoLeaseNamesItsPhoto() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            val leaseDirectory = File(context.cacheDir, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES).apply { mkdirs() }

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) { photo ->
                        if (photo != harbour) return@shareSelectedPhotos AttachmentPlaintext.Bytes(bytesOf(photo))
                        val file = File(leaseDirectory, "harbour.lease").apply { writeBytes(bytesOf(photo)) }
                        AttachmentPlaintext.Lease(DiskByteCacheLease(file)).also { file.delete() }
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(listOf(harbour), (failure as SharedPhotosUnavailableException).photos)
            assertTrue(failure.unreadable)
            assertNoShare()
        }

    /** A photo removed while it is read fails its read; the share reports it unavailable so it can be deselected. */
    @Test
    fun aReadFailureForARemovedPhotoReportsItUnavailable() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            var current = gallery
            val readFailure = IOException("blob deleted")

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { current }) { photo ->
                        if (photo == harbour) {
                            current = gallery - harbour
                            throw readFailure
                        }
                        AttachmentPlaintext.Bytes(bytesOf(photo))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            assertEquals(listOf(harbour), (failure as SharedPhotosUnavailableException).photos)
            assertTrue(readFailure in failure.suppressed)
            assertNoShare()
        }

    /** A photo removed after the final check read its page keeps the share sheet closed and deletes the staged set. */
    @Test
    fun aRemovalDuringTheFinalCheckSharesNothing() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            val rows = (3 downTo 1).map { historyEntry(it) }
            val source = GroupAttachmentFixture(rows)
            val resolve = GroupAttachmentPager(source).selectedPhotoResolver { "account" }
            val selected = attachmentLibraryTiles(rows, "account").visuals
            // Version reads 1–2 are the activation check, 3 is the final check's baseline and 4 its recheck after
            // projection, so the removal lands after that check read the photo's page.
            source.beforeVersion = {
                if (source.versionCalls == 4) {
                    source.rows = rows - rows[0]
                    source.removals++
                }
            }

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", selected, "Share", { resolve(selected) }) {
                        AttachmentPlaintext.Bytes(bytesOf(it))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is SharedPhotosUnavailableException)
            val removed = attachmentLibraryTiles(listOf(rows[0]), "account").visuals
            assertEquals(removed, (failure as SharedPhotosUnavailableException).photos)
            assertNoShare()
        }

    /** One selected photo is sent as a single stream. */
    @Test
    fun aSingleSelectedPhotoIsSentAlone() =
        runTest {
            clearSharedFiles()
            registerShareTarget(Intent.ACTION_SEND, JPEG)

            shareSelectedPhotos(context, "account", listOf(harbour), "Share", { gallery }) {
                AttachmentPlaintext.Bytes(bytesOf(it))
            }

            val send = sentIntent()
            assertEquals(Intent.ACTION_SEND, send.action)
            val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals("harbour.jpg", displayName(uri))
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        }

    /** A gallery that lost its account or conversation while staging cancels silently and deletes the set. */
    @Test
    fun aGalleryThatLostOwnershipCancelsBeforeLaunch() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            var owned = true

            val failure =
                runCatching {
                    val current: suspend () -> List<SharedMediaTile>? = { gallery.takeIf { owned } }
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", current) {
                        owned = false
                        AttachmentPlaintext.Bytes(bytesOf(it))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is CancellationException)
            assertNoShare()
        }

    /** Closing the gallery cancels staging in progress; nothing launches and nothing staged survives. */
    @Test
    fun closingTheGalleryCancelsStaging() =
        runTest {
            clearSharedFiles()
            registerShareTarget()
            val secondRead = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val sharing =
                async {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) { photo ->
                        if (photo == harbour) {
                            secondRead.complete(Unit)
                            release.await()
                        }
                        AttachmentPlaintext.Bytes(bytesOf(photo))
                    }
                }
            secondRead.await()
            assertEquals(1, sharedFiles().size)
            sharing.cancel()
            val failure = runCatching { sharing.await() }.exceptionOrNull()

            assertTrue("$failure", failure is CancellationException)
            assertNoShare()
        }

    /** With no app to receive photos, nothing is read or staged. */
    @Test
    fun withNoShareTargetNoPhotoIsRead() =
        runTest {
            clearSharedFiles()
            var reads = 0

            val failure =
                runCatching {
                    shareSelectedPhotos(context, "account", listOf(beach, harbour), "Share", { gallery }) {
                        reads += 1
                        AttachmentPlaintext.Bytes(bytesOf(it))
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is ActivityNotFoundException)
            assertEquals(0, reads)
            assertTrue(sharedFiles().isEmpty())
        }

    /** A slot is unavailable once it is gone, became a video or carries another reference; fresh metadata is not. */
    @Test
    fun slotsResolveBySlotAndReference() {
        val changed = dunes.copy(reference = dunes.reference.copy(plaintextSha256 = "d".repeat(64)))
        val becameVideo = harbour.copy(isVideo = true)
        val refreshed = beach.copy(recordedAt = 99uL)

        assertEquals(
            listOf(dunes, harbour, unselectedSibling),
            unavailableSelectedPhotos(
                listOf(beach, dunes, harbour, unselectedSibling),
                listOf(refreshed, changed, becameVideo),
            ),
        )
        assertTrue(unavailableSelectedPhotos(listOf(beach, harbour), gallery).isEmpty())
    }

    /** Selection keeps pick order, ignores videos, and ends when its last photo is deselected. */
    @Test
    fun selectionTogglesPhotosOnly() {
        val selection = SharedPhotoSelection()
        assertFalse(selection.active)

        selection.toggle(harbour)
        selection.toggle(beach)
        selection.toggle(video("message-3", 0))
        selection.toggle(dunes)
        assertEquals(listOf(harbour, beach, dunes), selection.photos)
        assertTrue(selection.isSelected(beach.copy(recordedAt = 99uL)))

        selection.toggle(beach)
        selection.deselect(listOf(dunes))
        assertEquals(listOf(harbour), selection.photos)
        selection.toggle(harbour)
        assertFalse(selection.active)

        selection.toggle(beach)
        selection.clear()
        assertEquals(0, selection.count)
    }

    /** The gallery's own Back ends selection mode first and only then leaves, as system Back does. */
    @Test
    fun topBarBackEndsSelectionBeforeLeaving() {
        val share = SharedPhotoShare()
        var left = 0
        val back = share.backOr { left += 1 }

        share.selection.toggle(beach)
        back()
        assertFalse(share.selection.active)
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }

    private fun assertNoShare() {
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
    }

    private fun sentIntent(): Intent {
        val chooser = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        return requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
    }

    private fun registerShareTarget(
        action: String = Intent.ACTION_SEND_MULTIPLE,
        mediaType: String = "image/*",
    ) {
        val application = ApplicationInfo().apply { packageName = SHARE_TARGET_PACKAGE }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(action).setType(mediaType),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = SHARE_TARGET_PACKAGE
                        name = "$SHARE_TARGET_PACKAGE.ShareActivity"
                        exported = true
                        applicationInfo = application
                    }
            },
        )
    }

    private fun bytesOf(photo: SharedMediaTile) = "${photo.messageIdHex}#${photo.attachmentIndex}".encodeToByteArray()

    private fun photo(
        messageIdHex: String,
        attachmentIndex: Int,
        fileName: String,
        mediaType: String,
    ) = SharedMediaTile(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference =
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://cdn.example.test/$fileName")),
                ciphertextSha256 = "a".repeat(64),
                plaintextSha256 = "b".repeat(64),
                nonceHex = "c".repeat(48),
                fileName = fileName,
                mediaType = mediaType,
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            ),
        mine = false,
        recordedAt = 1uL,
        sender = "sender",
        isVideo = false,
    )

    private fun video(
        messageIdHex: String,
        attachmentIndex: Int,
    ) = photo(messageIdHex, attachmentIndex, "clip.mp4", "video/mp4").copy(isVideo = true)

    private fun displayName(uri: Uri): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }

    private fun clearSharedFiles() {
        sharedFiles().forEach(File::delete)
    }

    private fun sharedFiles(): List<File> =
        File(context.cacheDir, MediaCacheDirs.SHARED)
            .listFiles()
            ?.filter(File::isFile)
            .orEmpty()

    private companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val SHARE_TARGET_PACKAGE = "com.example.photos"
    }
}
