package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Still-image worker generations. Each generation is one isolated binding with a unique instance name
 * and its own Binder death recipient, and carries at most one untrusted input. A new input is admitted
 * only after the previous input-bearing generation has a kernel Binder death callback. Clean spares,
 * which never receive input before they are claimed, are started while the current input is served,
 * so a retirement does not add a process start to the next admission. Live generations, including
 * retired ones whose death is still unconfirmed, never exceed one plus the spare limit.
 */
@Suppress("TooManyFunctions") // One cohesive owner for generation binding, admission, retirement and disposal.
internal class MediaWorkerPool(
    private val context: Context,
    private val instanceName: String,
    spares: Int,
    private val lock: Any,
    private val ended: (MediaWorker) -> Unit,
) {
    private val spareLimit = spares.coerceIn(0, MAX_SPARES)
    private val capacity = 1 + spareLimit
    private val generations = ArrayList<MediaWorker>(capacity)
    private var active: MediaWorker? = null
    private var closed = false
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = Executor { command -> handler.post(command) }

    /**
     * Waits up to [budgetMs] for the previous input-bearing generation's death, then claims a clean
     * connected generation, starting one when none is live. A refused or unknown bind fails this claim.
     * Waits are sliced so a session reset or close is observed without spending the whole budget.
     */
    @Suppress("ReturnCount") // Claimed, expired, refused and failed-start outcomes leave the loop separately.
    fun claim(
        budgetMs: Long,
        current: () -> Boolean,
    ): MediaWorker? {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            if (left <= 0L) return null
            when (val step = synchronized(lock) { nextStep(current) }) {
                is ClaimStep.Claimed -> return step.worker
                is ClaimStep.Await -> step.latch.await(minOf(left, WAIT_SLICE_MS), TimeUnit.MILLISECONDS)
                is ClaimStep.AwaitDeath -> awaitDeath(step.worker, minOf(left, WAIT_SLICE_MS))
                is ClaimStep.Start -> if (!start(step.worker)) return null
                ClaimStep.Refused -> return null
            }
        }
    }

    @Suppress("ReturnCount") // Each lane state has one distinct admission outcome.
    private fun nextStep(current: () -> Boolean): ClaimStep {
        if (closed || !current()) return ClaimStep.Refused
        // The death fence: no input reaches any generation while an earlier input-bearing one may live.
        active?.let { previous -> if (previous.dead.count != 0L) return ClaimStep.AwaitDeath(previous) }
        active = null
        generations.firstOrNull(MediaWorker::clean)?.let { ready ->
            ready.carriesInput = true
            active = ready
            return ClaimStep.Claimed(ready)
        }
        generations.firstOrNull(MediaWorker::starting)?.let { return ClaimStep.Await(it.connected) }
        if (generations.size < capacity) return ClaimStep.Start(reserve())
        // Every slot is held by a generation whose lifetime is unresolved, never exceed the bound.
        return generations.firstOrNull { it.dead.count != 0L }?.let { ClaimStep.AwaitDeath(it) } ?: ClaimStep.Refused
    }

    /** Starts clean spares up to the limit, a failed start leaves the next claim to bind cold. */
    fun refill() {
        while (true) {
            val spare =
                synchronized(lock) {
                    val spares = generations.count { !it.carriesInput && !it.retiring }
                    if (closed || spares >= spareLimit || generations.size >= capacity) null else reserve()
                } ?: return
            if (!start(spare)) return
        }
    }

    private fun reserve(): MediaWorker {
        val generation = bindingSequence.incrementAndGet()
        return MediaWorker(generation, workerInstanceName(instanceName, generation)).also { worker ->
            worker.connection = connection(worker)
            worker.deathRecipient = IBinder.DeathRecipient { died(worker) }
            generations += worker
        }
    }

    @Suppress("ReturnCount") // Accepted, definitively refused and unknown bind outcomes own different lifetimes.
    private fun start(worker: MediaWorker): Boolean {
        val accepted =
            try {
                context.bindIsolatedService(
                    Intent(context, MediaDecodeService::class.java),
                    Context.BIND_AUTO_CREATE,
                    worker.instance,
                    callbackExecutor,
                    worker.connection,
                )
            } catch (_: RuntimeException) {
                // A throwing bind has no authenticated lifetime outcome, the generation keeps its slot.
                synchronized(lock) {
                    worker.bindComplete = true
                    worker.retiring = true
                }
                warnLifetimeHold(worker, "bind-outcome")
                return false
            }
        val bound =
            synchronized(lock) {
                worker.bindComplete = true
                worker.bound = worker.bound || accepted
                if (!worker.bound) {
                    // Android definitively refused this binding: no process can carry it.
                    worker.retiring = true
                    worker.dead.countDown()
                    worker.connected.countDown()
                }
                worker.bound
            }
        if (worker.retiring) retire(worker, escalate = true)
        dispose(worker)
        return bound
    }

    private fun connection(worker: MediaWorker): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(lock) {
                    if (worker.disposed || worker.binder != null) return
                    worker.binder = service
                    worker.bound = true
                    worker.remote = Messenger(service)
                    try {
                        service.linkToDeath(worker.deathRecipient, 0)
                        worker.linked = true
                    } catch (_: android.os.RemoteException) {
                        // linkToDeath can report an already-dead remote, never infer death from send failure.
                        if (!service.isBinderAlive) worker.dead.countDown() else warnLifetimeHold(worker, "death-link")
                        worker.retiring = true
                    } catch (_: RuntimeException) {
                        worker.retiring = true
                        warnLifetimeHold(worker, "death-link")
                    }
                    if (closed) worker.retiring = true
                    worker.connected.countDown()
                }
                // A generation retired before it connected never received input: stop it at once.
                if (worker.retiring) retire(worker, escalate = true)
                dispose(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected(worker)

            override fun onBindingDied(name: ComponentName) = disconnected(worker)

            override fun onNullBinding(name: ComponentName) = disconnected(worker)
        }

    private fun disconnected(worker: MediaWorker) {
        synchronized(lock) {
            if (worker.disposed) return
            worker.retiring = true
            worker.connected.countDown()
            worker.ready.countDown()
            ended(worker)
        }
        // A binding notification is not a process-death receipt, release it so Android cannot restart it.
        retire(worker, escalate = true)
    }

    private fun died(worker: MediaWorker) {
        synchronized(lock) {
            worker.retiring = true
            worker.dead.countDown()
            worker.connected.countDown()
            worker.ready.countDown()
            ended(worker)
        }
        dispose(worker)
    }

    /**
     * Requests cooperative retirement. A worker that is still alive [RETIRE_ESCALATION_MS] later, or any
     * worker when [escalate] is set, is also unbound so Android stops its isolated process whether or not
     * the worker cooperates. Neither step reopens the slot: only the generation's Binder death does.
     */
    fun retire(
        worker: MediaWorker,
        escalate: Boolean = false,
    ) {
        val target =
            synchronized(lock) {
                worker.retiring = true
                if (worker.retireSent || worker.dead.count == 0L) {
                    null
                } else {
                    worker.remote?.also {
                        worker.retireSent = true
                        worker.retiredAtNanos = System.nanoTime()
                    }
                }
            }
        // Unbinding at once races Android's service teardown with the worker's own exit and slows death.
        runCatching { target?.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
        if (escalate) escalate(worker)
    }

    /** Waits up to [budgetMs] for [worker]'s Binder death, escalating to a system-enforced stop after the grace. */
    fun awaitDeath(
        worker: MediaWorker,
        budgetMs: Long,
    ): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            val grace =
                synchronized(lock) {
                    if (worker.retireSent) {
                        val retired = System.nanoTime() - worker.retiredAtNanos
                        TimeUnit.MILLISECONDS.toNanos(RETIRE_ESCALATION_MS) - retired
                    } else {
                        0L
                    }
                }
            if (grace <= 0L) escalate(worker)
            val slice = if (grace > 0L) minOf(left, TimeUnit.NANOSECONDS.toMillis(grace) + 1) else left
            if (worker.dead.await(slice.coerceAtLeast(0L), TimeUnit.MILLISECONDS)) return true
            if (until - System.nanoTime() <= 0L) return worker.dead.count == 0L
        }
    }

    /**
     * Unbinds a connected generation so Android stops its isolated process even if the worker ignores
     * RETIRE. A never-connected generation stays bound so a late connection can still supply its witness.
     */
    private fun escalate(worker: MediaWorker) {
        val unbind =
            synchronized(lock) {
                worker.retiring = true
                (worker.binder != null && worker.bindComplete && worker.bound && !worker.unbound).also {
                    if (it) worker.unbound = true
                }
            }
        if (unbind) runCatching { context.unbindService(worker.connection) }
    }

    /**
     * Marks every live generation, clean spares included, as retiring and returns them for [retire].
     * Callers capture this under their own state change so later generations are never swept up.
     */
    fun detachAll(): List<MediaWorker> = synchronized(lock) { generations.toList().onEach { it.retiring = true } }

    /** Releases a generation's binding and death link after its death or a definitive bind refusal. */
    fun dispose(worker: MediaWorker) {
        val unbind =
            synchronized(lock) {
                if (worker.dead.count != 0L || !worker.bindComplete || worker.disposed) return
                worker.disposed = true
                generations.remove(worker)
                if (active === worker) active = null
                // A definitive false bind still registers a connection that must be released.
                (!worker.unbound).also { worker.unbound = true }
            }
        if (unbind) runCatching { context.unbindService(worker.connection) }
        worker.binder?.let { binder -> runCatching { binder.unlinkToDeath(worker.deathRecipient, 0) } }
        quitWhenIdle()
    }

    /** Terminal: retires every generation and releases callback resources once all have died. */
    fun close() {
        val live =
            synchronized(lock) {
                closed = true
                detachAll()
            }
        live.forEach { retire(it, escalate = true) }
        quitWhenIdle()
    }

    private fun quitWhenIdle() {
        val idle = synchronized(lock) { closed && generations.isEmpty() }
        if (idle && callbackThread.isInitialized()) callbacks.quitSafely()
    }

    /** Live generations, held ones included, this is the process bound the pool enforces. */
    internal fun liveGenerationsForTest(): Int = synchronized(lock) { generations.size }

    /** One input-free diagnostic per held generation, this never supplies a death witness. */
    private fun warnLifetimeHold(
        worker: MediaWorker,
        reason: String,
    ) {
        val first =
            synchronized(lock) {
                if (worker.holdReported) false else true.also { worker.holdReported = true }
            }
        if (first) android.util.Log.w("MediaDecode", "Worker lifetime unresolved: $reason")
    }

    private sealed interface ClaimStep {
        class Claimed(
            val worker: MediaWorker,
        ) : ClaimStep

        class Await(
            val latch: CountDownLatch,
        ) : ClaimStep

        class AwaitDeath(
            val worker: MediaWorker,
        ) : ClaimStep

        class Start(
            val worker: MediaWorker,
        ) : ClaimStep

        data object Refused : ClaimStep
    }

    companion object {
        /** Clean spares beyond the one input-bearing generation, two keep the live-process bound at three. */
        const val MAX_SPARES = 2

        /** Production pre-warm depth: one spare missed the measured burst budget, two met it. */
        const val DEFAULT_SPARES = 2

        /** Cooperative RETIRE normally ends a worker within milliseconds, after this grace Android stops it. */
        const val RETIRE_ESCALATION_MS = 250L
        private const val WAIT_SLICE_MS = 50L
        private val bindingSequence = AtomicInteger()
    }
}

/** One binding generation. Its reply state is single-use because a generation carries one input. */
internal class MediaWorker(
    val generation: Int,
    val instance: String,
) {
    lateinit var connection: ServiceConnection
    lateinit var deathRecipient: IBinder.DeathRecipient
    var binder: IBinder? = null
    var remote: Messenger? = null
    var linked = false
    var bound = false
    var bindComplete = false
    var retiring = false
    var retireSent = false
    var unbound = false
    var disposed = false
    var holdReported = false
    var carriesInput = false
    var retiredAtNanos = 0L
    val connected = CountDownLatch(1)
    val dead = CountDownLatch(1)
    val ready = CountDownLatch(1)
    val completed = AtomicBoolean(false)

    @Volatile var result: Bundle? = null

    /** Connected with a death witness, never handed input and not retiring. */
    fun clean(): Boolean = linked && !retiring && !carriesInput && dead.count != 0L

    /** Bound and still waiting for its first connection callback. */
    fun starting(): Boolean = !retiring && !carriesInput && connected.count != 0L && dead.count != 0L
}
