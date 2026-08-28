package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.chats.relaysConnectedOnNetworkChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class ConnectivitySignals(
    val hasValidatedInternet: Boolean = false,
    val relaysConnected: Boolean = true,
)

internal class ConnectivitySignalOwner {
    private val mutableSignals = MutableStateFlow(ConnectivitySignals())
    val signals: StateFlow<ConnectivitySignals> = mutableSignals.asStateFlow()
    val networkGeneration = AtomicLong(0)
    private val lock = Any()

    fun update(
        hasValidatedInternet: Boolean? = null,
        relaysConnected: Boolean? = null,
    ) {
        synchronized(lock) {
            val current = mutableSignals.value
            val nextHasValidatedInternet = hasValidatedInternet ?: current.hasValidatedInternet
            if (nextHasValidatedInternet != current.hasValidatedInternet) networkGeneration.incrementAndGet()
            mutableSignals.value =
                current.copy(
                    hasValidatedInternet = nextHasValidatedInternet,
                    relaysConnected =
                        relaysConnectedOnNetworkChange(
                            isOnline = nextHasValidatedInternet,
                            cached = relaysConnected ?: current.relaysConnected,
                        ),
                )
        }
    }

    fun noteNetworkIdentityChange() {
        networkGeneration.incrementAndGet()
    }
}

internal data class AccountCatchUpKey(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val networkGeneration: Long,
    val readinessToken: ChatListConnectionEvidenceToken?,
)

internal class AccountCatchUpCoordinator(
    private val scope: CoroutineScope,
) {
    private var activeJob: Deferred<Boolean>? = null
    private var activeKey: AccountCatchUpKey? = null
    private val lock = Any()

    fun launch(
        key: AccountCatchUpKey,
        block: suspend () -> Boolean,
    ): Deferred<Boolean> =
        synchronized(lock) {
            activeJob?.takeIf { it.isActive && activeKey == key }
                ?: scope.async { block() }.also {
                    activeJob = it
                    activeKey = key
                }
        }
}
