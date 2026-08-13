package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SendFailureMessageTest {
    @Test
    fun aRefusedSendReadsAsABackedUpChatRatherThanAGenericFailure() {
        val refused = MarmotKitException.GroupSendQueueFull("7c3bdc38")

        assertEquals(R.string.toast_send_queue_full, sendFailureMessageRes(refused))
    }

    @Test
    fun aHydrationPendingSendReadsAsStillLoadingRatherThanAFailure() {
        val pending = MarmotKitException.GroupHydrationPending("7c3bdc38")

        assertEquals(R.string.toast_chat_still_loading, sendFailureMessageRes(pending))
    }

    @Test
    fun everyOtherFailureKeepsTheGenericMessage() {
        listOf(
            MarmotKitException.Publish("relay refused"),
            MarmotKitException.Io("disk"),
            IOException("offline"),
            IllegalStateException("illegal queue_app_message transition"),
        ).forEach { throwable ->
            assertEquals(
                "only a refused or still-loading send earns its own message",
                R.string.toast_send_failed,
                sendFailureMessageRes(throwable),
            )
        }
    }
}
