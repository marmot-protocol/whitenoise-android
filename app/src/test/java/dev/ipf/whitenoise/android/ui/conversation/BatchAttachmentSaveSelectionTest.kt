package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.media.MessageAttachmentSaveSummary
import dev.ipf.whitenoise.android.ui.conversation.media.aggregateMessageAttachmentSaveSummaries
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageAttachmentSaveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchAttachmentSaveSelectionTest {
    @Test
    fun batchSaveClearsSelectionWhenSaveStartsNotWhenAsyncWorkFinishes() {
        val saveHandler =
            conversationScreenSource()
                .substringAfter("onSaveSelection = {")
                .substringBefore("onReplySelection = {")

        val launchPattern =
            Regex(
                """batchAttachmentSaveInFlight = true[\s\S]*""" +
                    """orderedBatchSelections\(selectedMessages\.values\)[\s\S]*""" +
                    """selectedMessages\.clear\(\)[\s\S]*appState\.launchMutation""",
            )
        assertTrue(
            "batch save must snapshot and clear selection before async attachment I/O",
            launchPattern.containsMatchIn(saveHandler),
        )
        assertFalse(
            "stale save completion must not clear a newer selection",
            Regex(
                """presentAttachmentSaveOutcome\([\s\S]*selectedMessages\.clear\(\)""",
            ).containsMatchIn(saveHandler),
        )
    }

    @Test
    fun delayedSaveCompletionPreservesReselectionAfterLaunchTimeClear() {
        val selected = mutableMapOf("m1" to selection("m1", recordedAt = 100uL, timelineOrder = 1uL))

        val savedSnapshot = orderedBatchSelections(selected.values)
        selected.clear()
        selected["m2"] = selection("m2", recordedAt = 200uL, timelineOrder = 2uL)

        assertEquals(listOf("m1"), savedSnapshot.map { it.action.messageId })
        assertEquals(setOf("m2"), selected.keys)
    }

    @Test
    fun batchSaveAggregatesPartialSuccessAcrossTwoMessages() {
        val failure = java.io.IOException("first failure")
        val summary =
            aggregateMessageAttachmentSaveSummaries(
                listOf(
                    MessageAttachmentSaveSummary(savedCount = 2, totalCount = 2),
                    MessageAttachmentSaveSummary(savedCount = 0, totalCount = 1, firstFailure = failure),
                ),
            )

        assertEquals(2, summary.savedCount)
        assertEquals(3, summary.totalCount)
        assertEquals(MessageAttachmentSaveOutcome.Partial, summary.outcome)
        assertSame(failure, summary.firstFailure)
    }

    private fun conversationScreenSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
        ).first(File::exists).readText()

    private fun selection(
        id: String,
        recordedAt: ULong,
        timelineOrder: ULong,
    ): BatchMessageSelection =
        BatchMessageSelection(
            action =
                BatchMessageActionItem(
                    id,
                    "alice",
                    "Alice",
                    id,
                    id,
                    canDeleteForEveryone = false,
                ),
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = id,
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = recordedAt,
                    receivedAt = recordedAt,
                ),
            status = MessageStatus.Received,
            timelineOrder = timelineOrder,
        )
}
