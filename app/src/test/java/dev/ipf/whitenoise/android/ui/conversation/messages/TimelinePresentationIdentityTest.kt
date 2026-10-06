package dev.ipf.whitenoise.android.ui.conversation.messages

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Checks the production native projection handoff and bounded lifetime of presentation aliases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimelinePresentationIdentityTest {
    /** Exact client-token confirmation retains the row key across refresh, until the row leaves. */
    @Test
    fun nativeConfirmationAndRefreshPreserveTheOriginalRowKey() =
        runBlocking {
            val surface =
                swipeTestSurface(
                    ApplicationProvider.getApplicationContext(),
                    reacted = false,
                    mine = true,
                    media = false,
                )
            val pending =
                surface.item.copy(
                    id = "msg:pending-token",
                    presentationId = "msg:pending-token",
                    record = surface.item.record.copy(messageIdHex = "pending-token"),
                    projected = null,
                    status = MessageStatus.Pending,
                )
            val optimistic = surface.appState.optimisticMessages(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID)
            optimistic[pending.id] = pending
            val confirmed =
                surface.item.projected!!.copy(
                    clientToken = "pending-token",
                    messageIdHex = "confirmed-id",
                    sourceMessageIdHex = "confirmed-id",
                )
            repeat(2) {
                surface.controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
                    TimelinePageFfi(listOf(confirmed), false, false)
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                val row = surface.controller.timeline.single()
                assertEquals(pending.presentationId, row.presentationId)
                assertEquals("msg:confirmed-id", row.id)
                assertEquals("confirmed-id", row.record.messageIdHex)
                assertFalse(optimistic.containsKey(pending.id))
            }
            surface.controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
                TimelinePageFfi(emptyList(), false, false)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            surface.controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
                TimelinePageFfi(listOf(confirmed), false, false)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(
                "msg:confirmed-id",
                surface.controller.timeline
                    .single()
                    .presentationId,
            )
        }
}
