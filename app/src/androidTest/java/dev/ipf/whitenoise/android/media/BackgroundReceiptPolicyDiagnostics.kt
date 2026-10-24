package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.state.AutomaticAttachmentPolicy
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Closed identities for the fixture cases that change the receiver's automatic-download policy. */
internal enum class BackgroundReceiptPolicyCase {
    Initial,
    DisabledType,
    NetworkHeld,
    ProcessStop,
}

/** The two independently bounded waits in the fixture's policy setup. */
internal enum class BackgroundReceiptPolicyStage {
    HostPolicy,
    NativeApproval,
}

/**
 * Reports a policy timeout before fixture cleanup, then rethrows the same exception. Snapshot/report failures
 * cannot replace the timeout. The caller retains ownership of the deadline and all successful-path assertions.
 */
internal suspend fun <T> withBackgroundReceiptPolicyDiagnostics(
    case: BackgroundReceiptPolicyCase,
    stage: BackgroundReceiptPolicyStage,
    snapshot: () -> JSONObject,
    report: (JSONObject) -> Unit = ControlledAttachmentProbe::report,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        runCatching {
            report(
                snapshot()
                    .put("phase", "background-receipt-policy-timeout")
                    .put("case", case.name)
                    .put("stage", stage.name),
            )
        }
        throw timeout
    }

/**
 * Serializes only enum names and booleans from one host-policy observation; it performs no native or WorkManager
 * reads. Applied approval means a revision is present, not proof that it permits any particular attachment type.
 */
internal fun backgroundReceiptPolicySnapshot(
    policy: AutomaticAttachmentPolicy,
    expected: Set<MediaAutoDownloadType>,
    wifiOnly: Set<MediaAutoDownloadType>,
    approvalApplied: Boolean,
): JSONObject =
    JSONObject()
        .put("allowed_types", JSONArray(policy.allowedNow().sortedBy { it.ordinal }.map { it.name }))
        .put("expected_types", JSONArray(expected.sortedBy { it.ordinal }.map { it.name }))
        .put("wifi_only_types", JSONArray(wifiOnly.sortedBy { it.ordinal }.map { it.name }))
        .put("networks", JSONArray(policy.networks.sortedBy { it.ordinal }.map { it.name }))
        .put("account_state", policy.account.name)
        .put("paused", policy.paused)
        .put("validated_internet", policy.validatedInternet)
        .put("approval_applied", approvalApplied)
