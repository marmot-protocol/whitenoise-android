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
import dev.ipf.whitenoise.android.ui.conversation.composer.voiceRecordingOpensLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Events a screen reader cannot see are spoken. A live append announces only new messages someone else sent, naming a
 * single sender and counting a burst; a replaced timeline or a reordered existing row announces nothing. The voice
 * recorder speaks only after the microphone is released, so nothing spoken lands in the take.
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

        val arrivals = conversationIncomingArrivals(timeline, "msg:anchor", emptySet()) { it.record.sender == "me" }

        assertEquals(listOf("new"), arrivals.map { it.record.messageIdHex })
        assertEquals(
            resources.getString(R.string.conversation_new_message_from, "Bob"),
            conversationArrivalAnnouncement(arrivals, { "Bob" }, resources),
        )
    }

    @Test
    fun burstIsCountedAndOwnOrReplacedTimelinesStaySilent() {
        val timeline = listOf(row("anchor"), row("a"), row("b"), row("c"))
        val burst = conversationIncomingArrivals(timeline, "msg:anchor", emptySet()) { false }

        assertEquals(
            resources.getQuantityString(R.plurals.conversation_new_messages, 3, 3),
            conversationArrivalAnnouncement(burst, { error("a burst names no sender") }, resources),
        )
        val ownOnly = conversationIncomingArrivals(timeline, "msg:anchor", emptySet()) { true }
        assertNull(conversationArrivalAnnouncement(ownOnly, { "" }, resources))
        assertEquals(
            emptyList<TimelineMessage>(),
            conversationIncomingArrivals(timeline, "msg:gone", emptySet()) { false },
        )
    }

    @Test
    fun olderPageRowsMovedPastTheAnchorAreNotAnnounced() {
        val baseline = ConversationArrivalBaseline()
        baseline.markAllRendered(listOf(row("anchor")), reachesLiveTail = true)
        // An older page loads above the anchor without changing the latest row.
        baseline.noteRendered(listOf(row("older"), row("anchor")), "msg:anchor")
        // A display-position change then moves "older" past the anchor while "new" arrives.
        val timeline = listOf(row("anchor"), row("older"), row("new"))

        val arrivals = baseline.follow(timeline, "msg:anchor", reachesLiveTail = true) { false }

        assertEquals(listOf("new"), arrivals.map { it.record.messageIdHex })
        assertEquals(
            emptyList<TimelineMessage>(),
            baseline.follow(timeline + row("later"), null, reachesLiveTail = true) { false },
        )
        assertEquals(
            emptyList<TimelineMessage>(),
            baseline.follow(timeline + row("later"), "msg:new", reachesLiveTail = true) { false },
        )
    }

    @Test
    fun newerPagesLoadedFromHistoryAreNotAnnounced() {
        val baseline = ConversationArrivalBaseline()
        // A jump to an old message leaves the window short of the live tail.
        val jumped = listOf(row("old"))
        baseline.markAllRendered(jumped, reachesLiveTail = false)
        val middle = jumped + row("middle")
        val tail = middle + row("newest")

        assertEquals(
            emptyList<TimelineMessage>(),
            baseline.follow(middle, "msg:old", reachesLiveTail = false) { false },
        )
        // The page that reaches the tail is still history the reader scrolled to.
        assertEquals(
            emptyList<TimelineMessage>(),
            baseline.follow(tail, "msg:middle", reachesLiveTail = true) { false },
        )
        // Only a message arriving after the window reached the tail is new.
        val arrivals = baseline.follow(tail + row("live"), "msg:newest", reachesLiveTail = true) { false }
        assertEquals(listOf("live"), arrivals.map { it.record.messageIdHex })
    }

    @Test
    fun voiceRecordingSpeaksOnlyOnceTheMicrophoneCloses() {
        val idle = VoiceRecordingPhase.Idle
        val holding = VoiceRecordingPhase.Holding
        val locked = VoiceRecordingPhase.Locked
        val closing = VoiceRecordingPhase.Closing

        // Anything spoken while the microphone is open is captured in the take.
        assertNull(voiceRecordingAnnouncementRes(idle, holding))
        assertNull(voiceRecordingAnnouncementRes(idle, locked))
        assertNull(voiceRecordingAnnouncementRes(holding, locked))
        // A released take keeps recording its tail until the microphone is let go.
        assertNull(voiceRecordingAnnouncementRes(holding, closing))
        assertNull(voiceRecordingAnnouncementRes(locked, closing))
        assertEquals(R.string.voice_recording_stopped_announcement, voiceRecordingAnnouncementRes(closing, idle))
        assertEquals(R.string.voice_recording_stopped_announcement, voiceRecordingAnnouncementRes(holding, idle))
        assertEquals(R.string.voice_recording_stopped_announcement, voiceRecordingAnnouncementRes(locked, idle))
        VoiceRecordingPhase.entries.forEach { phase -> assertNull(voiceRecordingAnnouncementRes(phase, phase)) }
        // A tap opens a locked take silently, so it is felt; a held take already buzzed when pressed.
        assertTrue(voiceRecordingOpensLocked(idle, locked))
        assertTrue(voiceRecordingOpensLocked(closing, locked))
        assertFalse(voiceRecordingOpensLocked(holding, locked))
        assertFalse(voiceRecordingOpensLocked(idle, holding))
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
