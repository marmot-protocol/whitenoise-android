package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun WhiteNoiseAppState.hiddenMessageIdsInGroup(
    accountRef: String?,
    groupIdHex: String,
): Set<String> {
    val key = MessageHidePreferences.preferenceKey(accountRef, groupIdHex) ?: return emptySet()
    return hiddenMessageIdsByAccountGroup[key]?.value
        ?: MessageHidePreferences.readHiddenMessageIdsByKey(preferences, key)
}

/**
 * Hides one message on this device only, persisting it for its account and conversation, and retracts any
 * notification content that carried it (#3323). Returns whether the hide was stored.
 */
suspend fun WhiteNoiseAppState.hideMessageForMe(
    accountRef: String?,
    groupIdHex: String,
    messageIdHex: String,
): Boolean {
    val hidden =
        hiddenMessageMutationMutex.withLock {
            val key = MessageHidePreferences.preferenceKey(accountRef, groupIdHex) ?: return@withLock false
            val updated =
                withContext(Dispatchers.IO) {
                    MessageHidePreferences.hideMessage(preferences, accountRef, groupIdHex, messageIdHex)
                }
                    ?: return@withLock false
            val state = hiddenMessageIdsByAccountGroup.getOrPut(key) { mutableStateOf(updated) }
            state.value = updated
            true
        }
    // A successful local hide retracts the message's notification content too (#3323).
    if (hidden && accountRef != null) {
        // The hide is already persisted, so its retraction completes even if the caller is cancelled.
        withContext(NonCancellable) {
            retractNotificationContent(RemovedNotificationMessage(accountRef, groupIdHex, messageIdHex))
        }
    }
    return hidden
}

suspend fun WhiteNoiseAppState.clearHiddenMessagesForAccount(accountRef: String): Boolean =
    withContext(NonCancellable) {
        hiddenMessageMutationMutex.withLock {
            val prefix = MessageHidePreferences.accountKeyPrefix(accountRef) ?: return@withLock false
            val cleared =
                withContext(Dispatchers.IO) {
                    MessageHidePreferences.clearAccount(preferences, accountRef)
                }
            if (!cleared) return@withLock false
            hiddenMessageIdsByAccountGroup.removeAll { it.startsWith(prefix) }
            true
        }
    }
