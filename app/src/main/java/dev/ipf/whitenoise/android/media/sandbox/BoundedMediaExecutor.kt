package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A timeout abandons delivery, never releases admission while a native call is still running. */
internal class BoundedMediaExecutor(
    private val executor: Executor,
) {
    private val admission = Semaphore(1)

    fun <T> run(
        timeoutMs: Long,
        isCurrent: () -> Boolean,
        dispose: (T) -> Unit = {},
        block: (ensureCurrent: () -> Unit) -> T?,
    ): T? {
        if (!isCurrent() || !admission.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return null
        val result = MediaTaskResult(isCurrent, dispose)
        val done = CountDownLatch(1)
        try {
            executor.execute {
                try {
                    result.checkCurrent()
                    result.finish(block(result::checkCurrent))
                } catch (_: Exception) {
                    // Retired requests and failed platform operations have no deliverable value.
                } catch (_: OutOfMemoryError) {
                    // Refuse allocation failures; never start a parallel replacement decoder.
                } finally {
                    admission.release()
                    done.countDown()
                }
            }
        } catch (error: RejectedExecutionException) {
            admission.release()
            throw error
        }
        return try {
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) result.take() else null
        } finally {
            result.abandon()
        }
    }

    companion object {
        // Process-owned: account reset closes delivery, but cannot create a new slot around a hung native call.
        val shared = BoundedMediaExecutor(Executors.newSingleThreadExecutor())
    }
}

private class MediaTaskResult<T>(
    private val isCurrent: () -> Boolean,
    private val dispose: (T) -> Unit,
) {
    private var abandoned = false
    private var value: T? = null

    @Synchronized fun checkCurrent() {
        if (abandoned || !isCurrent()) throw CancellationException("media operation retired")
    }

    @Synchronized fun finish(next: T?) {
        if (abandoned || !isCurrent()) next?.let(dispose) else value = next
    }

    @Synchronized fun take(): T? = if (isCurrent()) value.also { value = null } else null

    @Synchronized fun abandon() {
        abandoned = true
        value?.let(dispose)
        value = null
    }
}
