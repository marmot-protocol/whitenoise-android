package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.launch

/** The engine's durable mute projection for the chat, from the live list. */
fun WhiteNoiseAppState.engineConversationMuted(groupIdHex: String): Boolean =
    (chatsController?.items.orEmpty() + chatsController?.archivedItems.orEmpty())
        .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        ?.engineMuted() == true

/** Awaitable batch boundary, serialized by the same repository as single-chat mute commands. */
@Suppress("ReturnCount") // Owner fences bracket the awaited native command and its committed result.
internal suspend fun WhiteNoiseAppState.setConversationMutedAwaited(
    account: String,
    groupId: String,
    muted: Boolean,
    isCurrent: () -> Boolean,
): Boolean {
    if (!isCurrent()) return false
    return chatMuteOwner.admit(ChatMutePreferences.compositeKey(account, groupId)).execute {
        val result = muteRepositoryCommand(account, groupId, muted, null, isCurrent)
        if (!isCurrent()) return@execute false
        result.fold(
            onSuccess = {
                commit(it)
                true
            },
            onFailure = { false },
        )
    }
}

fun WhiteNoiseAppState.setConversationMuted(
    groupIdHex: String,
    muted: Boolean,
    onCommitted: () -> Unit = {},
    isCurrent: () -> Boolean = { true },
    completionIsCurrent: () -> Boolean = isCurrent,
) {
    val accountRef = activeAccountRef ?: return
    submitMuteCommand(accountRef, groupIdHex, muted, null, onCommitted, isCurrent, completionIsCurrent)
}

/** Mute the chat for [durationMillis], auto-restoring the current mode after. */
fun WhiteNoiseAppState.muteConversationFor(
    groupIdHex: String,
    durationMillis: Long,
) {
    val accountRef = activeAccountRef ?: return
    val expiry = if (durationMillis <= 0) null else System.currentTimeMillis() + durationMillis
    submitMuteCommand(accountRef, groupIdHex, true, expiry)
}

/** Mute the chat until the exact future Unix epoch-millisecond [expiryMillis]. */
fun WhiteNoiseAppState.muteConversationUntil(
    groupIdHex: String,
    expiryMillis: Long,
) {
    val accountRef = activeAccountRef ?: return
    if (expiryMillis > System.currentTimeMillis()) submitMuteCommand(accountRef, groupIdHex, true, expiryMillis)
}

/** Sends the command to MDK; UI changes only after MDK returns its authoritative settings. */
@Suppress("LongParameterList") // Admission and committed-result ownership differ after presentation retires.
private fun WhiteNoiseAppState.submitMuteCommand(
    accountRef: String,
    groupIdHex: String,
    muted: Boolean,
    mutedUntilMs: Long?,
    onCommitted: () -> Unit = {},
    isCurrent: () -> Boolean = { true },
    completionIsCurrent: () -> Boolean = isCurrent,
) {
    val runtime = runtimeGeneration
    val ownsSession = { activeAccountRef == accountRef && runtimeGeneration == runtime }
    val command = chatMuteOwner.admit(ChatMutePreferences.compositeKey(accountRef, groupIdHex))
    mutationsScope.launch {
        command.execute<Unit> {
            if (!ownsSession() || !isCurrent()) return@execute
            muteRepositoryCommand(accountRef, groupIdHex, muted, mutedUntilMs) { ownsSession() && isCurrent() }
                .onSuccess {
                    if (ownsSession() && completionIsCurrent()) {
                        commit(it)
                        onCommitted()
                    }
                }.onFailure {
                    if (ownsSession() && isCurrent()) {
                        presentFailure(R.string.toast_couldnt_update_notifications, "CHAT_MUTE_UPDATE", it)
                    }
                }
        }
    }
}

private suspend fun WhiteNoiseAppState.muteRepositoryCommand(
    accountRef: String,
    groupIdHex: String,
    muted: Boolean,
    mutedUntilMs: Long?,
    isCurrent: () -> Boolean,
): Result<dev.ipf.marmotkit.ChatNotificationSettingsFfi> =
    if (muted) {
        chatMuteRepository.setMuted(accountRef, groupIdHex, mutedUntilMs, isCurrent)
    } else {
        chatMuteRepository.clearMuted(accountRef, groupIdHex, isCurrent)
    }

suspend fun WhiteNoiseAppState.authoritativeConversationMuteSettings(groupIdHex: String) =
    activeAccountRef?.let { accountRef -> chatMuteRepository.settings(accountRef, groupIdHex).getOrNull() }

/** Remaining timed-mute expiry (epoch millis) for the chat, or null. */
fun WhiteNoiseAppState.conversationMuteExpiryMillis(groupIdHex: String): Long? {
    val accountRef = activeAccountRef ?: return null
    val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
    return chatMuteOwner.mutedUntilMs(key, System.currentTimeMillis()) {
        (chatsController?.items.orEmpty() + chatsController?.archivedItems.orEmpty())
            .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            ?.projection
            ?.mutedUntilMs
    }
}
