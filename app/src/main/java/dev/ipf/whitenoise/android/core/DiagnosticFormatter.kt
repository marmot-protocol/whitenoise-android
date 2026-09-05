package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import java.util.Locale

data class DiagnosticIdentityPresentation(
    val accountLabel: (accountLabel: String, accountIdHex: String) -> String,
    val publicIdentity: (pubkeyHex: String) -> String,
) {
    companion object {
        fun accountLabel(
            accountLabel: String,
            accountIdHex: String,
            presentPublicIdentity: (String) -> String,
        ): String {
            val label = accountLabel.takeIf { it.isNotBlank() }
            if (label != null && !IdentityFormatter.isNostrIdentityFallback(label)) {
                return label
            }
            return presentPublicIdentity(accountIdHex)
        }
    }
}

/** Internal failures may provide a stable, privacy-safe support category and detail. */
internal interface DiagnosticErrorMetadata {
    val diagnosticErrorCode: String
    val diagnosticTechnicalDetail: String?
}

object DiagnosticFormatter {
    data class ErrorReportContext(
        val appVersion: String,
        val androidVersion: String,
        val occurredAtUtc: String,
    )

    fun describe(
        event: MarmotEventFfi,
        identity: DiagnosticIdentityPresentation,
    ): String =
        when (event) {
            is MarmotEventFfi.GroupJoined ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] joined group ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.GroupStateUpdated ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] group state ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.MessageReceived -> {
                // Diagnostic entries render live on the Diagnostics screen and
                // are captured by screen recorders, screenshots, and logcat.
                // Never embed decrypted message text here — the kind + length
                // is enough to debug delivery without breaking the e2e
                // confidentiality contract.
                val msg = event.received.message
                val account =
                    identity.accountLabel(
                        event.received.accountLabel,
                        event.received.accountIdHex,
                    )
                "[$account] msg from ${identity.publicIdentity(msg.sender)} " +
                    "kind=${msg.kind} len=${msg.plaintext.length}"
            }
            is MarmotEventFfi.ProjectionUpdated ->
                "[${identity.accountLabel(event.update.accountLabel, event.update.accountIdHex)}] projection ${IdentityFormatter.short(
                    event.update.update.groupIdHex,
                )} (${event.update.update.messages.size} messages)"
            is MarmotEventFfi.GroupEvent ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] group event"
            is MarmotEventFfi.AccountError ->
                // The FFI error string is not guaranteed content-free; scrub
                // common secret shapes before truncating so a path that ever
                // interpolates a relay URL, token, key, or decrypted content
                // can't leak it in full through this screen-capturable surface.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] error: ${redactError(event.message)}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] agent stream activity"
            is MarmotEventFfi.EpochStallEscalated ->
                // hex group id only — epoch and arm count carry no content.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] epoch stall escalated in ${IdentityFormatter.short(
                    event.groupIdHex,
                )} epoch=${event.stalledEpoch} arms=${event.arms}"
            is MarmotEventFfi.WelcomeDeliveryPending ->
                // Recipient is a public identity; group id stays shortened hex.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] welcome pending for ${identity.publicIdentity(
                    event.recipientHex,
                )} in group ${IdentityFormatter.short(event.groupIdHex)}"
        }

    private const val MAX_ERROR_LEN = 80
    private const val REDACTED = "[redacted]"
    private val NSEC_SECRET = Regex("(?i)nsec1[0-9a-z]+\\b")
    private val HEX_SECRET = Regex("\\b[0-9a-fA-F]{64,}\\b")
    private val SEPARATED_HEX_SECRET = Regex("(?i)\\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){15,}\\b")
    private val UUID_IDENTIFIER =
        Regex(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b",
        )

    // No trailing \b after the padding: `=` is a non-word char, so a \b there
    // only matched when another word char followed, letting the engine backtrack
    // to zero `=` and leak the trailing `=`/`==` when the padding ended the token
    // or was followed by whitespace/punctuation.
    private val BASE64_SECRET = Regex("\\b[A-Za-z0-9+/]{40,}={0,2}")
    private val CREDENTIALS_IN_URL = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@")
    private val TOKEN_ASSIGNMENT = Regex("(?i)\\b(authorization|bearer|token|auth[_-]?token|password|secret)=([^\\s&]+)")
    private val BEARER_HEADER = Regex("(?i)\\bauthorization:\\s*bearer\\s+\\S+")

    /**
     * Scrub secret-shaped substrings (nsec, long hex, URL credentials, token
     * assignments) and clamp length before an FFI error string reaches any
     * user-facing surface. Public so the highest-sensitivity callers (secret-key
     * export / encrypted backup toasts, which are not behind FLAG_SECURE) can
     * reuse the same scrubber instead of presenting raw FFI text (#846).
     */
    fun redactError(message: String): String {
        val scrubbed =
            message
                .replace(CREDENTIALS_IN_URL) { "${it.groupValues[1]}$REDACTED@" }
                .replace(NSEC_SECRET, REDACTED)
                .replace(HEX_SECRET, REDACTED)
                .replace(SEPARATED_HEX_SECRET, REDACTED)
                .replace(UUID_IDENTIFIER, REDACTED)
                .replace(BASE64_SECRET, REDACTED)
                .replace(TOKEN_ASSIGNMENT) { "${it.groupValues[1]}=$REDACTED" }
                .replace(BEARER_HEADER, "Authorization: Bearer $REDACTED")
        if (scrubbed.length <= MAX_ERROR_LEN) return scrubbed
        // Don't truncate mid surrogate pair — that would leave a lone surrogate.
        val end =
            if (Character.isHighSurrogate(scrubbed[MAX_ERROR_LEN - 1])) {
                MAX_ERROR_LEN - 1
            } else {
                MAX_ERROR_LEN
            }
        return scrubbed.take(end) + "…"
    }

    /**
     * A bounded, privacy-safe support payload kept separate from user-facing
     * copy. Operation and category are stable identifiers; exception messages
     * and class names never become UI text or support payloads. Callers may add
     * an explicitly selected, non-user-authored [technicalDetail], which is
     * still scrubbed and bounded before it is included.
     */
    fun errorReport(
        operationCode: String,
        throwable: Throwable,
        context: ErrorReportContext,
        technicalDetail: String? = null,
    ): String =
        buildString {
            val metadata =
                causeChain(throwable)
                    .filterIsInstance<DiagnosticErrorMetadata>()
                    .firstOrNull()
            appendLine("White Noise error report")
            appendLine("operation=${stableCode(operationCode)}")
            appendLine("error=${errorCode(throwable)}")
            (technicalDetail ?: metadata?.diagnosticTechnicalDetail ?: contentionDetail(throwable))
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { appendLine("detail=${redactError(it)}") }
            appendLine("app=${redactError(context.appVersion)}")
            appendLine("android=${redactError(context.androidVersion)}")
            append("utc=${redactError(context.occurredAtUtc)}")
        }.take(MAX_REPORT_LEN)

    /** Keeps the native busy boundary diagnosable after log rotation without copying exception text. */
    private fun contentionDetail(throwable: Throwable): String? {
        val code =
            when (causeChain(throwable).filterIsInstance<MarmotKitException>().firstOrNull()) {
                is MarmotKitException.AccountWorkerBusy -> "ACCOUNT_WORKER_BUSY"
                is MarmotKitException.RuntimeBusy -> "RUNTIME_BUSY"
                is MarmotKitException.AccountSessionBusy -> "ACCOUNT_SESSION_BUSY"
                is MarmotKitException.StorageBusy -> "STORAGE_BUSY"
                is MarmotKitException.GroupSendQueueFull -> "GROUP_SEND_QUEUE_FULL"
                else -> return null
            }
        return "contention=$code"
    }

    @Suppress("CyclomaticComplexMethod") // One ordered taxonomy prevents error-code precedence from drifting.
    internal fun errorCode(throwable: Throwable): String {
        val chain = causeChain(throwable)
        val names = chain.map { it.javaClass.simpleName.lowercase(Locale.ROOT) }
        val marmotError = chain.filterIsInstance<MarmotKitException>().firstOrNull()
        val diagnosticError = chain.filterIsInstance<DiagnosticErrorMetadata>().firstOrNull()
        return when {
            chain.any { it is java.util.concurrent.CancellationException } -> "CANCELLED"
            diagnosticError != null -> stableCode(diagnosticError.diagnosticErrorCode)
            marmotError is MarmotKitException.ExternalSignerRejected -> "CANCELLED"
            marmotError is MarmotKitException.InvalidChatPin ||
                marmotError is MarmotKitException.InvalidMessageDraft ||
                marmotError is MarmotKitException.InvalidMediaReference ||
                marmotError is MarmotKitException.InvalidHex ||
                marmotError is MarmotKitException.InvalidIdentity ||
                marmotError is MarmotKitException.InvalidKeyPackageEvent ||
                marmotError is MarmotKitException.EmptyPassphrase -> "INVALID_INPUT"
            marmotError is MarmotKitException.UnknownAccount ||
                marmotError is MarmotKitException.UnknownGroup ||
                marmotError is MarmotKitException.MissingKeyPackage ||
                marmotError is MarmotKitException.MemberNotInGroup ||
                marmotError is MarmotKitException.SecretNotFound -> "NOT_FOUND"
            marmotError is MarmotKitException.AccountWorkerBusy ||
                marmotError is MarmotKitException.RuntimeBusy ||
                marmotError is MarmotKitException.AccountSessionBusy ||
                marmotError is MarmotKitException.StorageBusy ||
                marmotError is MarmotKitException.GroupSendQueueFull -> "RESOURCE_BUSY"
            marmotError is MarmotKitException.NotGroupAdmin ||
                marmotError is MarmotKitException.AdminCannotSelfRemove ||
                marmotError is MarmotKitException.WouldRemoveLastAdmin -> "PERMISSION_DENIED"
            marmotError is MarmotKitException.Publish ||
                marmotError is MarmotKitException.TransportClosed ||
                marmotError is MarmotKitException.AccountCatchUp ||
                marmotError is MarmotKitException.FollowListUnavailable -> "CONNECTIVITY"
            marmotError is MarmotKitException.KeystoreUnavailable ||
                marmotError is MarmotKitException.ExternalSignerUnavailable -> "PLATFORM_UNAVAILABLE"
            marmotError is MarmotKitException.EncryptionFailed ||
                marmotError is MarmotKitException.ExternalSignerMismatch -> "CRYPTO_FAILURE"
            marmotError is MarmotKitException.Io -> "IO"
            chain.any { it is SecurityException } ||
                names.any { "permission" in it || "security" in it } -> "PERMISSION_DENIED"
            names.any { "timeout" in it } -> "TIMEOUT"
            chain.any { it is java.io.IOException } ||
                names.any { "network" in it || "connect" in it || "relay" in it } -> "CONNECTIVITY"
            chain.any { it is IllegalArgumentException } ||
                names.any { "invalid" in it || "parse" in it } -> "INVALID_INPUT"
            names.any { "notfound" in it || "missing" in it } -> "NOT_FOUND"
            names.any { "busy" in it || "conflict" in it || "locked" in it } -> "RESOURCE_BUSY"
            else -> "UNEXPECTED"
        }
    }

    private fun causeChain(throwable: Throwable): List<Throwable> =
        generateSequence(throwable) { current -> current.cause?.takeUnless { it === current } }
            .take(MAX_CAUSE_DEPTH)
            .toList()

    private fun stableCode(value: String): String =
        value
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9_]+"), "_")
            .trim('_')
            .take(MAX_CODE_LEN)
            .ifBlank { "UNKNOWN_OPERATION" }

    private const val MAX_REPORT_LEN = 600
    private const val MAX_CODE_LEN = 64
    private const val MAX_CAUSE_DEPTH = 8
}
