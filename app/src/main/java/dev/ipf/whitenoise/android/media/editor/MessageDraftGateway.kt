package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi

internal interface MessageDraftGateway {
    fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi?

    fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi

    fun delete(
        accountRef: String,
        groupIdHex: String,
    )

    fun summaries(accountRef: String): List<MessageDraftSummaryFfi>

    /** The selected draft with its opaque revision, or null when this gateway cannot write revision-safely. */
    fun selected(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi? = null

    /** Writes only while the store still holds [revision]; gateways without revisions save unconditionally. */
    fun saveIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi = save(accountRef, groupIdHex, content, replyToMessageIdHex, mediaAttachments)

    /** Clears only while the store still holds [revision]; gateways without revisions delete unconditionally. */
    fun clearIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
    ) = delete(accountRef, groupIdHex)
}

internal class MarmotMessageDraftGateway(
    private val marmot: () -> MarmotInterface,
) : MessageDraftGateway {
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = marmot().messageDraft(accountRef, groupIdHex)

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        marmot().saveMessageDraft(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
        )

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = marmot().deleteMessageDraft(accountRef, groupIdHex)

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = marmot().messageDrafts(accountRef)

    /** Reads MDK's selected draft and revision for the group. */
    override fun selected(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi = marmot().selectedMessageDraft(accountRef, groupIdHex)

    /**
     * Revision-safe upsert; a changed selected draft surfaces as `MessageDraftRevisionConflict`. MDK returns
     * descriptors only, so the hydrated draft is rebuilt from the bytes this write submitted.
     */
    override fun saveIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        val saved =
            marmot().saveMessageDraftIfRevision(accountRef, revision, content, replyToMessageIdHex, mediaAttachments)
        val now = System.currentTimeMillis()
        return MessageDraftFfi(
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
            createdAtMs = saved.draft?.createdAtMs ?: now,
            updatedAtMs = saved.draft?.updatedAtMs ?: now,
        )
    }

    /** Revision-safe clear; a changed selected draft surfaces as `MessageDraftRevisionConflict`. */
    override fun clearIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
    ) {
        marmot().clearMessageDraftIfRevision(accountRef, revision)
    }
}
