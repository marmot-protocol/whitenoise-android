package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingPhase
import dev.ipf.whitenoise.android.ui.conversation.composer.voiceRecordingAnnouncementRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Events a screen reader cannot see are spoken. A live append announces only messages someone else sent, naming a
 * single sender and counting a burst; a replaced timeline announces nothing. The voice recorder announces start, lock
 * and stop, and a tap that starts and locks in one step is heard once as a start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenReaderAnnouncementCoverageTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun appendAnnouncesOnlyOtherPeoplesChatMessagesAfterTheLastFollowedRow() {
        val timeline =
            listOf(
                row("old", sender = "alice"),
                row("anchor", sender = "alice"),
                row("mine", sender = "me"),
                row("reaction", sender = "bob", kind = 7uL),
                row("new", sender = "bob"),
            )

        val arrivals = conversationIncomingArrivals(timeline, "msg:anchor") { it.record.sender == "me" }

        assertEquals(listOf("new"), arrivals.map { it.record.messageIdHex })
        assertEquals(
            resources.getString(R.string.conversation_new_message_from, "Bob"),
            conversationArrivalAnnouncement(arrivals, { "Bob" }, resources),
        )
    }

    @Test
    fun burstIsCountedAndOwnOrReplacedTimelinesStaySilent() {
        val timeline = listOf(row("anchor"), row("a"), row("b"), row("c"))
        val burst = conversationIncomingArrivals(timeline, "msg:anchor") { false }

        assertEquals(
            resources.getQuantityString(R.plurals.conversation_new_messages, 3, 3),
            conversationArrivalAnnouncement(burst, { error("a burst names no sender") }, resources),
        )
        val ownOnly = conversationIncomingArrivals(timeline, "msg:anchor") { true }
        assertNull(conversationArrivalAnnouncement(ownOnly, { "" }, resources))
        assertEquals(emptyList<TimelineMessage>(), conversationIncomingArrivals(timeline, "msg:gone") { false })
    }

    @Test
    fun voiceRecordingAnnouncesStartLockAndStopOnce() {
        val idle = VoiceRecordingPhase.Idle
        val holding = VoiceRecordingPhase.Holding
        val locked = VoiceRecordingPhase.Locked

        assertEquals(R.string.voice_recording_started_announcement, voiceRecordingAnnouncementRes(idle, holding))
        assertEquals(R.string.voice_recording_started_announcement, voiceRecordingAnnouncementRes(idle, locked))
        assertEquals(R.string.voice_recording_locked_announcement, voiceRecordingAnnouncementRes(holding, locked))
        assertEquals(R.string.voice_recording_stopped_announcement, voiceRecordingAnnouncementRes(holding, idle))
        assertEquals(R.string.voice_recording_stopped_announcement, voiceRecordingAnnouncementRes(locked, idle))
        VoiceRecordingPhase.entries.forEach { phase -> assertNull(voiceRecordingAnnouncementRes(phase, phase)) }
    }

    private fun row(
        id: String,
        sender: String = "alice",
        kind: ULong = 9uL,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = sender,
                    plaintext = id,
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = kind,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 0uL,
                    receivedAt = 0uL,
                    sticker = null,
                ),
            status = MessageStatus.Received,
        )
}
