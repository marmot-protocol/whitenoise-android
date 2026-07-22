package dev.ipf.whitenoise.android.notifications

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationReplyRequestTest {
    @Test
    fun replyRequestRequiresConnectivity() {
        // Without a network constraint an offline reply runs immediately,
        // burns MAX_SEND_ATTEMPTS against guaranteed failures in ~90s, and is
        // silently dropped. The constraint defers the send until connectivity.
        assertEquals(NetworkType.CONNECTED, replyRequest().workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun replyRequestKeepsBoundedExponentialBackoff() {
        val spec = replyRequest().workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
    }

    private fun replyRequest() =
        NotificationReplyWorker.notificationReplyRequest(
            action =
                NotificationAction(
                    kind = NotificationActionKind.REPLY,
                    target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                    notificationTag = "acct-a|group-1",
                    notificationId = 0,
                ),
            requestId = UUID.randomUUID(),
            encryptedReply = EncryptedNotificationReply(ByteArray(12), ByteArray(16)),
        )
}
