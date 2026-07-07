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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val promptLock = ReentrantLock()

    @Volatile
    private var launcher: ActivityResultLauncher<Intent>? = null

    // The single-slot rendezvous for the one prompt allowed at a time. Set under
    // [promptLock] before launching; read (without the lock) by [deliverResult]
    // on the main thread.
    private val pending = AtomicReference<ArrayBlockingQueue<Delivery>?>(null)

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
        pending.get()?.offer(Delivery.Result(resultOk, data))
    }

    /**
     * Show [intent] via the foreground launcher and block the CALLING (worker)
     * thread until the result arrives or [timeoutMs] elapses. Never blocks the
     * main thread. Prompts are serialized: a second caller waits here until the
     * first resolves.
     */
    fun awaitApproval(
        intent: Intent,
        timeoutMs: Long,
    ): Outcome =
        promptLock.withLock {
            if (launcher == null) return Outcome.NoForegroundActivity
            val queue = ArrayBlockingQueue<Delivery>(1)
            pending.set(queue)
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
                pending.compareAndSet(queue, null)
            }
        }
}
