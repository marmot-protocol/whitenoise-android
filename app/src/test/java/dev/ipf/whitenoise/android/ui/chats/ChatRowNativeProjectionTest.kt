package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRowNativeProjectionTest {
    @Test
    fun nativeDraftPreviewWinsOverLegacyDraftState() {
        val item =
            ChatRowPortFixtures
                .item()
                .copy(
                    selectedPreview =
                        SelectedChatPreviewFfi.Draft(
                            ChatListDraftPreviewFfi(
                                text = "native draft",
                                textTruncated = false,
                                attachmentCount = 0uL,
                                attachmentKind = null,
                            ),
                        ),
                )

        assertEquals("native draft", chatRowDraftPreviewText(item, legacyDraft = "legacy draft"))
    }

    @Test
    fun absentNativePreviewRetainsLegacyRowCompatibility() {
        val item = ChatRowPortFixtures.item()

        assertEquals("legacy draft", chatRowDraftPreviewText(item, legacyDraft = "legacy draft"))
        assertNull(chatRowDraftPreviewText(item, legacyDraft = ""))
    }

    @Test
    fun authoritativeNonDraftPreviewSuppressesStaleLegacyDraft() {
        val message = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Message)
        val invitation = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Invitation)
        val empty = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Empty)

        assertNull(chatRowDraftPreviewText(message, legacyDraft = "stale"))
        assertNull(chatRowDraftPreviewText(invitation, legacyDraft = "stale"))
        assertNull(chatRowDraftPreviewText(empty, legacyDraft = "stale"))
    }
}
