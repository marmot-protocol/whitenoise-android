package dev.ipf.whitenoise.android.media

import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Keeps the strict request, validator, byte and interruption proof together for the ordinary-work fixture. */
internal object PlatformResumeLedger {
    /** Wait for the current acquisition rather than accepting a prior, disconnected hold. */
    fun currentInitialBodyHeld(events: List<JSONObject>): Boolean {
        val requestId = events.lastOrNull { it.getString("kind") == "get" }?.getLong("seq")
        val rows = events.filter { it.optLong("request", -1) == requestId }
        return rows.any { it.getString("kind") == "held" && it.getLong("value") == 2L * 1024 * 1024 } &&
            rows.none { it.getString("kind") in setOf("disconnect", "complete") }
    }

    /** Select the latest pre-stop acquisition, retaining earlier aborted attempts as setup diagnostics. */
    private fun recoveryRequests(events: List<JSONObject>): Pair<Long, Long?> {
        val marker = events.single { it.getString("kind") == "platform_stop_marker" }.getLong("seq")
        val acquisitions = events.filter { it.getString("kind") == "get" }.map { it.getLong("seq") }
        val initial = acquisitions.last { it < marker }
        val resumed = acquisitions.filter { it > marker }
        check(resumed.size <= 1) { "unexpected extra acquisition after platform stop" }
        val initialRows = events.filter { it.optLong("request", -1) == initial }
        val held = initialRows.single { it.getString("kind") == "held" }
        check(
            held.getLong("value") == 2L * 1024 * 1024 &&
                initial < held.getLong("seq") &&
                held.getLong("seq") < marker,
        )
        check(
            initialRows.none {
                it.getString("kind") in setOf("disconnect", "complete") && it.getLong("seq") < marker
            },
        ) { "initial hold was stale before platform stop" }
        acquisitions.filter { it < initial }.forEach { setup ->
            val ends =
                events.filter {
                    it.optLong("request", -1) == setup && it.getString("kind") in setOf("disconnect", "complete")
                }
            check(
                ends.size == 1 &&
                    ends.single().getString("kind") == "disconnect" &&
                    ends.single().getLong("seq") < initial,
            )
        }
        return initial to resumed.singleOrNull()
    }

    /** A historical hold or a second post-stop acquisition cannot admit release. */
    fun currentResumedBodyHeld(events: List<JSONObject>): Boolean {
        val resumedRequest = recoveryRequests(events).second ?: return false
        val rows = events.filter { it.optLong("request", -1) == resumedRequest }
        check(rows.none { it.getString("kind") in setOf("disconnect", "complete") }) {
            "resumed acquisition ended before fixture release"
        }
        val held = rows.singleOrNull { it.getString("kind") == "held" }
        if (held != null) {
            check(held.getLong("value") == 3L * 1024 * 1024)
            assertRecoveryRequest(rows, 2L * 1024 * 1024, 206)
            check(rows.single { it.getString("kind") == "range_requested_offset" }.getLong("value") == 2L * 1024 * 1024)
            check(rows.single { it.getString("kind") == "if_range_match" }.getLong("value") == 1L)
            check(rows.filter { it.getString("kind") == "body_bytes" }.sumOf { it.getLong("value") } == 1024L * 1024)
        }
        return held != null
    }

    /** The response checkpoint belongs to this request, never a prior attempt. */
    private fun assertRecoveryRequest(
        rows: List<JSONObject>,
        offset: Long,
        status: Int,
    ) {
        check(rows.all { it.getLong("seq") > it.getLong("request") })
        assertEquals(offset, rows.single { it.getString("kind") == "range_offset" }.getLong("value"))
        assertEquals(status, rows.single { it.getString("kind") == "status" }.getInt("value"))
    }

    /** Independently committed Range and byte totals must prove compatible checkpoint reuse. */
    fun assertResumeLedger(
        events: List<JSONObject>,
        size: Int,
    ) {
        val (initial, resumedOrNull) = recoveryRequests(events)
        val resumed = checkNotNull(resumedOrNull)
        val prefix = 2L * 1024 * 1024

        fun rows(request: Long) = events.filter { it.optLong("request", -1) == request }
        val first = rows(initial)
        val second = rows(resumed)
        assertRecoveryRequest(first, 0, 200)
        assertRecoveryRequest(second, prefix, 206)
        assertEquals(prefix, events.single { it.getString("kind") == "range_requested_offset" }.getLong("value"))
        assertEquals(resumed, events.single { it.getString("kind") == "range_requested_offset" }.getLong("request"))
        assertEquals(1, events.single { it.getString("kind") == "if_range_match" }.getInt("value"))
        assertEquals(resumed, events.single { it.getString("kind") == "if_range_match" }.getLong("request"))
        assertEquals(prefix, first.filter { it.getString("kind") == "body_bytes" }.sumOf { it.getLong("value") })
        assertEquals(
            size + 16L - prefix,
            second.filter { it.getString("kind") == "body_bytes" }.sumOf { it.getLong("value") },
        )

        fun sequence(
            rows: List<JSONObject>,
            kind: String,
        ) = rows.single { it.getString("kind") == kind }.getLong("seq")
        val marker = sequence(events, "platform_stop_marker")
        val interrupt = sequence(events, "interrupt_acquisition")
        val release = sequence(events, "release_acquisition")
        val held = sequence(second, "held")
        assertEquals(3L * 1024 * 1024, second.single { it.getString("kind") == "held" }.getLong("value"))
        assertEquals(
            1024L * 1024,
            second
                .filter {
                    it.getString("kind") == "body_bytes" && it.getLong("seq") < held
                }.sumOf { it.getLong("value") },
        )
        check(
            marker < interrupt &&
                interrupt < sequence(first, "disconnect") &&
                sequence(first, "disconnect") < resumed,
        )
        check(resumed < held && held < release && release < sequence(second, "complete"))
        check(first.all { it.getLong("seq") <= sequence(first, "disconnect") })
        check(second.all { it.getLong("seq") <= sequence(second, "complete") })
        check(sequence(second, "range_requested_offset") < held && sequence(second, "if_range_match") < held)
        assertEquals(
            prefix,
            first
                .filter {
                    it.getString("kind") == "body_bytes" && it.getLong("seq") < sequence(first, "held")
                }.sumOf { it.getLong("value") },
        )
        check(first.none { it.getString("kind") == "complete" } && second.none { it.getString("kind") == "disconnect" })
        reportRecoverySetup(events, initial)
    }

    private fun reportRecoverySetup(
        events: List<JSONObject>,
        initial: Long,
    ) {
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "automatic-resume-setup-diagnostics")
                .put(
                    "setup_acquisition_count",
                    events.count {
                        it.getString("kind") == "get" && it.getLong("seq") < initial
                    },
                ).put(
                    "setup_body_bytes",
                    events
                        .filter {
                            it.getString("kind") == "body_bytes" && it.getLong("request") < initial
                        }.sumOf { it.getLong("value") },
                ).put("ledger_acquisition_count", events.count { it.getString("kind") == "get" })
                .put(
                    "ledger_body_bytes",
                    events
                        .filter {
                            it.getString("kind") == "body_bytes"
                        }.sumOf { it.getLong("value") },
                ).put("performance_qualified", false),
        )
    }
}
