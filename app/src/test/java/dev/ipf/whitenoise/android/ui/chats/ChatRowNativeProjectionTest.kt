package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRowNativeProjectionTest {
    /** Native draft text overrides stale Android-owned draft state. */
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

    /** Rows without native selection retain legacy draft compatibility. */
    @Test
    fun absentNativePreviewRetainsLegacyRowCompatibility() {
        val item = ChatRowPortFixtures.item()

        assertEquals("legacy draft", chatRowDraftPreviewText(item, legacyDraft = "legacy draft"))
        assertNull(chatRowDraftPreviewText(item, legacyDraft = ""))
    }

    /** Authoritative non-draft variants suppress stale legacy drafts. */
    @Test
    fun authoritativeNonDraftPreviewSuppressesStaleLegacyDraft() {
        val message = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Message)
        val invitation = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Invitation)
        val empty = ChatRowPortFixtures.item().copy(selectedPreview = SelectedChatPreviewFfi.Empty)

        assertNull(chatRowDraftPreviewText(message, legacyDraft = "stale"))
        assertNull(chatRowDraftPreviewText(invitation, legacyDraft = "stale"))
        assertNull(chatRowDraftPreviewText(empty, legacyDraft = "stale"))
    }

    /** Native attachment-only drafts remain authoritative over the prior message. */
    @Test
    fun attachmentOnlyNativeDraftRemainsPresent() {
        val item =
            ChatRowPortFixtures.item().copy(
                selectedPreview =
                    SelectedChatPreviewFfi.Draft(
                        ChatListDraftPreviewFfi(
                            text = "",
                            textTruncated = false,
                            attachmentCount = 2uL,
                            attachmentKind = ChatListAttachmentKindFfi.PHOTO,
                        ),
                    ),
            )

        val draft = requireNotNull(chatRowDraftPreview(item, legacyDraft = "stale"))
        assertNull(draft.text)
        assertEquals(ChatListAttachmentKindFfi.PHOTO, draft.attachmentKind)
        assertEquals(2u, draft.attachmentCount)
    }

    /** Resource trimming restores Latin spacing without adding it after full-width punctuation. */
    @Test
    fun localizedDraftPrefixUsesNaturalSpacing() {
        assertEquals("Draft: Photo", chatRowDraftText("Draft:", "Photo"))
        assertEquals("草稿：照片", chatRowDraftText("草稿：", "照片"))
    }
}
