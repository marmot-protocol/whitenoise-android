package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Growth ceiling for the two largest state sources. The ceilings exist because
 * [Controllers.kt] (12,759 lines) and [AppState.kt] (10,185 lines) are already
 * hard to navigate; new work should land in smaller units unless a Kover report
 * justifies a deliberate, reviewed raise of these limits. Lowering a ceiling
 * after a refactor is routine; raising one requires an intentional edit here
 * backed by coverage measurements from
 * `./gradlew :app:koverXmlReportDevZapstoreDebug`.
 */
class StateSourceSizeCeilingTest {
    /** Keeps both large state sources at or below their reviewed growth ratchets. */
    @Test
    fun controllersAndAppStateStayWithinGrowthCeiling() {
        assertTrue(
            "Controllers.kt exceeds ${CONTROLLERS_MAX_LINES} lines",
            sourceLineCount("Controllers.kt") <= CONTROLLERS_MAX_LINES,
        )
        assertTrue(
            "AppState.kt exceeds ${APP_STATE_MAX_LINES} lines",
            sourceLineCount("AppState.kt") <= APP_STATE_MAX_LINES,
        )
    }

    /** Verifies the helper matches `wc -l` semantics for a trailing newline. */
    @Test
    fun lineCountHelperCountsPhysicalLinesLikeWc() {
        val temp = File.createTempFile("state-source-ceiling", ".kt")
        temp.writeText("first\nsecond\nthird\n")
        assertEquals(3, sourceLineCount(temp))
    }

    /** Resolves a state source by name and returns its physical line count. */
    private fun sourceLineCount(name: String): Int = sourceLineCount(sourceFile(name))

    internal companion object {
        // Lowered as `ChatListItem` and then the chat-list sort moved to their own
        // same-package files. Raised to the exact post-#2431 size for its covered
        // chat-list convergence and notification-open fixes; keep this ratchet exact.
        // PR #2792 adds covered token-bound send admission and cancellation arbitration;
        // the current base also adds group-recovery status handling. The pending-video
        // poster fix (#2732) adds 13 covered lines for attachment-aware thumbnail
        // reconciliation on top of that. PR #2798 adds 32 net lines for off-main
        // window application and commit-time index revalidation. Its prior green
        // head covered 4,787 / 6,614 controller lines in Kover. Exact-head CI
        // rechecks coverage. Issue #2784 adds a narrow controller check before
        // dropping a still-active top-window row; its ordering guard and tests
        // live in smaller files. Revision-bound publication across views brings
        // the controller to 13,240 lines; focused race regressions and exact-head
        // CI recheck the corresponding coverage and style contracts. Android host
        // telemetry adds only the controller integration boundaries; attempt ownership,
        // replay and settlement remain in the focused HostPerformanceTelemetry unit.
        const val CONTROLLERS_MAX_LINES = 13309

        // Master includes the covered draft lifecycle and host-timing changes. PR #2534
        // adds 38 lines for the async prepared-speech handoff while keeping preparation
        // in the bounded TTS sources; retain both changes in the combined growth ceiling.
        // PR #2565 adds 20 lines for the reply-aware dictation send path.
        // PR #2566 adds 4 lines for lifecycle-local provider discovery.
        // Navigation-resilient dictation adds 30 lines for atomic reply ownership,
        // claim release, and detached dispatch.
        // PR #2792 adds the covered shared send-phase registry and commit-lock helpers;
        // current base contributes two lines of fresh-group recovery state. Replacement-controller
        // retry regression adds a shared per-conversation wakeup registry (+12 lines).
        // The interactive account-switch avatar seed (#2155) adds 8 covered lines: the
        // bounded top-bar seed set now feeds both switch paths from one computation, and
        // the interactive branch loads and fences it before publication.
        // Durable push-wake recovery adds the covered lifecycle owner, generation fences,
        // finite attempt settlement, and notification-delivery cutover orchestration.
        // Its covered final settlement needs one formatter-required expression-body continuation.
        // Current master also adds pending-send and bounded-window recovery state;
        // Play/Zapstore unit tests and Kover run in required CI; keep merged size exact.
        // Android host telemetry adds the runtime, queue, profile and durable-settings
        // integration boundaries while its concurrency and replay logic stays in the
        // smaller HostPerformanceTelemetry unit; exact-head Kover rechecks the wiring.
        const val APP_STATE_MAX_LINES = 11462

        /** Counts physical source lines with the same trailing-newline semantics as `wc -l`. */
        internal fun sourceLineCount(file: File): Int = file.bufferedReader().useLines { lines -> lines.count() }

        /** Finds a state source from either the module or repository working directory. */
        private fun sourceFile(name: String): File =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
            ).firstOrNull(File::exists)
                ?: error("Missing $name source file")
    }
}
