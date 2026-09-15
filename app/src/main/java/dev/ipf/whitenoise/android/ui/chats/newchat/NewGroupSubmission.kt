package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.InitialGroupImageFfi
import dev.ipf.whitenoise.android.media.ImageUploadDraft

/** Immutable values captured before external callbacks or native suspension can change the editing UI. */
internal data class NewGroupSubmission(
    val name: String,
    val description: String?,
    val members: List<String>,
    val image: ImageUploadDraft?,
) {
    /** Maps the submitted draft into the existing encrypted native creation signature, including description. */
    suspend fun createWith(create: suspend (String, List<String>, String?, InitialGroupImageFfi?) -> String): String =
        create(name, members, description, image?.initialGroupImage())
}
