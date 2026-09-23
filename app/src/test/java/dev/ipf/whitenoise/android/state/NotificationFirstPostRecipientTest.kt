package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Recipient subtext never spends the first-draw budget when it cannot be displayed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationFirstPostRecipientTest {
    /** A second signed-in identity outside the conversation must not expose recipient subtext. */
    @Test
    fun unrelatedSignedInAccountSkipsRecipientIdentityRead() =
        runBlocking {
            val content =
                resolve(
                    signedInAccountIds = setOf("recipient", "unrelated"),
                    memberIds = listOf("recipient", "peer"),
                ) { error("hidden recipient label must not be read") }
            assertNull(content.recipientAccountSubtext)
        }

    /** Multiple signed-in identities in the same conversation retain the recipient label. */
    @Test
    fun multipleRelevantAccountsKeepResolvedRecipientSubtext() =
        runBlocking {
            val content =
                resolve(
                    signedInAccountIds = setOf("recipient", "second"),
                    memberIds = listOf("recipient", "second", "peer"),
                ) { "Recipient" }
            assertEquals("Recipient", content.recipientAccountSubtext)
        }

    /** An unavailable roster fails closed instead of showing account identity based on global count. */
    @Test
    fun unavailableRosterOmitsRecipientSubtext() =
        runBlocking {
            val content =
                resolve(
                    signedInAccountIds = setOf("recipient", "second"),
                    memberIds = emptyList(),
                ) { error("inconclusive recipient label must not be read") }
            assertNull(content.recipientAccountSubtext)
        }

    /** Uses the production projection graph with only external data reads replaced. */
    private suspend fun resolve(
        signedInAccountIds: Set<String>,
        memberIds: List<String>,
        recipient: (String) -> String?,
    ): NotificationFirstPostContent {
        val context = RuntimeEnvironment.getApplication()
        val fixture = NotificationBootstrapTestFixture(context = context)
        try {
            return createNotificationContentResolutionServices(
                context = context,
                source = ContentReads(signedInAccountIds, memberIds, recipient),
            ).firstPost.resolve(fixture.update, localOnly = true)
        } finally {
            fixture.close()
        }
    }

    /** Supplies external reads while keeping the production content projection intact. */
    private class ContentReads(
        private val signedInIds: Set<String>,
        private val memberIds: List<String>,
        private val recipient: (String) -> String?,
    ) : NotificationContentSource {
        override fun contactNickname(
            accountRef: String?,
            accountIdHex: String,
        ): String? = null

        override suspend fun readDisplayName(accountIdHex: String): String =
            when (accountIdHex) {
                "recipient" -> recipient(accountIdHex) ?: "Recipient"
                else -> "Sender"
            }

        override fun displayNameHint(accountIdHex: String): String? = null

        override fun cachedShortNpub(accountIdHex: String): String = "unknown"

        override fun hydratedDisplayName(accountIdHex: String): String? = null

        override fun requestProfile(accountIdHex: String): Unit = error("local first draw must not hydrate")

        override suspend fun accountIdHex(bech32: String): String? = null

        override suspend fun parseMarkdown(raw: String): MarkdownDocumentFfi =
            MarkdownDocumentFfi(
                blocks = emptyList(),
                truncated = false,
                blankLinesBefore = ByteArray(0),
            )

        override fun recipientAccountIdHex(ref: String): String = "recipient"

        override suspend fun timelineRecord(update: NotificationUpdateFfi): TimelineMessageRecordFfi? = null

        override suspend fun groupMembers(update: NotificationUpdateFfi): List<AppGroupMemberRecordFfi> =
            memberIds.map { AppGroupMemberRecordFfi(memberIdHex = it, account = null, local = false) }

        override suspend fun mediaKind(update: NotificationUpdateFfi): ReplyMediaKind = ReplyMediaKind.None

        override fun signedInAccountIds(): Set<String> = signedInIds
    }
}
