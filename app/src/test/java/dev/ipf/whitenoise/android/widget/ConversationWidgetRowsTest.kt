package dev.ipf.whitenoise.android.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWidgetRowsTest {
    private val rows =
        listOf(
            ConversationWidgetRow("a", "shared-id", "First account", true, "private A", 2u),
            ConversationWidgetRow("b", "shared-id", "Second account", false, "private B", 3u),
            ConversationWidgetRow("a", "other", "Older", false, "old private A", 1u),
        )

    @Test fun singleAccountDefaultRedactsPreviewsAndNeverUsesAnotherAccountsMatchingGroupId() {
        val selected = selectConversationWidgetRows(ConversationWidgetChoice("a"), setOf("a", "b"), true, rows, 8)
        assertEquals(listOf("a", "a"), selected.map { it.account })
        assertEquals(listOf("shared-id", "other"), selected.map { it.group })
        selected.forEach { assertNull(it.preview) }
    }

    @Test fun explicitAllAccountsRetainsDistinctAccountIdentityAndSortsByNativeActivity() {
        val selected =
            selectConversationWidgetRows(
                ConversationWidgetChoice("a", allAccounts = true),
                setOf("a", "b"),
                true,
                rows,
                8,
            )
        assertEquals(listOf("b", "a", "a"), selected.map { it.account })
        assertEquals(3, selected.size)
    }

    @Test fun lockedOrRemovedAccountProducesNoSensitiveRows() {
        assertTrue(
            selectConversationWidgetRows(
                ConversationWidgetChoice("a", previews = true),
                setOf("a", "b"),
                false,
                rows,
                8,
            ).isEmpty(),
        )
        assertTrue(
            selectConversationWidgetRows(
                ConversationWidgetChoice("a", allAccounts = true, previews = true),
                setOf("b"),
                true,
                rows,
                8,
            ).isEmpty(),
        )
    }

    @Test fun unreadSelectionIgnoresLegacyPreviewOptInAndRemainsBounded() {
        val selected =
            selectConversationWidgetRows(
                ConversationWidgetChoice("a", previews = true, unreadOnly = true),
                setOf("a", "b"),
                true,
                rows,
                8,
            )
        assertEquals(1, selected.size)
        assertNull(selected.single().preview)
        val many = (1..100).map { rows.first().copy(group = "$it") }
        assertEquals(8, selectConversationWidgetRows(ConversationWidgetChoice("a"), setOf("a"), true, many, 100).size)
    }
}
