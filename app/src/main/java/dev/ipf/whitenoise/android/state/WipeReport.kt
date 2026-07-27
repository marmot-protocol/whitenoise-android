package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.core.DiagnosticFormatter

/**
 * The stages of the destructive Sign Out & Wipe, in the order the engine
 * executes them inside [dev.ipf.marmotkit.Marmot.signOutAndWipe] (#350). The
 * FFI is a single suspend call that reports all stages only in its final
 * [WipeOutcomeFfi], so the UI shows these as an indeterminate staged display
 * while in flight and marks them from the outcome afterwards.
 */
enum class WipeStage {
    LeavingGroups,
    DeletingKeyPackages,
    WipingLocalData,
}

/**
 * One best-effort failure inside a wipe stage. [subject] is a shortened
 * identifier of the affected group / relay event (null for the local-cleanup
 * stage, which has no per-item subject); [reason] is the engine's diagnostic.
 */
data class WipeFailureItem(
    val subject: String?,
    val reason: String,
)

/** Per-stage result mapped from the engine outcome for the wipe outcome sheet. */
data class WipeStageReport(
    val stage: WipeStage,
    /**
     * How many items the stage completed (groups left, key packages deleted).
     * Null for [WipeStage.WipingLocalData], which is all-or-nothing.
     */
    val completedCount: Int?,
    val failures: List<WipeFailureItem>,
) {
    val hasIssues: Boolean get() = failures.isNotEmpty()
}

/**
 * UI model of a finished Sign Out & Wipe. A [clean] wipe just toasts; a
 * report with issues drives the "Wipe finished with N issues" sheet. The
 * account ref is invalid by the time this exists, so the sheet renders only
 * this snapshot and never reaches back into the FFI.
 */
data class WipeReport(
    val stages: List<WipeStageReport>,
) {
    val issueCount: Int get() = stages.sumOf { it.failures.size }
    val clean: Boolean get() = issueCount == 0
}

/**
 * Map the engine's [WipeOutcomeFfi] to the [WipeReport] UI model. Stages map
 * 1:1 to the outcome struct fields, in engine execution order.
 */
internal fun wipeReport(outcome: WipeOutcomeFfi): WipeReport =
    WipeReport(
        stages =
            listOf(
                WipeStageReport(
                    stage = WipeStage.LeavingGroups,
                    completedCount = outcome.groupsLeft.toInt(),
                    failures =
                        outcome.groupLeaveFailures.map {
                            WipeFailureItem(shortWipeSubject(it.groupIdHex), DiagnosticFormatter.redactError(it.reason))
                        },
                ),
                WipeStageReport(
                    stage = WipeStage.DeletingKeyPackages,
                    completedCount = outcome.keyPackagesDeleted.toInt(),
                    failures =
                        outcome.keyPackageFailures.map {
                            WipeFailureItem(shortWipeSubject(it.eventIdHex), DiagnosticFormatter.redactError(it.reason))
                        },
                ),
                WipeStageReport(
                    stage = WipeStage.WipingLocalData,
                    completedCount = null,
                    failures =
                        if (outcome.localCleanup.completed) {
                            emptyList()
                        } else {
                            listOf(
                                WipeFailureItem(
                                    subject = null,
                                    reason = DiagnosticFormatter.redactError(outcome.localCleanup.reason.orEmpty()),
                                ),
                            )
                        },
                ),
            ),
    )

/**
 * Shorten a hex identifier (group id, relay event id) for display in the
 * outcome sheet's failure rows. Full ids are 64 chars of noise; the first 12
 * are plenty to correlate against logs.
 */
internal fun shortWipeSubject(hex: String): String = if (hex.length <= 12) hex else "${hex.take(12)}…"

/**
 * How a non-destructive sign-out ended (#349). Local sign-out always
 * completes; this only distinguishes whether the engine's relay-side
 * KeyPackage cleanup finished.
 */
enum class SignOutCompletion {
    /** Engine sign-out succeeded with no relay cleanup failures. */
    Complete,

    /**
     * The engine call failed outright, or reported per-relay KeyPackage
     * cleanup failures. The session is still signed out locally, but MDK does
     * not retain a remote-deletion retry queue after this call.
     */
    RelayCleanupIncomplete,
}

/**
 * Map the engine sign-out outcome (null when the FFI call itself failed) to
 * the completion the UI toasts about.
 */
internal fun signOutCompletion(engineOutcome: SignOutOutcomeFfi?): SignOutCompletion =
    if (engineOutcome == null || engineOutcome.keyPackageFailures.isNotEmpty()) {
        SignOutCompletion.RelayCleanupIncomplete
    } else {
        SignOutCompletion.Complete
    }
