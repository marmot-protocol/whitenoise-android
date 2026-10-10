package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAvatarUploadAttemptTest {
    private val draft = ImageUploadDraft(byteArrayOf(1), "image/jpeg", null, null, null)
    private val url = "https://blossom.example/image.jpg"

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
                            throw MarmotKitException.InvalidMediaReference("private destination")
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
                            throw MarmotKitException.InvalidMediaReference("https://secret.invalid/private?auth=token")
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
