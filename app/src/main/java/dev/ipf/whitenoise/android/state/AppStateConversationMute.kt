package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern

fun WhiteNoiseAppState.isConversationMuted(groupIdHex: String): Boolean {
    val accountRef = activeAccountRef ?: return false
    val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
    return chatMuteOwner.muted(key, System.currentTimeMillis()) {
        engineConversationMuted(groupIdHex)
    }
}

fun WhiteNoiseAppState.isConversationMutePending(groupIdHex: String): Boolean {
    val accountRef = activeAccountRef ?: return false
    return chatMuteOwner.isPending(ChatMutePreferences.compositeKey(accountRef, groupIdHex))
}

/** Group details' muted flag and expiry; [row] and [targeted] are the screen's own MDK reads. */
internal fun WhiteNoiseAppState.groupDetailsMuteDisplay(
    groupIdHex: String,
    row: ChatListRowFfi?,
    targeted: dev.ipf.marmotkit.ChatNotificationSettingsFfi?,
): ChatMuteDisplay =
    chatMuteOwner.display(
        key = activeAccountRef?.let { ChatMutePreferences.compositeKey(it, groupIdHex) },
        nowMillis = System.currentTimeMillis(),
    ) {
        ChatMuteDisplay(
            muted = row?.muted ?: targeted?.muted ?: engineConversationMuted(groupIdHex),
            mutedUntilMs = row?.mutedUntilMs ?: targeted?.mutedUntilMs ?: conversationMuteExpiryMillis(groupIdHex),
        )
    }

fun WhiteNoiseAppState.conversationNotifyMode(groupIdHex: String): ChatNotifyMode {
    val accountRef = activeAccountRef ?: return ChatNotifyMode.ALL
    return chatMutePreferences.mode(accountRef, groupIdHex)
}

fun WhiteNoiseAppState.conversationVibrationPattern(groupIdHex: String): ConversationVibrationPattern {
    val accountRef = activeAccountRef ?: return ConversationVibrationPattern.SYSTEM_DEFAULT
    return conversationVibrationPreferences.pattern(accountRef, groupIdHex)
}

/** The All/Only-mentions preference to show when the chat isn't muted. */
fun WhiteNoiseAppState.conversationRestoreNotifyMode(groupIdHex: String): ChatNotifyMode {
    val accountRef = activeAccountRef ?: return ChatNotifyMode.ALL
    return chatMutePreferences.restoreNotifyMode(accountRef, groupIdHex)
}

fun WhiteNoiseAppState.setConversationNotifyMode(
    groupIdHex: String,
    mode: ChatNotifyMode,
) {
    val accountRef = activeAccountRef ?: return
    if (mode == ChatNotifyMode.NONE) {
        setConversationMuted(groupIdHex, true)
    } else {
        chatMutePreferences.setNotifyForMode(accountRef, groupIdHex, mode)
        setConversationMuted(groupIdHex, false)
    }
}

internal fun WhiteNoiseAppState.acceptAuthoritativeMuteProjection(
    accountRef: String?,
    groupIdHex: String,
    muted: Boolean,
    mutedUntilMs: Long?,
) {
    accountRef ?: return
    chatMuteOwner.acceptProjection(ChatMutePreferences.compositeKey(accountRef, groupIdHex), muted, mutedUntilMs)
}

fun WhiteNoiseAppState.setConversationNotifyForMode(
    groupIdHex: String,
    mode: ChatNotifyMode,
) {
    val accountRef = activeAccountRef ?: return
    chatMutePreferences.setNotifyForMode(accountRef, groupIdHex, mode)
}
