package dev.ipf.whitenoise.android.ui.chats.newchat

/** Separates accepted captured-account policy from UI ownership without retrying or undoing native creation. */
internal suspend fun runGroupCreationStages(
    owner: GroupCreationSession,
    createOrRetry: suspend () -> String?,
    applyCapturedPolicy: suspend (String) -> Unit,
    openCurrentChat: suspend (String) -> Unit,
) {
    owner.ensureCurrent()
    val canonicalId = createOrRetry() ?: return
    owner.ensureNativeCurrent()
    applyCapturedPolicy(canonicalId)
    owner.ensureCurrent()
    openCurrentChat(canonicalId)
}
