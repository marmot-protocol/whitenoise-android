package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.audio.DictationDiagnosticRecorder
import dev.ipf.whitenoise.android.audio.DictationDiagnosticStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Closed native/host failure vocabulary: no message, identity, route or raw exception ever enters a trace. */
internal enum class DmCreationFailure {
    NONE,
    MISSING_KEY_PACKAGE,
    MISSING_INBOX,
    INVALID_KEY_PACKAGE,
    INVALID_IDENTITY,
    HYDRATION_PENDING,
    INDEX_NOT_READY,
    LOOKUP_TIMEOUT,
    LOOKUP_UNAVAILABLE,
    OTHER_BINDING_ERROR,
    CREATED_PROJECTION_UNAVAILABLE,
    PUBLISH,
    CANCELLED,
    OWNER_REPLACED,
    UNKNOWN,
}

/** Maps only published binding types; discovery completeness and Welcome outcomes remain unavailable. */
internal fun dmCreationFailure(error: Throwable): DmCreationFailure =
    when (error) {
        is CancellationException -> DmCreationFailure.CANCELLED
        is MarmotKitException.MissingKeyPackage -> DmCreationFailure.MISSING_KEY_PACKAGE
        is MarmotKitException.MissingMemberInboxRoute -> DmCreationFailure.MISSING_INBOX
        is MarmotKitException.InvalidKeyPackageEvent -> DmCreationFailure.INVALID_KEY_PACKAGE
        is MarmotKitException.InvalidIdentity -> DmCreationFailure.INVALID_IDENTITY
        is MarmotKitException.GroupHydrationPending -> DmCreationFailure.HYDRATION_PENDING
        is MarmotKitException.DirectConversationIndexNotReady -> DmCreationFailure.INDEX_NOT_READY
        is MarmotKitException.CreatedGroupProjectionUnavailable -> DmCreationFailure.CREATED_PROJECTION_UNAVAILABLE
        is MarmotKitException.Publish -> DmCreationFailure.PUBLISH
        is MarmotKitException -> DmCreationFailure.OTHER_BINDING_ERROR
        else -> DmCreationFailure.UNKNOWN
    }

/** Closed Android-visible operation boundaries; native subphase distinctions remain unavailable. */
internal enum class DmCreationPhase { PREWARM, EXISTING_LOOKUP, CREATE, PROJECTION, FIRST_FRAME, OWNER }

internal enum class DmCreationOutcome { START, SUCCESS, FAILURE, CANCELLED, REPLACED }

/** One transient screen/recipient interaction, with no identity-bearing or persisted correlation key. */
internal class DmCreationInteraction(
    private val emit: (Map<String, Any>) -> Unit = DmCreationDiagnostics::record,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val token = UUID.randomUUID().toString()
    private val start = nowNanos()
    private val attempts = AtomicLong(-1L)

    /** Retries stay related without retaining any recipient/account/group identifier. */
    fun nextAttempt(): DmCreationAttempt = attempt(attempts.updateAndGet { (it + 1L).coerceAtLeast(1L) })

    /** Every re-preparation gets a distinct ordinal; the initial preparation can precede tap ordinal one. */
    fun preparation(): DmCreationAttempt = attempt(attempts.incrementAndGet())

    /** Separates overlapping preparation phases while keeping one interaction clock and attempt owner. */
    private fun attempt(ordinal: Long): DmCreationAttempt {
        val starts = ConcurrentHashMap<DmCreationPhase, Long>()
        return DmCreationAttempt { phase, outcome, failure ->
            val now = nowNanos()
            if (outcome == DmCreationOutcome.START) starts[phase] = now
            val duration = starts[phase]?.let { ((now - it) / NANOS_PER_MS).coerceIn(0, MAX_ELAPSED_MS) } ?: -1L
            emit(
                mapOf(
                    "operation" to "dm_create_open",
                    "interaction" to token,
                    "attempt" to ordinal,
                    "phase" to phase.name.lowercase(java.util.Locale.ROOT),
                    "outcome" to outcome.name.lowercase(java.util.Locale.ROOT),
                    "failure" to failure.name.lowercase(java.util.Locale.ROOT),
                    "elapsed_ms" to ((now - start) / NANOS_PER_MS).coerceIn(0, MAX_ELAPSED_MS),
                    "phase_duration_ms" to duration,
                    "native_phase_detail" to "unavailable",
                ),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_ELAPSED_MS = 86_400_000L
    }
}

/** Carries only a diagnostic callback, never recipient data or a native command. */
internal class DmCreationAttempt(
    private val emit: (DmCreationPhase, DmCreationOutcome, DmCreationFailure) -> Unit,
) {
    private val phaseFailures = ConcurrentHashMap<DmCreationPhase, DmCreationFailure>()

    /** Emits one typed boundary result without consulting private exception messages or stack contents. */
    fun record(
        phase: DmCreationPhase,
        outcome: DmCreationOutcome,
        failure: DmCreationFailure = DmCreationFailure.NONE,
    ) {
        if (outcome == DmCreationOutcome.START) phaseFailures.remove(phase)
        if (outcome == DmCreationOutcome.FAILURE || outcome == DmCreationOutcome.CANCELLED) {
            phaseFailures[phase] = failure
        }
        emit(phase, outcome, failure)
    }

    /** Both preparation and taps classify an uncertain resolution identically, preserving any typed native cause. */
    fun lookupFinished(definitive: Boolean) =
        record(
            DmCreationPhase.EXISTING_LOOKUP,
            if (definitive) DmCreationOutcome.SUCCESS else DmCreationOutcome.FAILURE,
            if (definitive) {
                DmCreationFailure.NONE
            } else {
                phaseFailures[DmCreationPhase.EXISTING_LOOKUP] ?: DmCreationFailure.LOOKUP_UNAVAILABLE
            },
        )

    /** Lifecycle replacement is distinct from a caller cancellation and carries no owner identifiers. */
    fun ownerReplaced() = record(DmCreationPhase.OWNER, DmCreationOutcome.REPLACED, DmCreationFailure.OWNER_REPLACED)

    /** Failure attribution remains bound to this captured attempt even after its UI owner is replaced. */
    fun failed(
        phase: DmCreationPhase,
        error: Throwable,
    ) = record(
        phase,
        if (error is CancellationException) DmCreationOutcome.CANCELLED else DmCreationOutcome.FAILURE,
        dmCreationFailure(error),
    )
}

/** Bounded, opt-in local diagnostics share the existing private writer/retention/export controls. */
internal object DmCreationDiagnostics {
    @Volatile private var recorder: DictationDiagnosticRecorder? = null

    @Volatile private var enabled = false
    private val pendingFrames = mutableStateMapOf<Destination, PendingFrame>()

    /** Installs an independent no-backup stream; no records enter automatic native telemetry upload. */
    @Synchronized
    fun attach(context: Context) {
        enabled = false
        recorder?.close()
        recorder =
            DictationDiagnosticRecorder(
                DictationDiagnosticStore(
                    File(context.noBackupFilesDir, "dm-create-diagnostics"),
                    BuildConfig.APP_SHORT_SHA,
                    filePrefix = "dm-create",
                ),
            )
        synchronized(pendingFrames) { pendingFrames.clear() }
    }

    /** Uses the same audit recording/disclosure grant and immediate revoke fence as other local diagnostics. */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (!enabled) this.enabled = false
        synchronized(pendingFrames) {
            if (!enabled) pendingFrames.clear()
            recorder?.setEnabled(enabled)
            this.enabled = enabled && recorder != null
        }
    }

    /** Adds only compile-time/public platform context; unsupported revision fields say unknown. */
    fun record(fields: Map<String, Any>) {
        if (!enabled) return
        recorder?.recordFields(fields.filterKeys { it in TRACE_FIELDS } + buildContext)
    }

    private val buildContext by lazy {
        mapOf(
            "app_version" to safeBuildToken(BuildConfig.VERSION_NAME),
            "app_version_code" to BuildConfig.VERSION_CODE,
            "distribution" to safeBuildToken(BuildConfig.FLAVOR_distribution),
            "android_api" to Build.VERSION.SDK_INT,
            "mdk_revision" to safeBuildToken(BuildConfig.MDK_SHORT_SHA),
        )
    }

    /** Called by the existing user-approved archive exporter on its IO dispatcher. */
    fun snapshot(): Map<String, ByteArray> = recorder?.snapshot().orEmpty()

    /** Clear retires pending frame tickets and queued records before removing the bounded history. */
    fun clear(): Boolean =
        synchronized(pendingFrames) {
            pendingFrames.clear()
            recorder?.clear() ?: false
        }

    /** A bounded navigation ticket correlates only the matching first frame, without serializing identities. */
    fun awaitFrame(
        account: String,
        group: String,
        generation: Int,
        attempt: DmCreationAttempt,
    ) {
        synchronized(pendingFrames) {
            if (!enabled) return
            attempt.record(DmCreationPhase.FIRST_FRAME, DmCreationOutcome.START)
            pruneFrames()
            val destination = Destination(account, group, generation)
            while (destination !in pendingFrames && pendingFrames.size >= MAX_PENDING_FRAMES) {
                pendingFrames.entries
                    .minByOrNull { it.value.createdAt }
                    ?.key
                    ?.let(pendingFrames::remove)
            }
            pendingFrames[destination] = PendingFrame(attempt, SystemClock.elapsedRealtime())
        }
    }

    /** Observable ticket identity also admits a new attempt into an already-mounted destination. */
    fun pendingFrame(
        account: String,
        group: String,
        generation: Int,
    ): DmCreationAttempt? =
        synchronized(pendingFrames) {
            pendingFrames[Destination(account, group, generation)]
                ?.takeIf { SystemClock.elapsedRealtime() - it.createdAt <= FRAME_TTL_MS }
                ?.attempt
        }

    /** Consumes the exact ticket once and rejects stale effects after a same-destination retry. */
    fun firstFrame(
        account: String,
        group: String,
        generation: Int,
        attempt: DmCreationAttempt,
    ) {
        synchronized(pendingFrames) {
            pruneFrames()
            val destination = Destination(account, group, generation)
            if (pendingFrames[destination]?.attempt === attempt) {
                pendingFrames.remove(destination)
                attempt.record(DmCreationPhase.FIRST_FRAME, DmCreationOutcome.SUCCESS)
            }
        }
    }

    /** Expires only in-memory first-frame tickets; trace retention is enforced by the disk store. */
    private fun pruneFrames() {
        val now = SystemClock.elapsedRealtime()
        pendingFrames.entries.removeAll { now - it.value.createdAt > FRAME_TTL_MS }
    }

    /** Compile metadata is a small token rather than an arbitrary string from a provider or exception. */
    private fun safeBuildToken(value: String): String {
        val allowed = value.matches(BUILD_TOKEN)
        return if (allowed) value else "unknown"
    }

    private data class Destination(
        val account: String,
        val group: String,
        val generation: Int,
    )

    private data class PendingFrame(
        val attempt: DmCreationAttempt,
        val createdAt: Long,
    )

    private val BUILD_TOKEN = Regex("[A-Za-z0-9._-]{1,80}")
    private val TRACE_FIELDS =
        setOf(
            "operation",
            "interaction",
            "attempt",
            "phase",
            "outcome",
            "failure",
            "elapsed_ms",
            "phase_duration_ms",
            "native_phase_detail",
        )
    private const val MAX_PENDING_FRAMES = 8
    private const val FRAME_TTL_MS = 30_000L
}
