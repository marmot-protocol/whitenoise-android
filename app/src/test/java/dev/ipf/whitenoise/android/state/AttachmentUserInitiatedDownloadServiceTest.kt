package dev.ipf.whitenoise.android.state

import android.os.PersistableBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JobScheduler retains only the same validated native lookup identity as WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentUserInitiatedDownloadServiceTest {
    private val request =
        AttachmentTransferRequest(
            accountRef = "private-account",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 2,
            sourceMessageIdHex = "ef".repeat(32),
        )

    @Test
    fun jobExtrasRoundTripOnlyTheMinimalNativeIdentity() {
        val extras = attachmentJobExtras(request)

        assertEquals(request, decodeAttachmentJobExtras(extras))
        assertEquals(attachmentDownloadWorkName(request), extras.getString("identity"))
        assertFalse(extras.keySet().contains("url"))
        assertFalse(extras.keySet().contains("filename"))
        assertFalse(extras.keySet().contains("nonce"))
        assertFalse(extras.keySet().contains("media_reference"))
    }

    @Test
    fun malformedOrMismatchedJobIdentityCannotReachMdk() {
        val missingIdentity = PersistableBundle(attachmentJobExtras(request))
        missingIdentity.remove("identity")
        assertNull(decodeAttachmentJobExtras(missingIdentity))

        val invalidGroup = PersistableBundle(attachmentJobExtras(request))
        invalidGroup.putString("group_id_hex", "not-a-group")
        assertNull(decodeAttachmentJobExtras(invalidGroup))
    }

    @Test
    fun schedulerIdAndDedupIdentityStayStableAcrossSourceProjection() {
        val sourceLess = request.copy(sourceMessageIdHex = null)

        assertEquals(attachmentJobId(sourceLess), attachmentJobId(request))
        assertEquals(attachmentDownloadWorkName(sourceLess), attachmentDownloadWorkName(request))
        assertTrue(attachmentJobId(request) >= 0)
    }
}
