package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.chats.relaysConnectedOnNetworkChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectivitySignals(
    val hasValidatedInternet: Boolean = false,
    val relaysConnected: Boolean = true,
)

internal class ConnectivitySignalOwner {
    private val mutableSignals = MutableStateFlow(ConnectivitySignals())
    val signals: StateFlow<ConnectivitySignals> = mutableSignals.asStateFlow()
    private val networkLifetime = StalenessGuard()
    private val lock = Any()

    /** Captures the network identity that an asynchronous probe is validating. */
    fun captureNetworkGeneration(): Long = networkLifetime.capture()

    /** Reports whether a captured probe still belongs to the active network identity. */
    fun isNetworkGenerationCurrent(captured: Long): Boolean = networkLifetime.isCurrent(captured)

    /** Publishes connectivity changes and invalidates probes when validation identity changes. */
    fun update(
        hasValidatedInternet: Boolean? = null,
        relaysConnected: Boolean? = null,
    ) {
        synchronized(lock) {
            val current = mutableSignals.value
            val nextHasValidatedInternet = hasValidatedInternet ?: current.hasValidatedInternet
            if (nextHasValidatedInternet != current.hasValidatedInternet) networkLifetime.advance()
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

    /** Invalidates probes after a network callback reports a different network identity. */
    fun noteNetworkIdentityChange() {
        networkLifetime.advance()
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
