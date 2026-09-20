package dev.ipf.whitenoise.android.state

import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.ipf.marmotkit.MarmotKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadWorkerTest {
    /** Backlog stop removes only queued automatic work without interactive intent. */
    @Test
    fun backlogStopCancelsOnlyQueuedAutomaticWork() {
        assertTrue(shouldCancelQueuedAutomaticWork(WorkInfo.State.ENQUEUED, hasInteractiveIntent = false))
        assertTrue(shouldCancelQueuedAutomaticWork(WorkInfo.State.BLOCKED, hasInteractiveIntent = false))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.RUNNING, hasInteractiveIntent = false))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.ENQUEUED, hasInteractiveIntent = true))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.SUCCEEDED, hasInteractiveIntent = false))
    }

    /** WorkManager stores only the minimal validated native lookup identity. */
    @Test
    fun workDataRoundTripsOnlyTheMdkLookupIdentity() {
        val request =
            AttachmentTransferRequest(
                accountRef = "account-a",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 3,
                sourceMessageIdHex = "ef".repeat(32),
            )

        val encoded = AttachmentDownloadWorkData.encode(request)

        assertEquals(request, AttachmentDownloadWorkData.decode(encoded))
        val serialized = encoded.keyValueMap.values.joinToString("|")
        assertFalse(serialized.contains("https://"))
        assertFalse(serialized.contains("ciphertext"))
        assertFalse(serialized.contains("nonce"))
    }

    /** Work persisted by the prior release remains decodable without a source ID. */
    @Test
    fun legacyWorkDataWithoutASourceMessageIdStillDecodes() {
        val decoded =
            AttachmentDownloadWorkData.decode(
                workDataOf(
                    "account_ref" to "account-a",
                    "group_id_hex" to "ab".repeat(16),
                    "message_id_hex" to "cd".repeat(32),
                    "attachment_index" to 3,
                ),
            )

        assertEquals(
            AttachmentTransferRequest("account-a", "ab".repeat(16), "cd".repeat(32), 3),
            decoded,
        )
    }

    /** Durable names and tags hash every private conversation identifier. */
    @Test
    fun uniqueWorkNameDoesNotExposeConversationIdentifiers() {
        val request =
            AttachmentTransferRequest(
                accountRef = "private-account-label",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )

        val name = attachmentDownloadWorkName(request)

        assertTrue(name.startsWith("attachment_download_"))
        assertFalse(name.contains(request.accountRef))
        assertFalse(name.contains(request.groupIdHex))
        assertFalse(name.contains(request.messageIdHex))

        val accountTag = attachmentAutomaticAccountTag(request.accountRef)
        val identityTag = attachmentIdentityTag(request)
        assertFalse(accountTag.contains(request.accountRef))
        assertFalse(identityTag.contains(request.groupIdHex))
        assertFalse(identityTag.contains(request.messageIdHex))
        assertTrue(accountTag != attachmentAutomaticAccountTag("other-account"))
    }

    /** Source projection upgrades retain the same durable cancellation and suppression identity. */
    @Test
    fun sourceQualifiedRequestKeepsTheSourceLessWorkIdentity() {
        val sourceLess =
            AttachmentTransferRequest("account", "ab".repeat(16), "cd".repeat(32), 2)
        val sourceQualified = sourceLess.copy(sourceMessageIdHex = "ef".repeat(32))

        assertEquals(attachmentDownloadWorkName(sourceLess), attachmentDownloadWorkName(sourceQualified))
        assertEquals(attachmentIdentityTag(sourceLess), attachmentIdentityTag(sourceQualified))
        assertEquals(
            "attachment_download_5bed766117bdd9260a266b7ce5644ca2c79084d78fe97ddb4ef86389ca6ff31d",
            attachmentDownloadWorkName(sourceLess),
        )
    }

    /** Retry policy permits one transient follow-up without restoring long retry loops. */
    @Test
    fun durableWorkerRetriesOneLaterAttemptWithoutRestoringTheOldThreeMinuteLoop() {
        val timeout = MarmotKitException.Runtime("request timed out")

        assertTrue(shouldRetryAttachmentDownloadWork(runAttemptCount = 0, timeout))
        assertFalse(shouldRetryAttachmentDownloadWork(runAttemptCount = 1, timeout))
        assertFalse(
            shouldRetryAttachmentDownloadWork(
                runAttemptCount = 0,
                MarmotKitException.InvalidMediaReference("media decryption failed"),
            ),
        )
    }
}
