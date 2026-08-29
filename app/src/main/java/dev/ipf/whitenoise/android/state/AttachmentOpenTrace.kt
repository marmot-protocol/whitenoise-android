package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig

internal enum class AttachmentOpenPhase(
    val wireName: String,
) {
    Tap("tap"),
    RequestPersisted("request_persisted"),
    InteractiveQueueAdmitted("interactive_queue_admitted"),
    MaterializationStarted("materialization_started"),
    WaitingForDurableAvailability("waiting_for_durable_availability"),
    DurableAvailabilityObserved("durable_availability_observed"),
    CacheArtifactReady("cache_artifact_ready"),
    LifecycleEligibility("lifecycle_eligibility"),
    VisibilityEligibility("visibility_eligibility"),
    PlatformDispatchStarted("platform_dispatch_started"),
    PlatformDispatchResult("platform_dispatch_result"),
    NavigationCancelled("navigation_cancelled"),
    TerminalResult("terminal_result"),
}

/**
 * Bounded, process-local phase timing for attachment opens. Emitted lines carry
 * only an ephemeral sequence, phase, duration, and finite outcome — never
 * account/group/message identifiers or filenames.
 */
internal class AttachmentOpenTraceRegistry(
    private val nowMs: () -> Long,
    private val emit: (String) -> Unit,
) {
    private data class Entry(
        val sequence: Long,
        val startedAtMs: Long,
    )

    private val entries = linkedMapOf<AttachmentOpenRequest, Entry>()
    private var nextSequence = 0L

    fun begin(request: AttachmentOpenRequest) {
        synchronized(entries) {
            val now = nowMs()
            val entry = Entry(sequence = ++nextSequence, startedAtMs = now)
            entries[request] = entry
            trimToBound()
            emitLine(entry, AttachmentOpenPhase.Tap, now, outcome = null, recovered = false)
        }
    }

    fun phase(
        request: AttachmentOpenRequest,
        phase: AttachmentOpenPhase,
        outcome: String? = null,
    ) {
        synchronized(entries) {
            val now = nowMs()
            val existing = entries[request]
            val entry = existing ?: Entry(sequence = ++nextSequence, startedAtMs = now)
            if (existing == null) {
                entries[request] = entry
                trimToBound()
            }
            emitLine(entry, phase, now, outcome, recovered = existing == null)
        }
    }

    fun finish(
        request: AttachmentOpenRequest,
        outcome: String,
    ) {
        phase(request, AttachmentOpenPhase.TerminalResult, outcome)
        synchronized(entries) { entries.remove(request) }
    }

    fun cancelOutside(destination: AttachmentOpenDestination?) {
        synchronized(entries) {
            val cancelled =
                entries.keys.filter { request -> destination == null || request.destination != destination }
            cancelled.forEach { request ->
                val entry = entries.remove(request) ?: return@forEach
                emitLine(
                    entry,
                    AttachmentOpenPhase.NavigationCancelled,
                    nowMs(),
                    outcome = "destination_changed",
                    recovered = false,
                )
            }
        }
    }

    private fun emitLine(
        entry: Entry,
        phase: AttachmentOpenPhase,
        now: Long,
        outcome: String?,
        recovered: Boolean,
    ) {
        val fields =
            buildList {
                add("attachment_open")
                add("sequence=${entry.sequence}")
                add("phase=${phase.wireName}")
                add("elapsed_ms=${(now - entry.startedAtMs).coerceAtLeast(0L)}")
                if (recovered) add("recovered=true")
                outcome?.let { add("outcome=$it") }
            }
        emit(fields.joinToString(" "))
    }

    private fun trimToBound() {
        while (entries.size > MAX_ACTIVE_TRACES) {
            entries.remove(entries.keys.first())
        }
    }

    private companion object {
        const val MAX_ACTIVE_TRACES = 32
    }
}

internal object AttachmentOpenTrace {
    private const val TAG = "AttachmentOpenTrace"
    private val registry =
        AttachmentOpenTraceRegistry(
            nowMs = SystemClock::elapsedRealtime,
            emit = { line -> if (BuildConfig.DEBUG) Log.d(TAG, line) },
        )

    fun begin(request: AttachmentOpenRequest) = registry.begin(request)

    fun phase(
        request: AttachmentOpenRequest,
        phase: AttachmentOpenPhase,
        outcome: String? = null,
    ) = registry.phase(request, phase, outcome)

    fun finish(
        request: AttachmentOpenRequest,
        outcome: String,
    ) = registry.finish(request, outcome)

    fun cancelOutside(destination: AttachmentOpenDestination?) = registry.cancelOutside(destination)
}
