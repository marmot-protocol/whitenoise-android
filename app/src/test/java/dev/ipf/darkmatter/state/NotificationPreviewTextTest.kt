package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreviewTextTest {
    private val knownNpub = "npub1" + "q".repeat(58)
    private val unknownNpub = "npub1" + "z".repeat(58)
    private val knownHex = "a".repeat(64)

    @Test
    fun mentionResolutionUsesCachedProfileNameBeforeBindingRead() =
        runBlocking {
            var displayNameReads = 0
            var profileRequests = 0

            val resolved =
                resolveNotificationMentionDisplayName(
                    bech32 = knownNpub,
                    accountIdHex = { knownHex },
                    profileDisplayName = { "Alice" },
                    readDisplayName = {
                        displayNameReads++
                        "stale binding name"
                    },
                    requestProfile = { profileRequests++ },
                )

            assertEquals("Alice", resolved)
            assertEquals(0, displayNameReads)
            assertEquals(0, profileRequests)
        }

    @Test
    fun mentionResolutionReadsAndSanitizesColdDisplayName() =
        runBlocking {
            val requests = mutableListOf<String>()

            val resolved =
                resolveNotificationMentionDisplayName(
                    bech32 = knownNpub,
                    accountIdHex = { knownHex },
                    profileDisplayName = { null },
                    readDisplayName = { " A\u200bl\u2060ice " },
                    requestProfile = requests::add,
                )

            assertEquals("Alice", resolved)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun mentionResolutionRequestsProfileWhenNoNameExists() =
        runBlocking {
            val requests = mutableListOf<String>()

            val resolved =
                resolveNotificationMentionDisplayName(
                    bech32 = knownNpub,
                    accountIdHex = { knownHex },
                    profileDisplayName = { null },
                    readDisplayName = { null },
                    requestProfile = requests::add,
                )

            assertNull(resolved)
            assertEquals(listOf(knownHex), requests)
        }

    @Test
    fun previewTextResolvesMentionsAndFallsBackToShortenedBech32() =
        runBlocking {
            val resolved =
                resolveNotificationPreviewText(
                    raw = "hello mentions",
                    parseMarkdown = { raw ->
                        assertEquals("hello mentions", raw)
                        MarkdownDocumentFfi(
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(
                                        MarkdownInlineFfi.Text("hello "),
                                        MarkdownInlineFfi.NostrMention(
                                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, knownNpub),
                                        ),
                                        MarkdownInlineFfi.Text(" and "),
                                        MarkdownInlineFfi.NostrMention(
                                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, unknownNpub),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    },
                    mentionDisplayName = { bech32 -> "Alice".takeIf { bech32 == knownNpub } },
                )

            assertEquals("hello @Alice and @npub1zzzzzzz…zzzzzz", resolved)
        }

    @Test
    fun previewTextResolvesEachDistinctMentionOnce() =
        runBlocking {
            var resolveCount = 0

            val resolved =
                resolveNotificationPreviewText(
                    raw = "duplicate mention",
                    parseMarkdown = {
                        MarkdownDocumentFfi(
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(
                                        MarkdownInlineFfi.NostrMention(
                                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, knownNpub),
                                        ),
                                        MarkdownInlineFfi.Text(" then again "),
                                        MarkdownInlineFfi.NostrMention(
                                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, knownNpub),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    },
                    mentionDisplayName = {
                        resolveCount++
                        "Alice"
                    },
                )

            assertEquals("@Alice then again @Alice", resolved)
            assertEquals(1, resolveCount)
        }

    @Test
    fun previewTextReturnsNullForBlankInputOrEmptyDocument() =
        runBlocking {
            val blank =
                resolveNotificationPreviewText(
                    raw = "   ",
                    parseMarkdown = { error("blank input should not be parsed") },
                    mentionDisplayName = { error("blank input should not resolve mentions") },
                )
            val emptyDocument =
                resolveNotificationPreviewText(
                    raw = "not blank",
                    parseMarkdown = { MarkdownDocumentFfi(blocks = emptyList()) },
                    mentionDisplayName = { error("empty document should not resolve mentions") },
                )

            assertNull(blank)
            assertNull(emptyDocument)
        }
}
