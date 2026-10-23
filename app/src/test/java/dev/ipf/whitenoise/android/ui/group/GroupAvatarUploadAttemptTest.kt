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

    /** Real JPEG and PNG picks prepare to the same bytes upload receives, survive a refusal and retry to success. */
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

                    /**
                     * Prepares, uploads and publishes the picked raster once, returning whether the avatar was updated.
                     */
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

    /** Encodes a small solid raster in [format], standing in for a picked photo. */
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

    /** Asserts the prepared upload is the expected 8x6 JPEG, byte for byte. */
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

    /** A refused upload leaves the saved avatar untouched and a deliberate retry can still complete. */
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

    /** Leaving or switching account during preparation stops the attempt before upload. */
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

    /** An upload that finishes after the editor was left never publishes its late result. */
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

    /** An unsafe returned URL fails at validation and never reaches the avatar update. */
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

    /** Preparation and commit failures carry their own stages, and a declined commit returns false. */
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

    /** Cancellation during upload propagates unchanged rather than becoming an upload failure. */
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

    /** The error report carries only phase, timing and fixed categories, never URL or token detail. */
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

    /** A preparation failure never reaches upload or the avatar update, and still ends the attempt. */
    @Test
    fun preparationFailureNeverCallsUploadOrPublish() =
        runTest {
            var uploads = 0
            var publishes = 0
            var finished = 0
            val failure =
                expectFailure {
                    GroupAvatarUploadAttempt({ true }, { 0 }).run(
                        prepare = { error("unreadable image") },
                        upload = {
                            uploads++
                            url
                        },
                        publish = {
                            publishes++
                            true
                        },
                        finished = { finished++ },
                    )
                }
            assertEquals(GroupAvatarUploadStage.Prepare, failure.stage)
            assertFalse(failure.destinationRefused)
            assertEquals(0, uploads)
            assertEquals(0, publishes)
            assertEquals(1, finished)
        }

    /**
     * Transport, HTTP and descriptor failures are MDK `BlobStore`, which crosses the FFI as `Runtime` at the
     * pinned MarmotKit: they never update the avatar and are never reported as a refused destination.
     */
    @Test
    fun runtimeUploadFailureNeverPublishesOrClaimsRefusal() =
        runTest {
            for (detail in listOf("upload returned HTTP 500", "upload descriptor hash mismatch")) {
                var avatar = "previous avatar"
                val failure =
                    expectFailure {
                        GroupAvatarUploadAttempt({ true }, { 0 }).run(
                            { draft },
                            { throw MarmotKitException.Runtime(detail) },
                            {
                                avatar = it
                                true
                            },
                        )
                    }
                assertEquals(GroupAvatarUploadStage.Upload, failure.stage)
                assertFalse(failure.destinationRefused)
                assertFalse(failure.diagnosticErrorCode == "MEDIA_DESTINATION_REFUSED")
                assertEquals("previous avatar", avatar)
            }
        }

    /**
     * Contract with the pinned MarmotKit's UniFFI `errors.rs`: `upload_profile_image` raises
     * `InvalidMediaReference` only from `AppError::UnsafeMediaFetch` (unsafe Blossom URL or non-public
     * resolved address), and every other upload failure crosses as `Runtime`. The type alone marks a
     * refused destination at the upload stage, whatever the detail says, and nowhere else.
     */
    @Test
    fun invalidMediaReferenceAtUploadIsADestinationRefusalWhateverItsDetail() {
        for (detail in listOf("", "unsafe Blossom URL: x", "a future reworded refusal")) {
            val cause = MarmotKitException.InvalidMediaReference(detail)
            assertTrue(GroupAvatarUploadFailure(GroupAvatarUploadStage.Upload, 0, cause).destinationRefused)
            GroupAvatarUploadStage.entries.filter { it != GroupAvatarUploadStage.Upload }.forEach { stage ->
                assertFalse(GroupAvatarUploadFailure(stage, 0, cause).destinationRefused)
            }
        }
    }

    /** An uploaded photo whose group commit fails or is declined is never reported as a saved avatar. */
    @Test
    fun uploadedPhotoWithFailedGroupCommitIsNotSuccess() =
        runTest {
            var uploads = 0
            val attempt = GroupAvatarUploadAttempt({ true }, { 0 })
            assertFalse(
                attempt.run(
                    {
                        draft
                    },
                    {
                        uploads++
                        url
                    },
                    { false },
                ),
            )
            val failure =
                expectFailure {
                    attempt.run({ draft }, {
                        uploads++
                        url
                    }, { error("commit rejected") })
                }
            assertEquals(GroupAvatarUploadStage.Publish, failure.stage)
            assertFalse(failure.destinationRefused)
            assertEquals(2, uploads)
        }

    /**
     * Every outcome ends the attempt exactly once — so the editor clears saved crop evidence — while a
     * cancellation does not, leaving that evidence for the post-recreation "interrupted" notice.
     */
    @Test
    fun finishedRunsOnceForEveryOutcomeButNotForCancellation() =
        runTest {
            val outcomes: List<suspend (() -> Unit) -> Unit> =
                listOf(
                    { done -> GroupAvatarUploadAttempt({ true }, { 0 }).run({ draft }, { url }, { true }, done) },
                    { done -> GroupAvatarUploadAttempt({ true }, { 0 }).run({ draft }, { url }, { false }, done) },
                    { done -> GroupAvatarUploadAttempt({ false }, { 0 }).run({ draft }, { url }, { true }, done) },
                    { done ->
                        var current = true
                        GroupAvatarUploadAttempt({ current }, { 0 }).run({ draft }, {
                            current = false
                            url
                        }, { true }, done)
                    },
                    { done ->
                        runCatching {
                            GroupAvatarUploadAttempt({ true }, { 0 }).run({ draft }, {
                                throw MarmotKitException.Runtime("transport")
                            }, { true }, done)
                        }
                    },
                )
            outcomes.forEach { outcome ->
                var finished = 0
                outcome { finished++ }
                assertEquals(1, finished)
            }
            var finished = 0
            try {
                GroupAvatarUploadAttempt({ true }, { 0 }).run(
                    { draft },
                    { throw CancellationException("editor closed") },
                    { true },
                    { finished++ },
                )
                error("expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(0, finished)
            }
        }

    /**
     * The editor wires the attempt's end to crop recovery and clears loading in `finally`, so neither a
     * stale owner nor an unreported `false` leaks an "interrupted" notice or a stuck spinner.
     */
    @Test
    fun editorSettlesCropRecoveryAndLoadingOnEveryOutcome() {
        val source =
            listOf(
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupEditScreen.kt"),
                File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupEditScreen.kt"),
            ).first(File::isFile).readText()
        val upload = source.substringAfter("fun uploadPublicAvatar(").substringBefore("BackHandler {")
        assertTrue(upload.contains("finished = { if (acceptedCrop) cropRecovery.settled() }"))
        assertEquals("only the attempt's end settles crop recovery", 1, upload.split("cropRecovery.settled()").size - 1)
        assertTrue(upload.contains("} finally {\n                    imageSaving = false"))
    }

    /** Runs [block] and returns the stage-tagged failure it must throw. */
    private suspend fun expectFailure(block: suspend () -> Unit): GroupAvatarUploadFailure {
        try {
            block()
        } catch (failure: GroupAvatarUploadFailure) {
            return failure
        }
        error("expected upload failure")
    }
}
