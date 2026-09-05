package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.HostSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

/** Result of selecting and using relay sources for one KeyPackage deletion. */
internal sealed interface KeyPackageDeletionResult {
    data object Deleted : KeyPackageDeletionResult

    data object NoUsableRelay : KeyPackageDeletionResult

    data object HostVerificationUnavailable : KeyPackageDeletionResult

    data object Superseded : KeyPackageDeletionResult

    data class Failed(
        val cause: Throwable,
    ) : KeyPackageDeletionResult
}

private sealed interface KeyPackageDeletionRelaySelection {
    data class Ready(
        val relays: List<String>,
    ) : KeyPackageDeletionRelaySelection

    data object NoUsableRelay : KeyPackageDeletionRelaySelection

    data object HostVerificationUnavailable : KeyPackageDeletionRelaySelection

    data object Superseded : KeyPackageDeletionRelaySelection
}

/**
 * Applies the deletion-only relay boundary to MDK-provided source relays.
 *
 * External public WSS endpoints are accepted here because MDK owns their
 * protocol provenance. Its endpoint classifier removes invalid, unsafe, and
 * retired peers one-by-one before Android applies a bounded resolve-time
 * public-address check. DNS can change between this check and the native dial;
 * TLS validation and MDK's literal/localhost checks remain the dial boundary.
 */
internal suspend fun deleteKeyPackageThroughSafeSourceRelays(
    sourceRelays: List<String>,
    classify: suspend (List<String>) -> List<RelayEndpointClassificationFfi>,
    resolve: RelayHostResolver,
    accountStillActive: () -> Boolean = { true },
    delete: suspend (List<String>) -> Unit,
): KeyPackageDeletionResult =
    if (!accountStillActive()) {
        KeyPackageDeletionResult.Superseded
    } else {
        runCatchingCancellable {
            selectKeyPackageDeletionRelays(sourceRelays, classify, resolve, accountStillActive)
        }.fold(
            onSuccess = { selection -> executeSelectedKeyPackageDeletion(selection, accountStillActive, delete) },
            onFailure = { failure -> KeyPackageDeletionResult.Failed(failure) },
        ).let { result ->
            // A failure or unusable DNS answer can also finish after an account switch.
            if (accountStillActive()) result else KeyPackageDeletionResult.Superseded
        }
    }

/** Executes only a fully vetted selection and suppresses stale-account presentation. */
private suspend fun executeSelectedKeyPackageDeletion(
    selection: KeyPackageDeletionRelaySelection,
    accountStillActive: () -> Boolean,
    delete: suspend (List<String>) -> Unit,
): KeyPackageDeletionResult =
    when (selection) {
        is KeyPackageDeletionRelaySelection.Ready -> {
            if (!accountStillActive()) {
                KeyPackageDeletionResult.Superseded
            } else {
                runCatchingCancellable {
                    delete(selection.relays)
                    if (accountStillActive()) {
                        KeyPackageDeletionResult.Deleted
                    } else {
                        // The remote acknowledgement cannot be rolled back, but a
                        // superseded account must not receive success UI/reloads.
                        KeyPackageDeletionResult.Superseded
                    }
                }.getOrElse(KeyPackageDeletionResult::Failed)
            }
        }
        KeyPackageDeletionRelaySelection.NoUsableRelay -> KeyPackageDeletionResult.NoUsableRelay
        KeyPackageDeletionRelaySelection.HostVerificationUnavailable ->
            KeyPackageDeletionResult.HostVerificationUnavailable
        KeyPackageDeletionRelaySelection.Superseded -> KeyPackageDeletionResult.Superseded
    }

/** Presents an acknowledged deletion or a current-account recovery result, returning whether to reload. */
internal fun WhiteNoiseAppState.presentKeyPackageDeletionResult(
    result: KeyPackageDeletionResult,
    hostVerificationDetail: AppText,
): Boolean =
    when (result) {
        KeyPackageDeletionResult.Deleted -> {
            presentTransient(R.string.toast_key_package_deleted)
            true
        }
        KeyPackageDeletionResult.NoUsableRelay -> {
            present(
                R.string.toast_couldnt_delete_key_package,
                R.string.error_no_safe_key_package_source_relay,
                copyable = false,
            )
            false
        }
        KeyPackageDeletionResult.HostVerificationUnavailable -> {
            present(R.string.toast_couldnt_delete_key_package, hostVerificationDetail, copyable = false)
            false
        }
        KeyPackageDeletionResult.Superseded -> false
        is KeyPackageDeletionResult.Failed -> {
            presentFailure(R.string.toast_couldnt_delete_key_package, "KEY_PACKAGE_DELETE", result.cause)
            false
        }
    }

/** Classifies bounded protocol sources off-main, then verifies only the approved hosts. */
private suspend fun selectKeyPackageDeletionRelays(
    sourceRelays: List<String>,
    classify: suspend (List<String>) -> List<RelayEndpointClassificationFfi>,
    resolve: RelayHostResolver,
    accountStillActive: () -> Boolean,
): KeyPackageDeletionRelaySelection =
    withContext(Dispatchers.IO) {
        val candidates = keyPackageDeletionRelayCandidates(sourceRelays)
        when {
            candidates.isEmpty() -> KeyPackageDeletionRelaySelection.NoUsableRelay
            else -> {
                val allowed = classifierAllowedDeletionRelays(classify(candidates))
                when {
                    !accountStillActive() -> KeyPackageDeletionRelaySelection.Superseded
                    allowed.isEmpty() -> KeyPackageDeletionRelaySelection.NoUsableRelay
                    else -> verifyDeletionRelayHosts(allowed, resolve, accountStillActive)
                }
            }
        }
    }

/** Trims and bounds protocol-derived input before it crosses the classifier boundary. */
private fun keyPackageDeletionRelayCandidates(sourceRelays: List<String>): List<String> =
    sourceRelays
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.length <= MAX_KEY_PACKAGE_DELETION_RELAY_CHARS }
        .distinct()
        .take(MAX_KEY_PACKAGE_DELETION_SOURCE_RELAYS)
        .toList()

/** Retains only MDK-approved canonical endpoints, preserving their first-seen order. */
private fun classifierAllowedDeletionRelays(classified: List<RelayEndpointClassificationFfi>): List<String> =
    classified
        .asSequence()
        .filter { it.policy == RelayEndpointPolicyFfi.ALLOWED }
        .mapNotNull(RelayEndpointClassificationFfi::normalizedEndpoint)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_KEY_PACKAGE_DELETION_SOURCE_RELAYS)
        .toList()

/** Caches one all-address DNS verdict per host and bounds the native relay fanout. */
private fun verifyDeletionRelayHosts(
    allowed: List<String>,
    resolve: RelayHostResolver,
    accountStillActive: () -> Boolean,
): KeyPackageDeletionRelaySelection {
    var verificationUnavailable = false
    val resolveResultByHost = mutableMapOf<String, RelayResolveTimeCheckResult>()
    val usable = mutableListOf<String>()
    for (relay in allowed) {
        if (usable.size == MAX_KEY_PACKAGE_DELETION_RELAYS) break
        if (!accountStillActive()) return KeyPackageDeletionRelaySelection.Superseded
        val host = relayHostForDeletionCheck(relay)
        val result =
            host?.let {
                resolveResultByHost.getOrPut(it) { relayHostResolveTimeCheckResult(it, resolve) }
            } ?: RelayResolveTimeCheckResult.Blocked
        when (result) {
            RelayResolveTimeCheckResult.Passed -> usable += relay
            RelayResolveTimeCheckResult.Blocked -> Unit
            RelayResolveTimeCheckResult.Unavailable -> verificationUnavailable = true
        }
    }
    return when {
        usable.isNotEmpty() -> KeyPackageDeletionRelaySelection.Ready(usable)
        verificationUnavailable -> KeyPackageDeletionRelaySelection.HostVerificationUnavailable
        else -> KeyPackageDeletionRelaySelection.NoUsableRelay
    }
}

/** Extracts the classifier-normalized host without imposing Android's editable-relay policy. */
private fun relayHostForDeletionCheck(relay: String): String? =
    runCatching {
        URI(relay)
            .host
            ?.removeSurrounding("[", "]")
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

/** Applies one cached DNS verdict per host before any classifier-approved endpoint is dialed. */
private fun relayHostResolveTimeCheckResult(
    host: String,
    resolve: RelayHostResolver,
): RelayResolveTimeCheckResult {
    val resolved = resolve(host)
    return when {
        resolved.isNullOrEmpty() -> RelayResolveTimeCheckResult.Unavailable
        resolved.any(HostSafety::isPrivateOrLoopbackAddress) -> RelayResolveTimeCheckResult.Blocked
        else -> RelayResolveTimeCheckResult.Passed
    }
}

private const val MAX_KEY_PACKAGE_DELETION_RELAYS = 16
private const val MAX_KEY_PACKAGE_DELETION_SOURCE_RELAYS = 256
private const val MAX_KEY_PACKAGE_DELETION_RELAY_CHARS = 2_048
