@file:Suppress("MagicNumber") // Versioned primitive layout and fixed raw-output limits.

package dev.ipf.whitenoise.android.media.sandbox

import android.os.IBinder
import android.os.Parcel
import java.io.IOException

/** Raw-output protocol. The worker receives no presentation, file-opening or account capability. */
internal object AvStreamProtocol {
    const val DESCRIPTOR = "dev.ipf.whitenoise.media.av.stream.v1"
    const val READY_DESCRIPTOR = "dev.ipf.whitenoise.media.av.stream.ready.v1"
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val READ = IBinder.FIRST_CALL_TRANSACTION + 1
    const val SEEK = IBinder.FIRST_CALL_TRANSACTION + 2
    const val RETIRE = IBinder.FIRST_CALL_TRANSACTION + 3
    const val READY = IBinder.FIRST_CALL_TRANSACTION
    const val MAX_REPLY_BYTES = 512
    const val MAX_EDGE = 4096
    const val MAX_PIXEL_BYTES = 64 * 1024 * 1024
    const val PCM_BYTES = 256 * 1024
    const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    const val CALL_DEADLINE_MS = 8000L
    const val WATCHDOG_MS = 6000L
    const val MAX_DURATION_US = 24L * 60 * 60 * 1_000_000

    fun pixelCapacity(edge: Int): Int {
        require(edge in 1..MAX_EDGE)
        return (edge.toLong() * edge * 4).also { require(it <= MAX_PIXEL_BYTES) }.toInt()
    }

    fun boolean(data: Parcel): Boolean = data.readInt().also { require(it in 0..1) } == 1

    fun writeTuple(
        data: Parcel,
        token: String,
        sequence: Long,
    ) {
        data.writeString(token)
        data.writeLong(sequence)
    }

    fun readTuple(data: Parcel): Pair<String, Long> {
        val token = checkNotNull(data.readString())
        val sequence = data.readLong()
        require(token.matches(Regex("[0-9a-f]{32}")) && sequence > 0)
        return token to sequence
    }

    fun writeValues(
        data: Parcel,
        values: LongArray,
    ) {
        data.writeInt(values.size)
        values.forEach(data::writeLong)
    }

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
        fun from(
            values: LongArray,
            edge: Int,
            video: Boolean,
            audio: Boolean,
        ): AvStreamOutput {
            require(values.size == 7)
            AvStreamProtocol.pixelCapacity(edge)
            require(values[0] in 0L..2L && values[5] in -10_000_000L..AvStreamProtocol.MAX_DURATION_US)
            when (values[0].toInt()) {
                0 -> require(values.all { it == 0L })
                1 -> {
                    require(video && values[1] in 1..edge.toLong() && values[2] in 1..edge.toLong())
                    require(values[3] == 0L && values[4] == 0L)
                    val bytes = values[1] * values[2] * 4
                    require(bytes <= AvStreamProtocol.pixelCapacity(edge) && values[6] == bytes)
                }
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

    @Synchronized fun close() {
        closed = true
    }

    @Synchronized fun bindCompleted(accepted: Boolean) {
        bindCompleted = true
        bindingAttempted = true
        if (!accepted) {
            definitiveNoWorker = true // Framework refused before any selected capability could be handed off.
        }
    }

    @Synchronized fun bindOutcomeUnknown() {
        bindCompleted = true
        bindingAttempted = true
    }

    @Synchronized fun neverBound() {
        check(!bindCompleted)
        bindCompleted = true
        definitiveNoWorker = true
        unbound = true
    }

    @Synchronized fun deathObserved() {
        closed = true // Binder death revokes the endpoint but supplies no OS process-absence proof.
    }

    @Synchronized fun processGoneObserved() {
        processGone = true
    }

    @Synchronized fun beginCall() {
        if (closed) throw IOException("Media session is closed")
        activeCalls++
    }

    @Synchronized fun endCall() {
        check(activeCalls > 0)
        activeCalls--
    }

    @Synchronized fun beginRpc() {
        check(!activeRpc)
        activeRpc = true
    }

    @Synchronized fun endRpc() {
        activeRpc = false
    }

    @Synchronized fun beginRetirement() {
        check(!activeRetirement)
        activeRetirement = true
    }

    @Synchronized fun endRetirement() {
        activeRetirement = false
    }

    @Synchronized fun beginUnbind(): Boolean {
        val lifetime = closed && bindCompleted
        val connection = bindingAttempted && !unbinding && !unbound && !cleanupFailed
        if (!lifetime || !connection) return false
        unbinding = true
        return true
    }

    @Synchronized fun unbindFinished(success: Boolean) {
        check(unbinding)
        unbound = success
        cleanupFailed = !success
    }

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

    @Synchronized fun epoch(): Long = epoch

    @Synchronized fun current(
        lane: AvStreamLane,
        expectedEpoch: Long,
    ): T? {
        checkEpoch(expectedEpoch)
        return owners[lane]
    }

    @Synchronized fun reserve(
        lane: AvStreamLane,
        expectedEpoch: Long,
        create: () -> T,
    ): T? {
        checkEpoch(expectedEpoch)
        if (owners.containsKey(lane)) return null
        return create().also { owners[lane] = it }
    }

    @Synchronized fun isCurrent(
        lane: AvStreamLane,
        expectedEpoch: Long,
        owner: T,
    ): Boolean = epoch == expectedEpoch && owners[lane] === owner

    @Synchronized fun accept(
        lane: AvStreamLane,
        expectedEpoch: Long,
        owner: T,
        action: () -> Boolean,
    ): Boolean {
        if (epoch != expectedEpoch || owners[lane] !== owner) return false
        return action()
    }

    @Synchronized fun release(
        lane: AvStreamLane,
        owner: T,
    ) {
        if (owners[lane] === owner) owners.remove(lane)
    }

    @Synchronized fun invalidateAll(): List<T> {
        epoch++
        return owners.values.toList()
    }

    private fun checkEpoch(expectedEpoch: Long) {
        if (epoch != expectedEpoch) throw IOException("Media account generation changed")
    }
}
