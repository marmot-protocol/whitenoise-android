package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupCoordinator
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Default time allowed for cold-start work before the shell becomes actionable. */
internal const val BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS = 15_000L

/** Emits operational detail only from debug builds so release logs remain privacy-bounded. */
internal inline fun appStateDebug(message: () -> String) {
    // Debug-only: these INFO lines are operational/diagnostic and some carry
    // sender/group context, so they must not ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.i("DMAppState", message())
}

/** Emits a debug throwable while retaining only a release-safe failure marker in release builds. */
internal inline fun appStateDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMAppState", message(), error)
    } else {
        Log.e("DMAppState", releaseFailureMarker("APP_STATE", error, message()))
    }
}

/** Deployment environments whose release logs may carry a redacted note beside the failure marker. */
private val VERBOSE_RELEASE_LOG_ENVIRONMENTS = setOf("dev", "preview", "staging")

/** Longest redacted note a release log line carries. */
private const val RELEASE_LOG_NOTE_LIMIT = 200

/**
 * The failure line a release build logs: the operation, the stable error category and the MarmotKit
 * error variant, never exception text. Non-production environments (dev, preview, staging) also carry the
 * caller's note after [redactForReleaseLog], so their logcat can explain a failure without identifiers.
 * The shapes this must never emit are enforced by `scripts/verify-release-runtime.sh`.
 */
internal fun releaseFailureMarker(
    operation: String,
    error: Throwable,
    note: String? = null,
): String =
    buildString {
        append("operation_failed op=").append(operation)
        append(" code=").append(DiagnosticFormatter.errorCode(error))
        DiagnosticFormatter.marmotVariant(error)?.let { append(" mdk=").append(it) }
        if (note != null && BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT in VERBOSE_RELEASE_LOG_ENVIRONMENTS) {
            append(" note=").append(redactForReleaseLog(note))
        }
    }

private val HEX_IDENTIFIER = Regex("[0-9a-fA-F]{64,}")
private val BECH32_IDENTIFIER = Regex("\\b(npub1|nsec1|nprofile1|note1|nevent1)[0-9a-z]+")
private val URL_OR_PATH = Regex("https?://\\S+|/data/\\S*")
private val FIELD_TOKENS = Regex("\\b(details?|group|message|filename|path|report|error|reason)=")

/**
 * Strips every identifier and error-text shape release logs must never carry: 64-hex ids, bech32 keys,
 * URLs and data paths, the `field=` tokens the runtime verifier rejects, and exception wording. What
 * remains is operational prose such as which step failed.
 */
internal fun redactForReleaseLog(text: String): String =
    text
        .replace(HEX_IDENTIFIER, "<hex>")
        .replace(BECH32_IDENTIFIER, "<bech32>")
        .replace(URL_OR_PATH, "<url>")
        .replace(FIELD_TOKENS) { "${it.groupValues[1]}:" }
        .replace("Caused by:", "cause:")
        .replace("Exception", "Exc")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(RELEASE_LOG_NOTE_LIMIT)

/** Waits for one bootstrap attempt without propagating an actionable-shell timeout. */
internal suspend fun awaitBootstrapAttempt(
    attempt: Deferred<Unit>,
    timeoutMillis: Long,
): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        attempt.await()
        true
    } ?: false

/** Returns a trimmed non-empty value or null for absent and blank configuration. */
internal fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Blocks notification mutations whenever the app-lock surface is protecting local state. */
internal fun notificationActionsAllowed(appLockScreenVisible: Boolean): Boolean = !appLockScreenVisible

/** Builds the account-scoped key used by cached group-member snapshots. */
internal fun groupMemberSnapshotKey(
    accountRef: String?,
    groupIdHex: String,
): String? {
    val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
    return "$account:$groupIdHex"
}

/** Recovers one acknowledged checkpoint and refreshes the published account state after success. */
internal suspend fun AccountSetupCoordinator.recoverAndRefresh(
    account: String,
    refreshAccounts: suspend () -> Unit,
): Boolean =
    runCatchingCancellable {
        if (!recover(account)) return@runCatchingCancellable false
        refreshAccounts()
        true
    }.onFailure { failure ->
        appStateDebug(failure) { "onboarding checkpoint recovery failed" }
    }.getOrDefault(false)
