package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChatListSearchProjectionTest {
    /** Title matches lead, then metadata matches; a chat matching nothing is not a chat row. */
    @Test
    fun titleMatchesLeadMetadataMatchesAndUnmatchedChatsStayOut() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("unmatched", "01"),
                        candidate("preview", "02", preview = "Marmot appears here"),
                        candidate("title-z", "03", title = "Marmot Z"),
                        candidate("title-a", "04", title = "Marmot A"),
                        candidate("description", "05", description = "Marmot planning"),
                    ),
                rawQuery = "marmot",
            )

        assertEquals(listOf("title-z", "title-a", "preview", "description"), sections.groups)
        assertEquals(sections.orderedItems().size, sections.orderedItems().distinct().size)
    }

    /** Two candidates with one canonical id keep the stronger classification and one row. */
    @Test
    fun canonicalDuplicateUsesStrongestClassificationWithoutDuplicatingRow() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("metadata-copy", "AAbb", description = "marmot"),
                        candidate("title-copy", "aabb", title = "Marmot group"),
                    ),
                rawQuery = "marmot",
            )

        assertEquals(listOf("title-copy"), sections.groups)
    }

    /** The folder scope uses the same locale-invariant canonical id as the rows. */
    @Test
    fun folderScopeUsesTheLocaleInvariantCanonicalId() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("included", "ABCDEF", title = "needle"),
                        candidate("excluded", "123456", title = "needle"),
                    ),
                rawQuery = "needle",
                folderChatIds = setOf("AbCdEf"),
            )

        assertEquals(listOf("included"), sections.groups)
    }

    @Test
    fun titlePrecedenceIsLocaleInvariant() =
        withDefaultLocale(Locale.forLanguageTag("tr")) {
            val sections =
                projectChatListSearchCandidates(
                    candidates = listOf(candidate("title", "01", title = "INDIGO")),
                    rawQuery = "i",
                )

            assertEquals(listOf("title"), sections.groups)
        }

    /** Full copied MLS and Nostr identifiers stay in Groups, with or without a message-only filter. */
    @Test
    fun copiedGroupIdentifiersRemainStableGroupResults() {
        val mlsId = "0123456789abcdef".repeat(4)
        val nostrId = "fedcba9876543210".repeat(4)
        val candidates =
            listOf(
                candidate("other", "a".repeat(64)),
                candidate("matched", mlsId, nostrGroupId = nostrId),
            )

        listOf(mlsId, nostrId, "  ${nostrId.uppercase()}  ").forEach { query ->
            val immediate = projectChatListSearchCandidates(candidates, rawQuery = query)
            val messageOnly = projectChatListSearchCandidates(candidates, rawQuery = query, messageOnly = true)

            assertEquals(listOf("matched"), immediate.groups)
            assertEquals(listOf("matched"), messageOnly.groups)
        }
    }

    /** Identifier classification wins when the copied id is also visible in a title or message preview. */
    @Test
    fun copiedIdentifierWinsOverMatchingTextDuringMessageOnlySearch() {
        val titleId = "0123456789abcdef".repeat(4)
        val previewId = "fedcba9876543210".repeat(4)
        val candidates =
            listOf(
                candidate("title", titleId, title = "Group $titleId"),
                candidate("preview", previewId, preview = "Shared $previewId"),
            )

        listOf(titleId to "title", previewId to "preview").forEach { (query, expected) ->
            val sections =
                projectChatListSearchCandidates(
                    candidates = candidates,
                    rawQuery = query,
                    messageOnly = true,
                )

            assertEquals(listOf(expected), sections.groups)
        }
    }

    /** Identifier prefixes start at eight hexadecimal characters without turning ordinary hex words into ids. */
    @Test
    fun groupIdentifierPrefixUsesTheMinimumBoundary() {
        val id = "abcdef0123456789".repeat(4)
        val candidates = listOf(candidate("matched", id))

        assertEquals(
            listOf("matched"),
            projectChatListSearchCandidates(candidates, rawQuery = id.take(GROUP_ID_SEARCH_MIN_LENGTH)).groups,
        )
        assertTrue(
            projectChatListSearchCandidates(candidates, rawQuery = id.take(GROUP_ID_SEARCH_MIN_LENGTH - 1))
                .groups
                .isEmpty(),
        )
        assertTrue(projectChatListSearchCandidates(candidates, rawQuery = "cafe").groups.isEmpty())
        assertTrue(projectChatListSearchCandidates(candidates, rawQuery = "abcdef0z").groups.isEmpty())
    }

    private fun withDefaultLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** A message-only filter drops title and metadata chat rows; matching messages are listed separately. */
    @Test
    fun messageOnlyDropsTitleAndMetadataRows() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("title", "02", title = "Marmot"),
                        candidate("metadata", "03", description = "Marmot"),
                    ),
                rawQuery = "marmot",
                messageOnly = true,
            )

        assertTrue(sections.groups.isEmpty())
    }

    private fun candidate(
        value: String,
        groupId: String,
        nostrGroupId: String = groupId,
        title: String = "Unrelated",
        preview: String = "Nothing here",
        description: String = "",
    ) = ChatListSearchCandidate(
        value = value,
        groupIdHex = groupId,
        nostrGroupIdHex = nostrGroupId,
        displayTitle = title,
        previewText = preview,
        description = description,
    )
}
