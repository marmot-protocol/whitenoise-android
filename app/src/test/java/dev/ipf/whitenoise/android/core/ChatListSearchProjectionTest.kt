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

    private fun candidate(
        value: String,
        groupId: String,
        title: String = "Unrelated",
        preview: String = "Nothing here",
        description: String = "",
    ) = ChatListSearchCandidate(
        value = value,
        groupIdHex = groupId,
        nostrGroupIdHex = groupId,
        displayTitle = title,
        previewText = preview,
        description = description,
    )
}
