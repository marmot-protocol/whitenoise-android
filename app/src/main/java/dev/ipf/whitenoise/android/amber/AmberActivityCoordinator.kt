package dev.ipf.whitenoise.android.amber

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * App-scoped bridge between the engine's synchronous signer callbacks and the
 * foreground Activity's [ActivityResultLauncher].
 *
 * The engine invokes [ExternalAccountSignerFfi][dev.ipf.marmotkit.ExternalAccountSignerFfi]
 * methods synchronously on background tokio/JNI worker threads. ContentResolver
 * operations run directly on those threads; only the Intent-approval fallback
 * comes here, and it must launch on the main thread while blocking ONLY the
 * calling worker thread until Amber answers.
 *
 * Lifecycle: [MainActivity][dev.ipf.whitenoise.android.MainActivity] registers a
 * launcher in `onCreate` and [attach]es it; `onDestroy` [detach]es it. The
 * pending-approval handoff lives on this object (a process-scoped singleton),
 * NOT on the Activity — so a launcher swap across a configuration change
 * (Activity recreation) never loses an in-flight result: the recreated
 * Activity's launcher delivers into the same waiting queue. Process death tears
 * down the blocked worker and the engine together, so nothing leaks.
 *
 * A [ReentrantLock] serializes prompts, so Amber is only ever asked one thing at
 * a time and no two workers race for the single launcher.
 */
object AmberActivityCoordinator {
    // Lazy so loading this object (e.g. for the pure result-correlation check)
    // never touches the main Looper — that call only makes sense on-device.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val promptLock = ReentrantLock()

    @Volatile
    private var launcher: ActivityResultLauncher<Intent>? = null

    // The single-slot rendezvous for the one prompt allowed at a time. Set under
    // [promptLock] before launching; read (without the lock) by [deliverResult]
    // on the main thread.
    // The single in-flight prompt: its rendezvous queue plus the request id we
    // expect the result to echo back (EXTRA_ID). Set under [promptLock] before
    // launching; read (without the lock) by [deliverResult] on the main thread.
    private val pending = AtomicReference<Pending?>(null)

    /** Outcome of an Intent approval, as seen by the (worker-thread) caller. */
    sealed interface Outcome {
        data class Completed(
            val resultOk: Boolean,
            val data: Intent?,
        ) : Outcome

        /** No foreground Activity/launcher was available to show the prompt. */
        data object NoForegroundActivity : Outcome

        /** RESULT never arrived within the timeout. */
        data object TimedOut : Outcome
    }

    private sealed interface Delivery {
        data class Result(
            val resultOk: Boolean,
            val data: Intent?,
        ) : Delivery

        data object LauncherGone : Delivery
    }

    private data class Pending(
        val queue: ArrayBlockingQueue<Delivery>,
        val requestId: String?,
    )

    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    fun detach(launcher: ActivityResultLauncher<Intent>) {
        // Only clear if this exact launcher is still current: a fast recreate may
        // already have attached the new Activity's launcher before the old one's
        // onDestroy runs.
        if (this.launcher === launcher) this.launcher = null
    }

    /** Delivered on the main thread by MainActivity's launcher callback. */
    fun deliverResult(
        resultOk: Boolean,
        data: Intent?,
    ) {
        val active = pending.get() ?: return
        // Correlate by request id: a late result from a prior, timed-out prompt
        // must not satisfy the next caller. An id-bearing request accepts only a
        // result that echoes its id; get_public_key sends no id, so its result
        // (which likewise carries none) matches on both being null.
        val resultId = data?.getStringExtra(Nip55.EXTRA_ID)
        if (!shouldAcceptResult(active.requestId, resultId)) {
            // A dropped result means the waiting caller will burn the full
            // approval timeout — loud enough to find in a field logcat.
            android.util.Log.w(
                "AmberSigner",
                "dropped signer result: expectedId=${active.requestId} resultId=$resultId ok=$resultOk",
            )
            return
        }
        active.queue.offer(Delivery.Result(resultOk, data))
    }

    /**
     * Whether a delivered result should satisfy the active request. An
     * id-bearing request accepts only a result echoing the same `EXTRA_ID`, so
     * a prior, timed-out request's late result can never satisfy it.
     * get_public_key sends no id, and signers answer it with a self-generated
     * id — so a no-id request accepts whatever arrives while it is the single
     * pending prompt (the prompt lock serializes prompts, so there is nothing
     * else the result could belong to).
     */
    internal fun shouldAcceptResult(
        expectedId: String?,
        resultId: String?,
    ): Boolean = expectedId == null || expectedId == resultId

    /**
     * Show [intent] via the foreground launcher and block the CALLING (worker)
     * thread until the result arrives or [timeoutMs] elapses. Never blocks the
     * main thread. Prompts are serialized: a second caller waits here until the
     * first resolves.
     */
    fun awaitApproval(
        intent: Intent,
        timeoutMs: Long,
        requestId: String?,
    ): Outcome =
        promptLock.withLock {
            if (launcher == null) return Outcome.NoForegroundActivity
            val queue = ArrayBlockingQueue<Delivery>(1)
            val slot = Pending(queue, requestId)
            pending.set(slot)
            try {
                mainHandler.post {
                    val active = launcher
                    if (active == null) {
                        queue.offer(Delivery.LauncherGone)
                    } else {
                        try {
                            active.launch(intent)
                        } catch (_: Exception) {
                            // Signer package vanished / could not be launched.
                            queue.offer(Delivery.LauncherGone)
                        }
                    }
                }
                when (val delivery = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
                    is Delivery.Result -> Outcome.Completed(delivery.resultOk, delivery.data)
                    Delivery.LauncherGone -> Outcome.NoForegroundActivity
                    null -> Outcome.TimedOut
                }
            } finally {
                pending.compareAndSet(slot, null)
            }
        }
}
