package dev.ipf.whitenoise.android.ui.group

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupAvatarUploadAttemptTest {
    private val draft = ImageUploadDraft(byteArrayOf(1), "image/jpeg", null, null, null)
    private val url = "https://blossom.example/image.jpg"

    @Test
    fun realJpegAndPngPreparationReachUploadUnchangedAfterRefusal() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
                val source = rasterBytes(format)
                val file = File.createTempFile("avatar-raster-", ".image", context.cacheDir)
                try {
                    file.writeBytes(source)
                    val picked = Uri.fromFile(file)
                    val expected = GroupImageDraftProcessor.fromBytes(source, null)
                    val expectedHash = MessageDigest.getInstance("SHA-256").digest(expected.plaintext)
                    var uploads = 0
                    var publishes = 0
                    var avatar = "previous avatar"

                    suspend fun attempt(): Boolean =
                        GroupAvatarUploadAttempt({ true }, { 0 }).run(
                            prepare = {
                                GroupImageDraftProcessor.fromGroupContentUri(context.contentResolver, picked)
                            },
                            upload = { prepared ->
                                uploads++
                                assertPreparedRaster(expected, expectedHash, prepared)
                                if (uploads == 1) {
                                    throw MarmotKitException.InvalidMediaReference(
                                        "unsafe media host address: private destination",
                                    )
                                }
                                url
                            },
                            publish = {
                                publishes++
                                avatar = it
                                true
                            },
                        )
                    val failure = expectFailure { attempt() }
                    assertEquals(GroupAvatarUploadStage.Upload, failure.stage)
                    assertTrue(failure.destinationRefused)
                    assertEquals(0, publishes)
                    assertEquals("previous avatar", avatar)
                    assertTrue(attempt())
                    assertEquals(2, uploads)
                    assertEquals(1, publishes)
                    assertEquals(url, avatar)
                } finally {
                    assertTrue(file.delete())
                }
            }
        }

    private fun rasterBytes(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xFF336699.toInt())
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPreparedRaster(
        expected: ImageUploadDraft,
        expectedHash: ByteArray,
        prepared: ImageUploadDraft,
    ) {
        assertEquals("image/jpeg", prepared.mediaType)
        assertEquals(expected.plaintext.size, prepared.plaintext.size)
        assertArrayEquals(expected.plaintext, prepared.plaintext)
        assertArrayEquals(expectedHash, MessageDigest.getInstance("SHA-256").digest(prepared.plaintext))
        val decoded = BitmapFactory.decodeByteArray(prepared.plaintext, 0, prepared.plaintext.size)
        try {
            assertEquals(8, decoded.width)
            assertEquals(6, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun rejectedUploadNeverPublishesAndDeliberateRetryWorks() =
        runTest {
            var uploads = 0
            var avatar = "previous avatar"
            val failure =
                expectFailure {
                    GroupAvatarUploadAttempt({ true }, { 0 }).run(
                        { draft },
                        {
                            uploads++
                            throw MarmotKitException.InvalidMediaReference(
                                "unsafe media host address: private destination",
                            )
                        },
                        {
                            avatar = it
                            true
                        },
                    )
                }
            assertTrue(failure.destinationRefused)
            assertEquals("previous avatar", avatar)
            assertTrue(
                GroupAvatarUploadAttempt({ true }, { 0 }).run(
                    { draft },
                    {
                        uploads++
                        url
                    },
                    {
                        avatar = it
                        true
                    },
                ),
            )
            assertEquals(2, uploads)
            assertEquals(url, avatar)
        }

    @Test
    fun leavingOrSwitchingAccountDuringPreparationDoesNotUpload() =
        runTest {
            var current = true
            var uploads = 0
            val result =
                GroupAvatarUploadAttempt({ current }, { 0 }).run(
                    {
                        current = false
                        draft
                    },
                    {
                        uploads++
                        url
                    },
                    { error("stale publish") },
                )
            assertFalse(result)
            assertEquals(0, uploads)
        }

    @Test
    fun leavingDuringUploadDoesNotPublishItsLateResult() =
        runTest {
            var current = true
            assertFalse(
                GroupAvatarUploadAttempt({ current }, { 0 }).run(
                    { draft },
                    {
                        current = false
                        url
                    },
                    { error("stale publish") },
                ),
            )
        }

    @Test
    fun unsafeDescriptorDoesNotChangeAvatar() =
        runTest {
            val failure =
                expectFailure {
                    GroupAvatarUploadAttempt({ true }, { 0 }).run(
                        { draft },
                        { "https://127.0.0.1/private" },
                        { error("unsafe publish") },
                    )
                }
            assertEquals(GroupAvatarUploadStage.ValidateUrl, failure.stage)
            assertFalse(failure.destinationRefused)
        }

    @Test
    fun preparationAndCommitFailuresHaveTheirOwnStages() =
        runTest {
            val attempt = GroupAvatarUploadAttempt({ true }, { 0 })
            assertEquals(
                GroupAvatarUploadStage.Prepare,
                expectFailure { attempt.run({ error("decode") }, { url }, { true }) }.stage,
            )
            assertEquals(
                GroupAvatarUploadStage.Publish,
                expectFailure { attempt.run({ draft }, { url }, { error("commit") }) }.stage,
            )
            assertFalse(attempt.run({ draft }, { url }, { false }))
        }

    @Test
    fun cancellationIsPropagatedWithoutReportingFailure() =
        runTest {
            val cancelled = CancellationException("left")
            try {
                GroupAvatarUploadAttempt({ true }, { 0 }).run({ draft }, { throw cancelled }, { true })
                error("expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
        }

    /** Cancellation at preparation or native publication cannot be converted to an upload error. */
    @Test
    fun cancellationAtPreparationAndPublicationPreservesTheOriginalException() =
        runTest {
            for (duringPreparation in listOf(true, false)) {
                val cancelled = CancellationException("editor closed")
                var uploads = 0
                try {
                    GroupAvatarUploadAttempt({ true }, { 0 }).run(
                        { if (duringPreparation) throw cancelled else draft },
                        {
                            uploads++
                            url
                        },
                        { throw cancelled },
                    )
                    error("expected cancellation")
                } catch (actual: CancellationException) {
                    assertSame(cancelled, actual)
                    assertEquals(if (duringPreparation) 0 else 1, uploads)
                }
            }
        }

    @Test
    fun diagnosticReportContainsOnlyPhaseTimingAndFixedCategories() =
        runTest {
            var now = 0L
            val failure =
                expectFailure {
                    GroupAvatarUploadAttempt({ true }, { now }).run(
                        {
                            now = 10
                            draft
                        },
                        {
                            now = 17
                            throw MarmotKitException.InvalidMediaReference(
                                "unsafe media host address: https://secret.invalid/private?auth=token",
                            )
                        },
                        { true },
                    )
                }
            val report =
                DiagnosticFormatter.errorReport(
                    failure.stage.operationCode,
                    failure,
                    DiagnosticFormatter.ErrorReportContext("test", "35", "test"),
                )
            assertTrue(report.contains("operation=GROUP_IMAGE_UPLOAD"))
            assertTrue(report.contains("error=MEDIA_DESTINATION_REFUSED"))
            assertTrue(report.contains("marmot=InvalidMediaReference"))
            assertTrue(report.contains("phase=Upload elapsed_ms=7"))
            assertFalse(report.contains("secret"))
            assertFalse(report.contains("token"))
        }

    private suspend fun expectFailure(block: suspend () -> Unit): GroupAvatarUploadFailure {
        try {
            block()
        } catch (failure: GroupAvatarUploadFailure) {
            return failure
        }
        error("expected upload failure")
    }
}
