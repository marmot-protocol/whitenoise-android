package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.SpanStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MarkdownAutolinkIntegrationTest {
    @Test
    fun actualParserKeepsHttpUrlsClickableFromMessageText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MarmotAndroid.initialize(context)
        val root = File(context.cacheDir, "markdown-autolink-${UUID.randomUUID()}").apply { mkdirs() }
        val destinations =
            try {
                Marmot(root.absolutePath, emptyList()).use { marmot ->
                    listOf(
                        "https://example.com/path?q=1#section",
                        "See http://example.com/a_(b)?q=two#three.",
                    ).flatMap { messageText ->
                        val document = marmot.parseMarkdown(messageText)
                        val optimistic = optimisticRecord(messageText, document)
                        val projected = TimelineProjector.toAppMessageRecord(projectedRecord(messageText, document))
                        listOf(optimistic, projected).map { record -> linkDestination(record) }
                    }
                }
            } finally {
                root.deleteRecursively()
            }

        assertEquals(
            listOf(
                "https://example.com/path?q=1#section",
                "https://example.com/path?q=1#section",
                "http://example.com/a_(b)?q=two#three",
                "http://example.com/a_(b)?q=two#three",
            ),
            destinations,
        )
    }

    private fun linkDestination(record: AppMessageRecordFfi): String {
        val paragraph = record.contentTokens.blocks.single() as MarkdownBlockFfi.Paragraph
        return markdownLinkDestinations(
            markdownInlinesToAnnotatedString(
                inlines = paragraph.inlines,
                codeStyle = SpanStyle(),
                linkStyle = SpanStyle(),
            ),
        ).single()
    }

    private fun optimisticRecord(
        text: String,
        document: dev.ipf.marmotkit.MarkdownDocumentFfi,
    ) = AppMessageRecordFfi(
        messageIdHex = "temp",
        direction = "sent",
        groupIdHex = "group",
        sender = "alice",
        plaintext = text,
        contentTokens = document,
        kind = 9uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )

    private fun projectedRecord(
        text: String,
        document: dev.ipf.marmotkit.MarkdownDocumentFfi,
    ) = TimelineMessageRecordFfi(
        messageIdHex = "confirmed",
        sourceMessageIdHex = "source",
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        direction = "sent",
        groupIdHex = "group",
        sender = "alice",
        plaintext = text,
        contentTokens = document,
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
    )
}
