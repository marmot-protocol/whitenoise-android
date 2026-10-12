package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.notifications.LongBodies
import dev.ipf.whitenoise.android.notifications.MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS
import dev.ipf.whitenoise.android.ui.MARKDOWN_PREVIEW_MAX_LENGTH
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A long message body reaches the notification through the production resolver and formatter, cut only by the
 * per-message safety bound and never by the chat list's one-line preview budget (#1901).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationFirstPostLongBodyTest {
    /** A body several times the chat-list budget reaches the posted text whole. */
    @Test
    fun longBodyPostsWholeInsteadOfStoppingAtTheChatListBudget() =
        runBlocking {
            val body = LongBodies.plain(MARKDOWN_PREVIEW_MAX_LENGTH * 3)

            val posted = postedBody(body)

            assertEquals(body, posted)
        }

    /** A body past the safety bound is cut to exactly that many code points. */
    @Test
    fun bodyPastTheSafetyBoundIsCutAtTheBound() =
        runBlocking {
            val body = LongBodies.plain(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS + 500)

            val posted = postedBody(body)

            assertEquals(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS, LongBodies.codePoints(posted))
            assertTrue(body.startsWith(posted))
        }

    /** Two-unit code points keep the full code-point bound, because flattening budgets two units per code point. */
    @Test
    fun supplementaryCodePointsKeepTheFullBound() =
        runBlocking {
            val body = "🙂".repeat(MAX_NOTIFICATION_MESSAGE_BODY_CODE_POINTS)

            val posted = postedBody(body)

            assertEquals(body, posted)
        }

    /** Resolves [body] as a new message and returns the text the formatter would post. */
    private suspend fun postedBody(body: String): String {
        val context = RuntimeEnvironment.getApplication()
        val fixture = NotificationBootstrapTestFixture(context = context, previewText = body)
        try {
            val content =
                createNotificationContentResolutionServices(context = context, source = ParagraphReads)
                    .firstPost
                    .resolve(fixture.update, localOnly = true)
            val presentation = notificationContentPresentation(context, fixture.update, content) { "npub" }
            return checkNotNull(presentation.content).body
        } finally {
            fixture.close()
        }
    }

    /** Parses every body as one plain paragraph and answers the identity reads with fixed local names. */
    private object ParagraphReads : NotificationContentSource {
        /** No contact alias, so the sender label comes from the profile read. */
        override fun contactNickname(
            accountRef: String?,
            accountIdHex: String,
        ): String? = null

        /** Every identity reads as the same local sender name. */
        override suspend fun readDisplayName(accountIdHex: String): String = "Sender"

        /** No payload name hint. */
        override fun displayNameHint(accountIdHex: String): String? = null

        /** A fixed identity fallback that these tests never reach. */
        override fun cachedShortNpub(accountIdHex: String): String = "unknown"

        /** Nothing is hydrated yet, so the resolver reads the stored name. */
        override fun hydratedDisplayName(accountIdHex: String): String? = null

        /** Fails the test if the local first draw starts a remote profile read. */
        override fun requestProfile(accountIdHex: String): Unit = error("local first draw must not hydrate")

        /** The bodies carry no mentions to decode. */
        override suspend fun accountIdHex(bech32: String): String? = null

        /** Returns [raw] as a single paragraph, the shape MDK gives plain prose. */
        override suspend fun parseMarkdown(raw: String): MarkdownDocumentFfi =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(raw)))),
                blankLinesBefore = ByteArray(0),
            )

        /** No recipient identity, so no recipient subtext is resolved. */
        override fun recipientAccountIdHex(ref: String): String? = null

        /** No stored record is needed for a text body. */
        override suspend fun timelineRecord(update: NotificationUpdateFfi): TimelineMessageRecordFfi? = null

        /** An empty roster, since recipient relevance is not under test. */
        override suspend fun groupMembers(update: NotificationUpdateFfi): List<AppGroupMemberRecordFfi> = emptyList()

        /** Text bodies carry no media. */
        override suspend fun mediaKind(update: NotificationUpdateFfi): ReplyMediaKind = ReplyMediaKind.None

        /** No signed-in identities, so recipient subtext stays off. */
        override fun signedInAccountIds(): Set<String> = emptySet()
    }
}
