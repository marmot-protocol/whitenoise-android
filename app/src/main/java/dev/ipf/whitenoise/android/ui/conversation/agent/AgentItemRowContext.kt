package dev.ipf.whitenoise.android.ui.conversation.agent

/** Where the row sits in the transcript and whether the viewer may act from it. */
internal data class AgentItemRowContext(
    /** True on the first row of the agent's sender run, which names the agent. */
    val showSenderName: Boolean,
    /** True while the conversation cannot send, so cards show their state without actions. */
    val readOnly: Boolean,
    /** The last row of a sender run carries its avatar in the chat. */
    val showSenderAvatar: Boolean = false,
)
