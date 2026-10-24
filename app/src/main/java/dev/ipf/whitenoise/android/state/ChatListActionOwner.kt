package dev.ipf.whitenoise.android.state

/** Captures the presentation owner before an asynchronous row action is admitted. */
internal data class ChatListActionOwner(
    val accountRef: String,
    val bindEpoch: Long,
    val runtimeGeneration: Int,
    val activeAccountRef: String?,
    val presentationIsCurrent: () -> Boolean = { true },
)
