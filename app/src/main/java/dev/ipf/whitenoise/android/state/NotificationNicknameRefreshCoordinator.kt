package dev.ipf.whitenoise.android.state

import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val LOG_ID_PREFIX_LENGTH = 8

/** Reconciles active notification sender lines after an account-scoped nickname change. */
internal class NotificationNicknameRefreshCoordinator
    @VisibleForTesting
    internal constructor(
        private val scope: CoroutineScope,
        private val resolveSenderName: suspend (String, String) -> String,
        private val refreshSenderName: suspend (String, String, String, () -> Boolean) -> Unit,
    ) {
        private val generationLock = Any()
        private val generations = mutableMapOf<NicknameRefreshKey, NicknameRefreshState>()

        /** Connects production identity resolution and serialized notification publication. */
        internal constructor(
            scope: CoroutineScope,
            identity: NotificationIdentityResolver,
            presenter: LocalNotificationPresenter,
        ) : this(
            scope = scope,
            resolveSenderName = { accountRef, accountIdHex ->
                identity.displayNameForAccount(
                    accountRef = accountRef,
                    accountIdHex = accountIdHex,
                    requestMissingProfile = false,
                )
            },
            refreshSenderName = { accountRef, accountIdHex, senderName, isCurrent ->
                presenter.refreshContactSenderName(accountRef, accountIdHex, senderName, isCurrent)
            },
        )

        /** Refreshes matching notification lines only while this remains the newest edit for the contact. */
        fun refresh(
            accountRef: String,
            accountIdHex: String,
        ) {
            val token = beginRefresh(accountRef, accountIdHex)
            scope.launch {
                try {
                    runCatchingCancellable {
                        val senderName = resolveSenderName(accountRef, accountIdHex)
                        refreshSenderName(accountRef, accountIdHex, senderName) { isCurrent(token) }
                    }.onFailure {
                        appStateDebug {
                            "notification nickname refresh failed account=${accountRef.take(LOG_ID_PREFIX_LENGTH)}"
                        }
                    }
                } finally {
                    finishRefresh(token)
                }
            }
        }

        /** Claims the next edit generation while retaining state until every overlapping refresh finishes. */
        private fun beginRefresh(
            accountRef: String,
            accountIdHex: String,
        ): NicknameRefreshToken =
            synchronized(generationLock) {
                val key = NicknameRefreshKey(accountRef, accountIdHex)
                val state = generations.getOrPut(key) { NicknameRefreshState() }
                state.generation += 1
                state.activeRefreshes += 1
                NicknameRefreshToken(key, state, state.generation)
            }

        /** Checks newest-edit ownership at the presenter's serialized card-write boundary. */
        private fun isCurrent(token: NicknameRefreshToken): Boolean =
            synchronized(generationLock) {
                generations[token.key] === token.state && token.state.generation == token.generation
            }

        /** Releases one refresh and removes its per-contact state after the final overlap completes. */
        private fun finishRefresh(token: NicknameRefreshToken) {
            synchronized(generationLock) {
                token.state.activeRefreshes -= 1
                if (token.state.activeRefreshes == 0) generations.remove(token.key, token.state)
            }
        }

        private data class NicknameRefreshKey(
            val accountRef: String,
            val accountIdHex: String,
        )

        private data class NicknameRefreshToken(
            val key: NicknameRefreshKey,
            val state: NicknameRefreshState,
            val generation: Long,
        )

        private data class NicknameRefreshState(
            var generation: Long = 0,
            var activeRefreshes: Int = 0,
        )
    }
