package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.ImageUploadDraft

internal fun submittedNewGroupName(value: TextFieldValue): String = value.text.trim()

/** Details stay editable until creation is busy or a canonical retry pins the group. */
internal fun newGroupDetailsEditable(
    retryGroupIdHex: String?,
    busy: Boolean,
    imagePreparing: Boolean,
): Boolean = retryGroupIdHex == null && !busy && !imagePreparing

/** Freeze the normalized recipients, text and prepared image at the original submission boundary. */
internal fun captureNewGroupSubmission(
    draft: NewGroupDraft,
    members: List<RecipientSearch.Candidate>,
    image: ImageUploadDraft?,
    retentionSecs: Long,
): NewGroupSubmission {
    val recipients =
        newChatMemberRefs(
            directMessage = false,
            normalizedPendingRecipients = emptyList(),
            initialMemberRefs = members.map { it.accountIdHex },
        )
    return NewGroupSubmission(
        name =
            draft.name.text
                .toString()
                .trim(),
        description =
            draft.description.text
                .toString()
                .trim()
                .takeIf { it.isNotEmpty() },
        members = recipients,
        image = image,
        disappearingMessageSecs = retentionSecs,
    )
}
