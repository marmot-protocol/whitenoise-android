package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reviewed voice note has one owner. The review releases it in the same turn it is seeded and passes no durable
 * callback; every review action is gated on `!sending`; sending renders a visible indicator, not only semantics
 * (#3956).
 */
class VoiceSendHandoffCoverageTest {
    /** The sender settles the review at seed for replies too, so no failed reply leaves two live Send paths. */
    @Test
    fun voiceSendHandsTheTakeToItsRowAtSeed() {
        val send = source("ui/conversation/ConversationMediaSender.kt").functionBody("sendVoiceAttachment")
        val upload = Regex("""controller\.uploadQueued\(seeded\)(\s*\{)?""").findAll(send).toList()
        assertEquals("one upload call", 1, upload.size)
        assertTrue("no durable-acceptance callback", upload.single().groupValues[1].isEmpty())
        assertFalse("no reply-only hand-off branch", send.contains("replyTarget != null"))
        assertFalse("no non-reply-only hand-off branch", send.contains("if (replyTarget == null)"))
        val handOff = send.indexOf("onQueued(true)")
        assertTrue(
            "the take is handed off before the upload starts",
            handOff in 0 until send.indexOf(upload.single().value),
        )
    }

    /** Each review action is disabled while Send prepares, and user Discard is refused in that window. */
    @Test
    fun everyReviewActionIsGatedOnSending() {
        val actions = source("ui/conversation/composer/VoiceReviewActions.kt")
        listOf(
            "VoiceReviewPlayAction",
            "VoiceReviewDiscardAction",
            "VoiceReviewRecordAgainAction",
            "VoiceReviewSendAction",
        ).forEach { name ->
            val body = actions.functionBody(name)
            assertTrue("$name must be disabled while sending", body.contains("enabled = !sending"))
        }
        val review = source("ui/conversation/composer/VoiceRecordingReview.kt")
        assertTrue(review.functionBody("discardByUser").contains("!isSending"))
        val composer = source("ui/conversation/composer/VoiceReviewComposer.kt")
        assertFalse("the UI never calls the internal discard", composer.contains("review.discard(clip)"))
        assertTrue(composer.functionBody("VoiceReviewComposerContent").contains("if (!sending) {"))
    }

    /** Sending is drawn on the review's Send disc and on the pending bubble, not only announced. */
    @Test
    fun sendingRendersAVisibleIndicator() {
        assertTrue(source("ui/conversation/composer/VoiceReviewActions.kt").contains("VoiceSendingIndicator("))
        assertTrue(
            source("ui/conversation/media/MediaVoice.kt")
                .functionBody("VoiceAttachmentContent")
                .contains("VoiceSendingIndicator("),
        )
        assertTrue(
            source("ui/conversation/messages/PendingVoiceAttachments.kt")
                .contains("sending = item.status == MessageStatus.Pending"),
        )
    }

    private fun source(path: String): String = appModuleSource("src/main/java/dev/ipf/whitenoise/android/$path")
}
