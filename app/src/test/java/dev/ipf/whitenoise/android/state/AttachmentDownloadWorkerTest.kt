package dev.ipf.whitenoise.android.state

import androidx.work.WorkInfo
import dev.ipf.marmotkit.MarmotKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadWorkerTest {
    @Test
    fun backlogStopCancelsOnlyQueuedAutomaticWork() {
        assertTrue(shouldCancelQueuedAutomaticWork(WorkInfo.State.ENQUEUED, hasInteractiveIntent = false))
        assertTrue(shouldCancelQueuedAutomaticWork(WorkInfo.State.BLOCKED, hasInteractiveIntent = false))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.RUNNING, hasInteractiveIntent = false))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.ENQUEUED, hasInteractiveIntent = true))
        assertFalse(shouldCancelQueuedAutomaticWork(WorkInfo.State.SUCCEEDED, hasInteractiveIntent = false))
    }

    @Test
    fun workDataRoundTripsOnlyTheMdkLookupIdentity() {
        val request =
            AttachmentTransferRequest(
                accountRef = "account-a",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 3,
            )

        val encoded = AttachmentDownloadWorkData.encode(request)

        assertEquals(request, AttachmentDownloadWorkData.decode(encoded))
        val serialized = encoded.keyValueMap.values.joinToString("|")
        assertFalse(serialized.contains("https://"))
        assertFalse(serialized.contains("ciphertext"))
        assertFalse(serialized.contains("nonce"))
    }

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
