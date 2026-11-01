package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Keeps every account-switch trace stage measurable in a release WNPerf session (#3496). */
class AccountSwitchPerformanceCoverageTest {
    /** Each stage label the trace emits maps to one closed WNPerf phase. */
    @Test
    fun everyEmittedStageHasAPhase() {
        val sources =
            listOf("AppState.kt", "Controllers.kt", "AccountSwitchLocalSnapshot.kt")
                .joinToString("\n") { File(STATE_SOURCE_DIR, it).readText() }
        val emitted =
            STAGE_CALL.findAll(sources).map { it.groupValues[1] }.toSet() +
                MEMBER_STAGE_RESULT.findAll(sources).map { it.groupValues[1] }.toSet() +
                "first-local-frame"
        assertEquals(EXPECTED_STAGES, emitted)
        emitted.forEach { stage ->
            requireNotNull(accountSwitchPerformancePhase(stage)) { "$stage has no WNPerf phase" }
        }
    }

    /** Row and frame stages reuse the startup phases, so both journeys read on one scale. */
    @Test
    fun sharedStagesReuseStartupPhases() {
        assertEquals(PerformancePhase.CACHED_CHAT_ROWS_READY, accountSwitchPerformancePhase("cached-chat-rows-ready"))
        assertEquals(PerformancePhase.FIRST_LOCAL_FRAME, accountSwitchPerformancePhase("first-local-frame"))
        assertEquals(
            PerformancePhase.MEMBER_DERIVED_LOCAL_READY,
            accountSwitchPerformancePhase("member-derived-local-ready"),
        )
    }

    /** A label outside the closed schema is dropped rather than emitted. */
    @Test
    fun unknownStageIsDropped() {
        assertNull(accountSwitchPerformancePhase("identity-state"))
        assertNull(accountSwitchPerformancePhase("account 1234"))
    }

    private companion object {
        const val STATE_SOURCE_DIR = "src/main/java/dev/ipf/whitenoise/android/state"
        val STAGE_CALL =
            Regex(
                "record(?:AccountSwitchMark|AccountSwitchPreloadStage|PendingAccountSwitchStage)" +
                    """\(\s*(?:accountRef,\s*)?"([a-z-]+)"""",
            )
        val MEMBER_STAGE_RESULT = Regex(""""(member-derived-local-(?:ready|deferred))"""")
        val EXPECTED_STAGES =
            setOf(
                "cached-chat-rows-ready",
                "member-derived-local-ready",
                "member-derived-local-deferred",
                "top-bar-profiles-ready",
                "persisted-profiles-ready",
                "previous-shortcuts-hide-finished",
                "published",
                "bind-start",
                "seed-bound",
                "first-local-frame",
            )
    }
}
