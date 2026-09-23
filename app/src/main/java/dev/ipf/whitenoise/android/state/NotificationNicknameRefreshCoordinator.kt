package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val LOG_ID_PREFIX_LENGTH = 8

/** Reconciles active notification sender lines after an account-scoped nickname change. */
internal class NotificationNicknameRefreshCoordinator(
    private val scope: CoroutineScope,
    private val identity: NotificationIdentityResolver,
    private val presenter: LocalNotificationPresenter,
) {
    /** Refreshes matching notification lines without posting a new alert. */
    fun refresh(
        accountRef: String,
        accountIdHex: String,
    ) {
        scope.launch {
            runCatchingCancellable {
                val senderName =
                    identity.displayNameForAccount(
                        accountRef = accountRef,
                        accountIdHex = accountIdHex,
                        requestMissingProfile = false,
                    )
                presenter.refreshContactSenderName(accountRef, accountIdHex, senderName)
            }.onFailure {
                appStateDebug {
                    "notification nickname refresh failed account=${accountRef.take(LOG_ID_PREFIX_LENGTH)}"
                }
            }
        }
    }
}
