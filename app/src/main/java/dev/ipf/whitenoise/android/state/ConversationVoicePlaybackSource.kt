package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.core.GroupTitleCopy

/** Captures one Android player owner only while its originating conversation can still accept actions. */
internal fun ConversationController.voicePlaybackSource(
    appState: WhiteNoiseAppState,
    messageId: String,
    titleCopy: GroupTitleCopy,
    focusMessage: Boolean = true,
): VoicePlaybackSource? {
    val account = boundAccountRef
    if (account == null ||
        appState.activeAccountRef != account ||
        !acceptsConversationActionOwner(account, group.groupIdHex)
    ) {
        return null
    }
    return VoicePlaybackSource(account, group.groupIdHex, messageId, title(titleCopy), focusMessage)
}
