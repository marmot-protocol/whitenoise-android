package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Owns Android listener cleanup; admission, generations and native lifetime stay with their existing owners. */
internal class AppRuntimeListenerTeardownOwner(
    private val cancelNetworkRecovery: suspend () -> Unit,
    private val cancelPushWakeDrain: suspend () -> Unit,
    private val cancelListener: suspend () -> Unit,
    private val clearUnreadRefresh: suspend () -> Unit,
    private val receiverActive: MutableStateFlow<Boolean>,
) {
    suspend fun stopForAccountTeardown() {
        cancelNetworkRecovery()
        cancelPushWakeDrain()
        cancelListener()
        clearUnreadRefresh()
    }

    suspend fun withNotificationSubscription(
        subscription: AppNotificationSubscription,
        consume: suspend () -> Unit,
    ) {
        receiverActive.value = true
        try {
            consume()
        } finally {
            receiverActive.value = false
            runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    subscription.close()
                }
            }
        }
    }
}
