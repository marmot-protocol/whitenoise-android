package dev.ipf.whitenoise.android.audio

/** A transient playback owner used only to return the shell to its currently playing source. */
internal interface PlaybackConversationDestination {
    val accountRef: String
    val groupIdHex: String
    val messageIdHex: String
    val sessionId: Long
    val ttsFocusSessionId: Long?
}

/** One voice player's source metadata; it is never persisted or used as a protocol-data cache. */
data class VoicePlaybackSource(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val title: String,
)

/** Voice sessions use a separate sign domain so an old speech request cannot adopt a voice player. */
internal data class VoiceConversationDestination(
    val source: VoicePlaybackSource,
    val playerSessionId: Long,
) : PlaybackConversationDestination {
    override val accountRef: String get() = source.accountRef
    override val groupIdHex: String get() = source.groupIdHex
    override val messageIdHex: String get() = source.messageIdHex
    override val sessionId: Long get() = -playerSessionId
    override val ttsFocusSessionId: Long? get() = null
}
