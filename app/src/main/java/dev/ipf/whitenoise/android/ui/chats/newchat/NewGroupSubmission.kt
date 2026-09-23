package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.whitenoise.android.media.ImageUploadDraft

private typealias CreateGroupWithOptions = suspend (String, List<String>, CreateGroupOptionsFfi) -> String

/** Immutable values captured before external callbacks or native suspension can change the editing UI. */
internal data class NewGroupSubmission(
    val name: String,
    val description: String?,
    val members: List<String>,
    val image: ImageUploadDraft?,
    val disappearingMessageSecs: Long = 0L,
) {
    /** Maps one immutable submission into MDK's atomic founding-options boundary. */
    suspend fun createWith(create: CreateGroupWithOptions): String =
        create(
            name,
            members,
            CreateGroupOptionsFfi(
                description = description,
                initialImage = image?.initialGroupImage(),
                disappearingMessageSecs = disappearingMessageSecs.toULong(),
            ),
        )
}
