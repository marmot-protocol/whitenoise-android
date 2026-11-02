@file:Suppress("MagicNumber") // Versioned primitive layout and fixed raw-output limits.

package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel
import java.io.IOException

/** Raw-output protocol. The worker receives no presentation, file-opening or account capability. */
internal object AvStreamProtocol {
    const val DESCRIPTOR = "dev.ipf.whitenoise.media.av.stream.v2"
    const val READY_DESCRIPTOR = "dev.ipf.whitenoise.media.av.stream.ready.v1"
    const val REFUSED = 1
    const val CONTAINER_MALFORMED = 2
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val READ = IBinder.FIRST_CALL_TRANSACTION + 1
    const val SEEK = IBinder.FIRST_CALL_TRANSACTION + 2
    const val RETIRE = IBinder.FIRST_CALL_TRANSACTION + 3
    const val IDENTIFY = IBinder.FIRST_CALL_TRANSACTION + 4
    const val IDENTITY = IBinder.FIRST_CALL_TRANSACTION + 1
    const val READY = IBinder.FIRST_CALL_TRANSACTION
    const val ANY_TRACK = 0
    const val VIDEO_TRACK = 1
    const val AUDIO_TRACK = 2
    const val MAX_REPLY_BYTES = 512
    const val MAX_EDGE = 4096
    const val MAX_PIXEL_BYTES = 64 * 1024 * 1024
    const val PCM_BYTES = 256 * 1024
    const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    const val CALL_DEADLINE_MS = 8000L
    const val WATCHDOG_MS = 6000L
    const val MAX_DURATION_US = 24L * 60 * 60 * 1_000_000

    /** Validates the requested edge before computing the exact packed RGBA allocation bound. */
    fun pixelCapacity(edge: Int): Int {
        require(edge in 1..MAX_EDGE)
        return (edge.toLong() * edge * 4).also { require(it <= MAX_PIXEL_BYTES) }.toInt()
    }

    /** Rejects noncanonical wire booleans instead of treating arbitrary worker scalars as truthy. */
    fun boolean(data: Parcel): Boolean = data.readInt().also { require(it in 0..1) } == 1

    /** Writes only the parent-generated generation token and monotonic request sequence. */
    fun writeTuple(
        data: Parcel,
        token: String,
        sequence: Long,
    ) {
        data.writeString(token)
        data.writeLong(sequence)
    }

    /** Accepts a bounded hexadecimal generation token and positive request sequence only. */
    fun readTuple(data: Parcel): Pair<String, Long> {
        val token = checkNotNull(data.readString())
        val sequence = data.readLong()
        require(token.matches(Regex("[0-9a-f]{32}")) && sequence > 0)
        return token to sequence
    }

    /** Serializes a fixed scalar vector without native objects or parser-controlled strings. */
    fun writeValues(
        data: Parcel,
        values: LongArray,
    ) {
        data.writeInt(values.size)
        values.forEach(data::writeLong)
    }

    /** Requires the exact expected scalar count and no trailing primitive or object payload. */
    fun readValues(
        data: Parcel,
        count: Int,
    ): LongArray {
        require(data.readInt() == count && data.dataAvail() == count * Long.SIZE_BYTES)
        return LongArray(count) { data.readLong() }
    }
}

internal typealias AvStreamInfo = NativeMediaInfo

internal val AvStreamInfo.hasVideo: Boolean get() = width > 0 && height > 0
internal val AvStreamInfo.hasAudio: Boolean get() = sampleRate > 0 && channels > 0

/** Validates native metadata against raw output budgets and requires a usable requested track. */
internal fun avStreamInfo(values: LongArray): AvStreamInfo {
    val info = NativeMediaOutputPolicy.info(values).also { require(it.hasVideo || it.hasAudio) }
    return info
}

internal data class AvStreamOutput(
    val kind: Int,
    val width: Int,
    val height: Int,
    val rate: Int,
    val channels: Int,
    val timestampUs: Long,
    val byteCount: Int,
) {
    companion object {
        /** Validates every scalar before the parent reads shared pixels or PCM. */
        fun from(
            values: LongArray,
            edge: Int,
            video: Boolean,
            audio: Boolean,
        ): AvStreamOutput {
            require(values.size == 7)
            AvStreamProtocol.pixelCapacity(edge)
            require(values[0] in 0L..4L && values[5] in -10_000_000L..AvStreamProtocol.MAX_DURATION_US)
            when (values[0].toInt()) {
                0 -> require(values.all { it == 0L })
                3, 4 -> {
                    require(if (values[0] == 3L) video else audio)
                    require(values.drop(1).all { it == 0L })
                }
                1 -> validateVideoFrame(values, edge, video)
                2 -> {
                    require(audio && values[1] == 0L && values[2] == 0L)
                    require(values[3] in 8000L..96000L && values[4] in 1L..2L)
                    require(values[6] in 1..AvStreamProtocol.PCM_BYTES.toLong() && values[6] % (values[4] * 2) == 0L)
                }
            }
            return AvStreamOutput(
                values[0].toInt(),
                values[1].toInt(),
                values[2].toInt(),
                values[3].toInt(),
                values[4].toInt(),
                values[5],
                values[6].toInt(),
            )
        }

        /** Requires requested video and exact packed RGBA dimensions within the selected edge budget. */
        private fun validateVideoFrame(
            values: LongArray,
            edge: Int,
            video: Boolean,
        ) {
            require(video && values[1] in 1..edge.toLong() && values[2] in 1..edge.toLong())
            require(values[3] == 0L && values[4] == 0L)
            val bytes = values[1] * values[2] * 4
            require(bytes <= AvStreamProtocol.pixelCapacity(edge) && values[6] == bytes)
        }
    }
}

/** Holds admission on uncertain startup/death/cleanup; no elapsed-time release path exists. */
@Suppress("TooManyFunctions") // Every lifetime proof stays with its one generation.
internal class AvStreamLifetime {
    @Volatile var closed = false
        private set
    private var bindCompleted = false
    private var bindingAttempted = false
    private var processGone = false
    private var definitiveNoWorker = false
    private var unbinding = false
    private var unbound = false
    private var cleanupFailed = false
    private var activeCalls = 0
    private var activeRpc = false
    private var activeRetirement = false

    @Volatile var openMayHaveBeenDelivered = false
        private set

    /** Commit immediately before IPC; cancellation or an uncertain result never revokes exposure. */
    @Synchronized fun commitOpen(): Boolean {
        if (closed || openMayHaveBeenDelivered) return false
        openMayHaveBeenDelivered = true
        return true
    }

    /** Revokes new operations immediately; cleanup and exact process-absence evidence remain separate. */
    @Synchronized fun close() {
        closed = true
    }

    /** Records the framework bind outcome; only an explicit false establishes no worker creation. */
    @Synchronized fun bindCompleted(accepted: Boolean) {
        bindCompleted = true
        bindingAttempted = true
        if (!accepted) {
            definitiveNoWorker = true // Framework refused before any selected capability could be handed off.
        }
    }

    /** Retains process admission when a binding exception leaves creation uncertain. */
    @Synchronized fun bindOutcomeUnknown() {
        bindCompleted = true
        bindingAttempted = true
    }

    /** Records a pre-bind failure with no framework connection or possible worker to retire. */
    @Synchronized fun neverBound() {
        check(!bindCompleted)
        bindCompleted = true
        definitiveNoWorker = true
        unbound = true
    }

    /** Revokes a dead Binder endpoint without claiming that its OS process has disappeared. */
    @Synchronized fun deathObserved() {
        closed = true // Binder death revokes the endpoint but supplies no OS process-absence proof.
    }

    /** Records the separately calibrated kernel absence proof required before admission release. */
    @Synchronized fun processGoneObserved() {
        processGone = true
    }

    /** Holds a caller-copy credit until the operation finishes; closed generations reject acquisition. */
    @Synchronized fun beginCall() {
        if (closed) throw IOException("Media session is closed")
        activeCalls++
    }

    /** Settles one caller-copy credit without asserting RPC completion or worker death. */
    @Synchronized fun endCall() {
        check(activeCalls > 0)
        activeCalls--
    }

    /** Holds the single RPC credit independently of the waiting caller deadline. */
    @Synchronized fun beginRpc() {
        check(!activeRpc)
        activeRpc = true
    }

    /** Acknowledges actual RPC completion so timed-out Binder calls cannot lose their process credit. */
    @Synchronized fun endRpc() {
        activeRpc = false
    }

    /** Keeps retirement IPC accounted until its executor has actually settled. */
    @Synchronized fun beginRetirement() {
        check(!activeRetirement)
        activeRetirement = true
    }

    /** Settles retirement work without treating a successful send as process absence. */
    @Synchronized fun endRetirement() {
        activeRetirement = false
    }

    /** Grants one framework cleanup attempt only after close and a known bind completion. */
    @Synchronized fun beginUnbind(): Boolean {
        val lifetime = closed && bindCompleted
        val connection = bindingAttempted && !unbinding && !unbound && !cleanupFailed
        if (!lifetime || !connection) return false
        unbinding = true
        return true
    }

    /** Retains failed cleanup permanently; successful unbind alone never releases worker admission. */
    @Synchronized fun unbindFinished(success: Boolean) {
        check(unbinding)
        unbound = success
        cleanupFailed = !success
    }

    /** Requires absence, successful connection cleanup and all caller/RPC/retirement credits settled. */
    @Synchronized fun releasable(): Boolean {
        val absent = processGone || definitiveNoWorker
        val cleanup = closed && bindCompleted && absent && unbound && !cleanupFailed
        return cleanup && activeCalls == 0 && !activeRpc && !activeRetirement
    }
}

/** Exactly two fixed consumers: live playback and finite attachment analysis. */
internal enum class AvStreamLane {
    Playback,
    Analysis,
}

/** A lane cannot be replaced by a different owner or a newer account epoch before cleanup. */
internal class AvStreamAdmissions<T : Any> {
    private val owners = java.util.EnumMap<AvStreamLane, T>(AvStreamLane::class.java)
    private var epoch = 0L

    /** Returns the current account generation used to fence reservations and reply publication. */
    @Synchronized fun epoch(): Long = epoch

    /** Returns the retained lane owner only for the exact current account epoch. */
    @Synchronized fun current(
        lane: AvStreamLane,
        expectedEpoch: Long,
    ): T? {
        checkEpoch(expectedEpoch)
        return owners[lane]
    }

    /** Claims one fixed lane without replacing an unresolved generation or another account owner. */
    @Synchronized fun reserve(
        lane: AvStreamLane,
        expectedEpoch: Long,
        create: () -> T,
    ): T? {
        checkEpoch(expectedEpoch)
        if (owners.containsKey(lane)) return null
        return create().also { owners[lane] = it }
    }

    /** Checks both account epoch and object identity without releasing stale retained ownership. */
    @Synchronized fun isCurrent(
        lane: AvStreamLane,
        expectedEpoch: Long,
        owner: T,
    ): Boolean = epoch == expectedEpoch && owners[lane] === owner

    /** Runs a short publication or exposure commit atomically against account invalidation. */
    @Synchronized fun accept(
        lane: AvStreamLane,
        expectedEpoch: Long,
        owner: T,
        action: () -> Boolean,
    ): Boolean {
        if (epoch != expectedEpoch || owners[lane] !== owner) return false
        return action()
    }

    /** Removes only the exact retained owner after its independent cleanup proof is complete. */
    @Synchronized fun release(
        lane: AvStreamLane,
        owner: T,
    ) {
        if (owners[lane] === owner) owners.remove(lane)
    }

    /** Revokes both account lanes while retaining their process credits until exact cleanup. */
    @Synchronized fun invalidateAll(): List<T> {
        epoch++
        return owners.values.toList()
    }

    /** Rejects stale account operations before they can acquire or inspect current lane ownership. */
    private fun checkEpoch(expectedEpoch: Long) {
        if (epoch != expectedEpoch) throw IOException("Media account generation changed")
    }
}
