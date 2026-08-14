package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FilterOutputStream
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaAttachmentSaveTest {
    private lateinit var outputFile: File
    private lateinit var provider: RecordingMediaProvider

    @Before
    fun setUp() {
        outputFile = File.createTempFile("media-save", ".bin")
        provider = RecordingMediaProvider(outputFile, context().contentResolver)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
        outputFile.delete()
    }

    @Test
    fun imageAttachmentSavesToPicturesCollection() {
        val saved = saveAttachmentToMediaStore(context(), PAYLOAD, "photo.png", "IMAGE/PNG")

        assertTrue(saved)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, provider.insertedCollection)
    }

    @Test
    fun videoAttachmentSavesToMoviesCollection() {
        val saved = saveAttachmentToMediaStore(context(), PAYLOAD, "clip.mp4", "video/mp4")

        assertTrue(saved)
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, provider.insertedCollection)
    }

    @Test
    fun fileAttachmentSavesSanitizedBytesToDownloadsCollection() {
        val saved = saveAttachmentToMediaStore(context(), PAYLOAD, "../report.pdf", "application/pdf")

        assertTrue(saved)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.insertedCollection)
        assertEquals("report.pdf", provider.insertedValues?.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("application/pdf", provider.insertedValues?.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download/White Noise", provider.insertedValues?.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, provider.insertedValues?.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(0, provider.updatedValues?.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertArrayEquals(PAYLOAD, outputFile.readBytes())
    }

    @Test
    fun materializedFileStreamsToDownloadsCollection() {
        val source = File.createTempFile("materialized-attachment", ".apk")
        try {
            source.writeBytes(PAYLOAD)

            val saved =
                saveDocumentToDownloads(
                    context(),
                    source,
                    "agent-build.apk",
                    "application/vnd.android.package-archive",
                )

            assertTrue(saved)
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.insertedCollection)
            assertEquals("agent-build.apk", provider.insertedValues?.getAsString(MediaStore.Downloads.DISPLAY_NAME))
            assertArrayEquals(PAYLOAD, outputFile.readBytes())
        } finally {
            source.delete()
        }
    }

    @Test
    fun documentFallbackIntentUsesSafeNameAndOriginalMime() {
        val intent = createDocumentIntent("../agent-build.apk", ANDROID_PACKAGE_MIME)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertEquals(ANDROID_PACKAGE_MIME, intent.type)
        assertEquals("agent-build.apk", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun packageInstallPermissionIntentReturnsThroughActivityResult() {
        val context = context()
        val intent = androidPackageInstallPermissionIntent(context)

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun documentSaveUsesSelectedDestinationAfterMediaStoreFailure() =
        runTest {
            val source = File.createTempFile("materialized-attachment", ".apk")
            provider.outputFailure = java.io.IOException("provider unavailable")
            var selectedSource: File? = null
            try {
                source.writeBytes(PAYLOAD)

                val saved =
                    saveDocumentWithFallback(
                        context = context(),
                        source = source,
                        fileName = "agent-build.apk",
                        mediaType = ANDROID_PACKAGE_MIME,
                        fallback = { fallbackSource, _, _ -> selectedSource = fallbackSource },
                    )

                assertTrue(saved)
                assertSame(source, selectedSource)
                assertEquals(1, provider.deleteCount)
            } finally {
                source.delete()
            }
        }

    @Test
    fun mediaStoreOpenFailureIsPreservedForDiagnosticPresentation() {
        val openFailure = java.io.IOException("credential-bearing content://secret must not reach UI")
        provider.outputFailure = openFailure

        val failure =
            runCatching {
                saveAttachmentToMediaStore(context(), PAYLOAD, "private-name.png", "image/png")
            }.exceptionOrNull()

        assertTrue(failure is AttachmentSaveException)
        assertEquals(AttachmentSaveStage.MEDIASTORE_OPEN, (failure as AttachmentSaveException).stage)
        assertSame(openFailure, failure.cause)
    }

    @Test
    fun mediaStoreWriteFailureReportsWriteStage() {
        val writeFailure = java.io.IOException("local write failed")
        provider.writeFailure = writeFailure

        val failure =
            runCatching {
                saveAttachmentToMediaStore(context(), PAYLOAD, "private-name.png", "image/png")
            }.exceptionOrNull()

        assertTrue(failure is AttachmentSaveException)
        assertEquals(AttachmentSaveStage.MEDIASTORE_WRITE, (failure as AttachmentSaveException).stage)
        assertSame(writeFailure, failure.cause)
    }

    @Test
    fun documentDestinationClosesWhenSourceCannotOpen() {
        val destination =
            context().contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues(),
            ) ?: error("test provider did not return a destination")
        val missingSource = File(outputFile.parentFile, "missing-source-${System.nanoTime()}")

        val failure =
            runCatching {
                copyDocumentToDestination(context(), missingSource, destination)
            }.exceptionOrNull()

        assertTrue(failure is AttachmentSaveException)
        assertEquals(AttachmentSaveStage.DOCUMENT_DESTINATION_WRITE, (failure as AttachmentSaveException).stage)
        assertEquals(1, provider.outputCloseCount)
    }

    @Test
    fun mediaStoreZeroRowFinalizationFailsAndDeletesPendingEntry() {
        provider.updateResult = 0

        listOf(
            Triple("photo.png", "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            Triple("clip.mp4", "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            Triple("report.pdf", "application/pdf", MediaStore.Downloads.EXTERNAL_CONTENT_URI),
        ).forEachIndexed { index, (fileName, mediaType, collection) ->
            val failure =
                runCatching {
                    saveAttachmentToMediaStore(context(), PAYLOAD, fileName, mediaType)
                }.exceptionOrNull()

            assertTrue(failure is AttachmentSaveException)
            assertEquals(AttachmentSaveStage.MEDIASTORE_FINALIZE, (failure as AttachmentSaveException).stage)
            assertEquals(collection, provider.insertedCollection)
            assertEquals(index + 1, provider.deleteCount)
        }
    }

    @Test
    fun mediaStoreInsertRetriesOneNullProviderResult() {
        var attempt = 0
        var retries = 0

        val inserted =
            retryNullableMediaStoreInsert(onRetry = { retries++ }) {
                attempt += 1
                if (attempt == 1) null else "content://media/downloads/1"
            }

        assertEquals("content://media/downloads/1", inserted)
        assertEquals(2, attempt)
        assertEquals(1, retries)
    }

    @Test
    fun exhaustedNullMediaStoreInsertReportsStableStage() {
        val failure =
            runCatching {
                retryNullableMediaStoreInsert<String>(attempts = 2, onRetry = {}) { null }
            }.exceptionOrNull()

        assertTrue(failure is AttachmentSaveException)
        assertEquals(AttachmentSaveStage.MEDIASTORE_INSERT, (failure as AttachmentSaveException).stage)
        assertEquals("IO", failure.diagnosticErrorCode)
        assertEquals("stage=MEDIASTORE_INSERT", failure.diagnosticTechnicalDetail)
    }

    @Test(expected = CancellationException::class)
    fun mediaStoreCancellationIsNeverReducedToSaveFailure() {
        provider.outputFailure = CancellationException("cancelled")

        saveAttachmentToMediaStore(context(), PAYLOAD, "private-name.png", "image/png")
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private class RecordingMediaProvider(
        private val outputFile: File,
        private val resolver: ContentResolver,
    ) : ContentProvider() {
        var insertedCollection: Uri? = null
            private set
        var insertedValues: ContentValues? = null
            private set
        var updatedValues: ContentValues? = null
            private set
        var outputFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var updateResult: Int = 1
        var deleteCount: Int = 0
            private set
        var outputCloseCount: Int = 0
            private set

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri {
            insertedCollection = uri
            insertedValues = values?.let(::ContentValues)
            val inserted = uri.buildUpon().appendPath("1").build()
            shadowOf(resolver)
                .registerOutputStreamSupplier(inserted) {
                    outputFailure?.let { throw it }
                    object : FilterOutputStream(outputFile.outputStream()) {
                        override fun write(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            writeFailure?.let { throw it }
                            super.write(buffer, offset, length)
                        }

                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                outputCloseCount += 1
                            }
                        }
                    }
                }
            return inserted
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deleteCount += 1
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            updatedValues = values?.let(::ContentValues)
            return updateResult
        }
    }

    private companion object {
        val PAYLOAD = "attachment bytes".toByteArray()
    }
}
