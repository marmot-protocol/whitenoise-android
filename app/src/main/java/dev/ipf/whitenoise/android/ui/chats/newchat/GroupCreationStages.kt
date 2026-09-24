package dev.ipf.whitenoise.android.ui.chats.newchat

/** Opens only the canonical ID returned by create/recovery, with ownership checks at each boundary. */
internal suspend fun runGroupCreationStages(
    owner: GroupCreationSession,
    createOrRetry: suspend () -> String?,
    openCurrentChat: suspend (String) -> Unit,
) {
    owner.ensureCurrent()
    val canonicalId = createOrRetry() ?: return
    owner.ensureCurrent()
    openCurrentChat(canonicalId)
}
