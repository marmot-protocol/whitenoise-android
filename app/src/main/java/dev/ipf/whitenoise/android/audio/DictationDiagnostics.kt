package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private const val DIAGNOSTIC_BARRIER_TIMEOUT_SECONDS = 5L

/** A process-wide diagnostic sink; capture and speech callbacks never perform disk IO. */
internal object DictationDiagnostics {
    @Volatile private var recorder: DictationDiagnosticRecorder? = null

    @Volatile var activeSession: Long = 0

    fun attach(context: Context) {
        recorder?.close()
        activeSession = 0
        recorder =
            DictationDiagnosticRecorder(
                DictationDiagnosticStore(
                    File(context.noBackupFilesDir, "dictation-diagnostics"),
                    BuildConfig.APP_SHORT_SHA,
                ),
            )
    }

    fun setEnabled(enabled: Boolean) {
        recorder?.setEnabled(enabled)
    }

    fun record(event: String) {
        val correlated = "$event active_session=$activeSession uptime_ms=${SystemClock.elapsedRealtime()}"
        Log.i("WNDictation", correlated)
        recorder?.record(correlated)
    }

    /** Called on the export IO dispatcher; a barrier includes accepted events before the snapshot. */
    fun snapshot(): Map<String, ByteArray> = recorder?.snapshot().orEmpty()

    fun clear(): Boolean = recorder?.clear() ?: false
}

internal class DictationDiagnosticRecorder(
    private val store: DictationDiagnosticStore,
    queueCapacity: Int = 256,
    private val barrierTimeoutSeconds: Long = DIAGNOSTIC_BARRIER_TIMEOUT_SECONDS,
) : AutoCloseable {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { runnable -> Thread(runnable, "dictation-diagnostics").apply { isDaemon = true } },
        )
    private val dropped = AtomicLong()
    private val filtered = AtomicLong()
    private val epoch = AtomicLong()

    @Volatile private var enabled = false

    /** Revocation invalidates queued work immediately and preserves previously stored files. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            enabled = value
            epoch.incrementAndGet()
        }
    }

    @Synchronized
    fun record(event: String) {
        if (!enabled) return
        val parsed =
            DictationDiagnosticSchema.parse(event) ?: run {
                dropped.incrementAndGet()
                return
            }
        filtered.addAndGet(parsed.filteredFields)
        recordFields(parsed.fields)
    }

    /** Queues an already allowlisted host diagnostic record without performing IO on its caller. */
    @Synchronized
    internal fun recordFields(fields: Map<String, Any>) {
        if (!enabled) return
        val capturedEpoch = epoch.get()
        runCatching {
            executor.execute {
                if (enabled && epoch.get() == capturedEpoch) {
                    runCatching { store.append(fields) }.onFailure { dropped.incrementAndGet() }
                }
            }
        }.onFailure { dropped.incrementAndGet() }
    }

    @Suppress("SwallowedException") // Export a closed failure code rather than private exception detail.
    fun snapshot(): Map<String, ByteArray> =
        try {
            executor
                .submit<Map<String, ByteArray>> { store.snapshot(enabled, dropped.get(), filtered.get()) }
                .get(barrierTimeoutSeconds, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            store.unavailableSnapshot(enabled, dropped.get(), "queue_full", filtered.get())
        } catch (_: TimeoutException) {
            store.unavailableSnapshot(enabled, dropped.get(), "barrier_timeout", filtered.get())
        } catch (failure: ExecutionException) {
            if (failure.cause is CancellationException) throw requireNotNull(failure.cause)
            store.unavailableSnapshot(enabled, dropped.get(), "store_failed", filtered.get())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }

    fun clear(): Boolean {
        val clearing =
            synchronized(this) {
                epoch.incrementAndGet()
                executor.submit<Boolean> {
                    val removed = store.clear()
                    dropped.set(0)
                    filtered.set(0)
                    removed
                }
            }
        return clearing.get(barrierTimeoutSeconds, TimeUnit.SECONDS)
    }

    override fun close() {
        setEnabled(false)
        executor.shutdownNow()
    }
}
