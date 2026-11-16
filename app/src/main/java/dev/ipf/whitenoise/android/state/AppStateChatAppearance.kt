package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateOf
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AccountBubbleColorSlot(
    val accountRef: String,
    val theme: BubbleTheme,
    val side: BubbleSide,
)

internal data class AccountActionColorSlot(
    val accountRef: String,
    val theme: BubbleTheme,
)

internal fun WhiteNoiseAppState.globalBubbleColorArgb(
    theme: BubbleTheme,
    side: BubbleSide,
): Long? {
    val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val slot = AccountBubbleColorSlot(account, theme, side)
    return globalBubbleColors
        .getOrPut(slot) {
            mutableStateOf(BubbleColorPreferences.readGlobalColor(preferences, account, theme, side))
        }.value
}

internal fun WhiteNoiseAppState.chatBubbleColorArgb(
    groupIdHex: String,
    side: BubbleSide,
): Long? {
    val key = BubbleColorPreferences.chatKey(activeAccountRef, groupIdHex, side) ?: return null
    return chatBubbleColors
        .getOrPut(key) {
            mutableStateOf(BubbleColorPreferences.readChatColor(preferences, activeAccountRef, groupIdHex, side))
        }.value
}

internal fun WhiteNoiseAppState.effectiveBubbleColorArgb(
    theme: BubbleTheme,
    side: BubbleSide,
    groupIdHex: String,
): Long? = chatBubbleColorArgb(groupIdHex, side) ?: globalBubbleColorArgb(theme, side)

internal fun WhiteNoiseAppState.updateGlobalBubbleColor(
    theme: BubbleTheme,
    side: BubbleSide,
    argb: Long?,
) {
    val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return
    BubbleColorPreferences.writeGlobalColor(preferences, account, theme, side, argb)
    val color = BubbleColorPreferences.readGlobalColor(preferences, account, theme, side)
    globalBubbleColors
        .getOrPut(AccountBubbleColorSlot(account, theme, side)) { mutableStateOf(color) }
        .value = color
}

internal fun WhiteNoiseAppState.actionColorArgb(
    theme: BubbleTheme,
    accountRef: String? = activeAccountRef,
): Long? {
    val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val slot = AccountActionColorSlot(account, theme)
    return actionColors
        .getOrPut(slot) {
            mutableStateOf(ActionColorPreferences.readColor(preferences, account, theme))
        }.value
}

internal fun WhiteNoiseAppState.updateActionColor(
    theme: BubbleTheme,
    argb: Long?,
) {
    val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return
    ActionColorPreferences.writeColor(preferences, account, theme, argb)
    val color = ActionColorPreferences.readColor(preferences, account, theme)
    actionColors
        .getOrPut(AccountActionColorSlot(account, theme)) { mutableStateOf(color) }
        .value = color
}

internal fun WhiteNoiseAppState.updateChatBubbleColor(
    groupIdHex: String,
    side: BubbleSide,
    argb: Long?,
) {
    val key = BubbleColorPreferences.chatKey(activeAccountRef, groupIdHex, side) ?: return
    BubbleColorPreferences.writeChatColor(preferences, activeAccountRef, groupIdHex, side, argb)
    val color = BubbleColorPreferences.readChatColor(preferences, activeAccountRef, groupIdHex, side)
    chatBubbleColors.getOrPut(key) { mutableStateOf(color) }.value = color
}

/**
 * Local UI preference for the active account's per-group long-message collapse
 * gate (#1180). Default ON preserves the existing Read More behavior for every
 * account/group until the user disables it from that group's details screen.
 */
fun WhiteNoiseAppState.collapseLongMessagesInGroup(groupIdHex: String): Boolean =
    longMessageCollapseState.collapseLongMessages(activeAccountRef, groupIdHex)

fun WhiteNoiseAppState.updateCollapseLongMessagesInGroup(
    groupIdHex: String,
    enabled: Boolean,
) {
    longMessageCollapseState.updateCollapseLongMessages(activeAccountRef, groupIdHex, enabled)
}

/** Creates the immutable target channel before making it the persisted active choice. */
fun WhiteNoiseAppState.setConversationVibrationPattern(
    groupIdHex: String,
    isDm: Boolean,
    conversationTitle: String,
    pattern: ConversationVibrationPattern,
) {
    val accountRef = activeAccountRef ?: return
    val shortcutId = conversationShortcutId(accountRef, groupIdHex) ?: return
    mutationsScope.launch {
        // Channel creation performs NotificationManager Binder calls. Keep
        // them off the UI dispatcher and serialize rapid choices so an
        // older operation cannot overwrite the newest persisted choice.
        conversationVibrationChannelMutex.withLock {
            val previous = conversationVibrationPreferences.pattern(accountRef, groupIdHex)
            if (previous == pattern) return@withLock
            val activeChannelId =
                withContext(Dispatchers.Default) {
                    runCatchingCancellable {
                        NotificationChannels.ensureChannels(appContext)
                        ConversationNotificationChannels.ensureConversationChannel(
                            context = appContext,
                            parentChannelId = ConversationNotificationChannels.primaryMessageParent(isDm).id,
                            conversationShortcutId = shortcutId,
                            conversationTitle = conversationTitle,
                            vibrationPattern = pattern,
                            sourceVibrationPattern = previous,
                        )
                    }.onFailure { error ->
                        appStateDebug(error) {
                            "conversation vibration channel failed group=${groupIdHex.take(8)}"
                        }
                    }.getOrNull()
                }
            // Never persist an app-only value when Android could not create
            // the channel that future posts and settings links need.
            if (activeChannelId != null) {
                conversationVibrationPreferences.setPattern(accountRef, groupIdHex, pattern)
            }
        }
    }
}
