package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChatListSearchProjectionTest {
    @Test
    fun titleMatchesLeadStableGroupsAndBodyOnlyMatchesStayInMessages() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("body", "01"),
                        candidate("preview", "02", preview = "Marmot appears here"),
                        candidate("title-z", "03", title = "Marmot Z"),
                        candidate("title-a", "04", title = "Marmot A"),
                        candidate("description", "05", description = "Marmot planning"),
                    ),
                rawQuery = "marmot",
                bodyMatchGroupIds = setOf("01", "04"),
            )

        assertEquals(listOf("title-z", "title-a", "preview", "description"), sections.groups)
        assertEquals(listOf("body"), sections.messages)
        assertEquals(sections.orderedItems().size, sections.orderedItems().distinct().size)
    }

    @Test
    fun canonicalDuplicateUsesStrongestClassificationWithoutDuplicatingRow() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("body-copy", "AAbb"),
                        candidate("title-copy", "aabb", title = "Marmot group"),
                    ),
                rawQuery = "marmot",
                bodyMatchGroupIds = setOf("AaBb"),
            )

        assertEquals(listOf("title-copy"), sections.groups)
        assertTrue(sections.messages.isEmpty())
    }

    @Test
    fun metadataAndMessageOrderRemainStableAcrossBodyResultSetOrder() {
        val candidates =
            listOf(
                candidate("message-first", "aa"),
                candidate("metadata", "bb", description = "needle"),
                candidate("message-second", "cc"),
            )

        val first =
            projectChatListSearchCandidates(
                candidates = candidates,
                rawQuery = "needle",
                bodyMatchGroupIds = linkedSetOf("cc", "aa"),
            )
        val second =
            projectChatListSearchCandidates(
                candidates = candidates,
                rawQuery = "needle",
                bodyMatchGroupIds = linkedSetOf("aa", "cc"),
            )

        assertEquals(listOf("metadata"), first.groups)
        assertEquals(listOf("message-first", "message-second"), first.messages)
        assertEquals(first, second)
    }

    @Test
    fun folderAndBodyIdentityUseTheSameLocaleInvariantCanonicalId() {
        val sections =
            projectChatListSearchCandidates(
                candidates = listOf(candidate("included", "ABCDEF"), candidate("excluded", "123456")),
                rawQuery = "message-only",
                bodyMatchGroupIds = setOf("abcdef", "123456"),
                folderChatIds = setOf("AbCdEf"),
            )

        assertTrue(sections.groups.isEmpty())
        assertEquals(listOf("included"), sections.messages)
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
            assertTrue(sections.messages.isEmpty())
        }

    /** Full copied MLS and Nostr identifiers stay in Groups before and after body-search completion. */
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
            val afterBodySearch =
                projectChatListSearchCandidates(
                    candidates,
                    rawQuery = query,
                    bodyMatchGroupIds = setOf("a".repeat(64), mlsId),
                    messageOnly = true,
                )

            assertEquals(listOf("matched"), immediate.groups)
            assertEquals(listOf("matched"), afterBodySearch.groups)
            assertEquals(listOf("other"), afterBodySearch.messages)
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
                    bodyMatchGroupIds = setOf(titleId, previewId),
                    messageOnly = true,
                )

            assertEquals(listOf(expected), sections.groups)
            assertEquals(listOf(if (expected == "title") "preview" else "title"), sections.messages)
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

    /** Message only keeps body matches as messages and drops title matches. */
    @Test
    fun messageOnlyKeepsBodyMatchesAsMessagesAndDropsTitleMatches() {
        val sections =
            projectChatListSearchCandidates(
                candidates =
                    listOf(
                        candidate("body", "01"),
                        candidate("title-with-body", "02", title = "Marmot"),
                        candidate("title-only", "03", title = "Marmot Z"),
                    ),
                rawQuery = "",
                bodyMatchGroupIds = setOf("01", "02"),
                messageOnly = true,
            )

        assertTrue(sections.groups.isEmpty())
        assertEquals(listOf("body", "title-with-body"), sections.messages)
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
