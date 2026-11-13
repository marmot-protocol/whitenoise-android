package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayLivenessFfi
import dev.ipf.marmotkit.RelayLivenessSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Complete MDK snapshots for one screen owner. A null snapshot means the stream is unavailable. */
internal fun WhiteNoiseAppState.relayLiveness(
    account: String,
    generation: Int,
): Flow<List<RelayLivenessFfi>?> =
    flow<List<RelayLivenessFfi>?> {
        var subscription: RelayLivenessSubscription? = null
        try {
            // Take ownership before cancellation can discard the newly returned native handle.
            withContext(NonCancellable) {
                if (ownsRelayConnection(account, generation)) {
                    subscription = marmotOrNull()?.subscribeRelayLiveness(account)
                }
            }
            val active =
                subscription ?: run {
                    emit(null)
                    return@flow
                }
            currentCoroutineContext().ensureActive()
            if (ownsRelayConnection(account, generation)) emit(active.snapshot())
            while (ownsRelayConnection(account, generation)) {
                val rows = active.next() ?: break
                if (ownsRelayConnection(account, generation)) emit(rows)
            }
            emit(null)
        } finally {
            withContext(NonCancellable) {
                subscription?.let { active ->
                    try {
                        active.cancel()
                    } finally {
                        active.close()
                    }
                }
            }
        }
    }.catch { failure ->
        if (failure !is Exception) throw failure
        emit(null)
    }.flowOn(Dispatchers.IO)

/** Resolve configured spellings and dial policy with the same parser that supplies the live stream. */
internal suspend fun WhiteNoiseAppState.classifyRelayConnections(
    account: String,
    generation: Int,
    urls: List<String>,
): Map<String, RelayEndpointClassificationFfi> =
    runCatchingCancellable {
        marmotIo {
            if (!ownsRelayConnection(account, generation)) return@marmotIo emptyMap()
            classifyRelayEndpoints(urls).associateBy(RelayEndpointClassificationFfi::endpoint)
        }
    }.getOrDefault(emptyMap())

/** Request one configured connection. Live state continues to come from the subscription. */
internal suspend fun WhiteNoiseAppState.retryRelayConnection(
    account: String,
    generation: Int,
    url: String,
): Boolean =
    runCatchingCancellable {
        // Apply the app's existing resolve-time host check before asking MDK to dial an imported URL.
        val publicHost = withContext(Dispatchers.IO) { relayUrlPassesResolveTimeCheck(url) }
        marmotIo {
            if (!publicHost || !ownsRelayConnection(account, generation)) return@marmotIo false
            reconnectRelay(account, url)
            true
        }
    }.getOrDefault(false)

/** Screen work belongs to the active account and native runtime that supplied the row. */
private fun WhiteNoiseAppState.ownsRelayConnection(
    account: String,
    generation: Int,
): Boolean = activeAccountRef == account && runtimeGeneration == generation
