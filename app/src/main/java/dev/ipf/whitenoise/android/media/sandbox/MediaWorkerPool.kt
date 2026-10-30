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
 * Still-image worker generations (#3373). Each generation is one isolated binding with a unique instance name
 * and its own Binder death recipient. A claim binds a clean generation to one data-access domain for its whole
 * life: a reusable domain's generation serves that domain's later inputs and no other, and a single-use claim
 * gets a generation that carries one input. A domain has at most one live generation, and a retiring one must
 * die before its domain is served again; single-use inputs also wait for the previous single-use generation's
 * death. Clean spares, which never receive input before they are claimed, are started while inputs are
 * served. Live generations, including retired ones whose death is still unconfirmed, never exceed the live
 * limit: a new domain takes a spare or a free slot, or evicts the least recently used idle domain through the
 * same death fence. As many evictions run at once as claims wait for a slot, so an album of new domains is not
 * served one eviction at a time, and none beyond that.
 */
@Suppress("TooManyFunctions") // One cohesive owner for generation binding, admission, retirement and disposal.
internal class MediaWorkerPool(
    private val context: Context,
    private val instanceName: String,
    spares: Int,
    private val lock: Any,
    liveLimit: Int = 1 + spares.coerceIn(0, MAX_SPARES),
    private val ended: (MediaWorker) -> Unit,
) {
    private val capacity = liveLimit.coerceIn(1, MAX_LIVE_GENERATIONS)
    private val spareLimit = spares.coerceIn(0, minOf(MAX_SPARES, capacity - 1))
    private val generations = ArrayList<MediaWorker>(capacity)
    private var singleUseFence: MediaWorker? = null

    /** Claims now waiting for a slot; evictions in flight never outnumber them. Guarded by [lock]. */
    private var slotDemand = 0
    private var signal = CountDownLatch(1)
    private var closed = false
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = Executor { command -> handler.post(command) }

    /**
     * Claims a connected generation for [domain] within [budgetMs]: the domain's own idle generation when
     * [reusable], otherwise a clean one bound to [domain] from now on, starting or evicting as the live limit
     * allows. A refused or unknown bind fails this claim. Waits are sliced so a session reset or close is
     * observed without spending the whole budget.
     */
    @Suppress("ReturnCount") // Claimed, expired, refused and failed-start outcomes leave the loop separately.
    fun claim(
        domain: Any,
        reusable: Boolean,
        budgetMs: Long,
        current: () -> Boolean,
    ): MediaWorker? {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val demand = SlotDemand()
        try {
            while (true) {
                val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                if (left <= 0L) return null
                when (val step = synchronized(lock) { nextStep(domain, reusable, current, demand) }) {
                    is ClaimStep.Claimed -> return step.worker
                    is ClaimStep.Await -> step.latch.await(minOf(left, WAIT_SLICE_MS), TimeUnit.MILLISECONDS)
                    is ClaimStep.AwaitDeath -> awaitDeath(step.worker, minOf(left, WAIT_SLICE_MS))
                    is ClaimStep.Evict -> retire(step.worker)
                    is ClaimStep.Start -> if (!start(step.worker)) return null
                    ClaimStep.Refused -> return null
                }
            }
        } finally {
            synchronized(lock) { satisfy(demand) }
        }
    }

    /** Whether one claim is counted in [slotDemand]. Guarded by [lock]. */
    private class SlotDemand {
        var counted = false
    }

    /** Stops counting [demand] as waiting for a slot. Called under the lock. */
    private fun satisfy(demand: SlotDemand) {
        if (demand.counted) {
            demand.counted = false
            slotDemand--
        }
    }

    /** The next admission step for one claim, decided under the lock from the live generations. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Each lane state has one distinct admission outcome.
    private fun nextStep(
        domain: Any,
        reusable: Boolean,
        current: () -> Boolean,
        demand: SlotDemand,
    ): ClaimStep {
        if (closed || !current()) return ClaimStep.Refused
        // The single-use fence: no single-use input while the previous single-use generation may live.
        if (!reusable) {
            singleUseFence?.let { previous -> if (previous.dead.count != 0L) return ClaimStep.AwaitDeath(previous) }
            singleUseFence = null
        }
        // The domain fence: one live generation per domain, and a retiring one dies before the domain is served.
        generations.firstOrNull { it.domain == domain && it.dead.count != 0L }?.let { owner ->
            return when {
                owner.retiring -> ClaimStep.AwaitDeath(owner)
                owner.busy -> ClaimStep.Await(signal)
                else -> ClaimStep.Claimed(owner.also { it.busy = true })
            }
        }
        generations.firstOrNull(MediaWorker::clean)?.let { ready ->
            ready.domain = domain
            ready.reusable = reusable
            ready.busy = true
            if (!reusable) singleUseFence = ready
            return ClaimStep.Claimed(ready)
        }
        // A starting spare is speculative: wait for it only briefly while a slot could start a fresh one.
        generations
            .filter(MediaWorker::starting)
            .maxByOrNull { it.reservedAtNanos }
            ?.let { youngest ->
                val waited = System.nanoTime() - youngest.reservedAtNanos
                val young = waited < TimeUnit.MILLISECONDS.toNanos(STARTING_GRACE_MS)
                if (young || generations.size >= capacity) return ClaimStep.Await(youngest.connected)
            }
        if (generations.size < capacity) {
            satisfy(demand)
            return ClaimStep.Start(reserve())
        }
        // Every slot is taken, so this claim waits for one. Slots already being freed are awaited while they cover
        // every waiting claim, never doubled by another eviction; otherwise one more eviction runs alongside them.
        if (!demand.counted) {
            demand.counted = true
            slotDemand++
        }
        val freeing = generations.filter { it.retiring && it.dead.count != 0L }
        if (freeing.size >= slotDemand) return ClaimStep.AwaitDeath(freeing.first())
        // The least recently used idle domain leaves through the death fence.
        generations
            .filter { it.reusable && !it.busy && !it.retiring && it.dead.count != 0L }
            .minByOrNull { it.lastUsedNanos }
            ?.let { victim ->
                // Marked here, under the lock, so no claim for the victim's domain takes it before RETIRE.
                victim.retiring = true
                return ClaimStep.Evict(victim)
            }
        if (generations.any { it.busy }) return ClaimStep.Await(signal)
        // Every slot is held by a generation whose lifetime is unresolved, never exceed the bound.
        generations.firstOrNull { it.dead.count != 0L }?.let { return ClaimStep.AwaitDeath(it) }
        // Only generations already dead and not yet released hold the slots: their release signals.
        return if (generations.isEmpty()) ClaimStep.Refused else ClaimStep.Await(signal)
    }

    /**
     * Returns a reusable domain's generation after a valid reply. It serves its domain again until it has
     * carried [DOMAIN_INPUT_CAP] inputs or stays idle for [DOMAIN_IDLE_MS], then it is retired.
     */
    fun release(worker: MediaWorker) {
        val (spent, stamp) =
            synchronized(lock) {
                worker.busy = false
                worker.inputs++
                worker.lastUsedNanos = System.nanoTime()
                val spent = worker.inputs >= DOMAIN_INPUT_CAP || worker.retireOnRelease
                // Retiring under the same lock, so no claim can take a spent generation before RETIRE is sent.
                if (spent) worker.retiring = true
                signalLocked()
                spent to ++worker.useStamp
            }
        if (spent) {
            retire(worker)
        } else {
            handler.postDelayed({ retireIfIdle(worker, stamp) }, DOMAIN_IDLE_MS)
        }
    }

    /**
     * Returns a claimed generation its request never sent input to. One claimed fresh for this request becomes a
     * clean spare again; a domain's earlier generation waits for that domain's next input, unless the domain was
     * retired meanwhile.
     */
    fun unclaim(worker: MediaWorker) {
        val retireNow =
            synchronized(lock) {
                worker.busy = false
                if (!worker.served) {
                    worker.domain = null
                    worker.reusable = false
                    worker.retireOnRelease = false
                    if (singleUseFence === worker) singleUseFence = null
                }
                // A served generation whose domain was retired meanwhile retires now instead of waiting idle.
                val retireNow = worker.served && worker.retireOnRelease && !worker.retiring
                if (retireNow) worker.retiring = true
                signalLocked()
                retireNow
            }
        if (retireNow) retire(worker)
    }

    /**
     * Retires [domain]'s live generation, such as a pick removed from a draft or sent: at once when it is idle,
     * after its request in flight otherwise. Its next input, if any, waits for its death as usual.
     */
    fun retireDomain(domain: Any) {
        val idle =
            synchronized(lock) {
                val owner =
                    generations.firstOrNull { it.domain == domain && !it.retiring && it.dead.count != 0L }
                        ?: return
                if (owner.busy) {
                    owner.retireOnRelease = true
                    null
                } else {
                    owner.also { it.retiring = true }
                }
            }
        idle?.let(::retire)
    }

    private fun retireIfIdle(
        worker: MediaWorker,
        stamp: Int,
    ) {
        val idle =
            synchronized(lock) {
                (!worker.busy && !worker.retiring && worker.useStamp == stamp).also { if (it) worker.retiring = true }
            }
        if (idle) retire(worker)
    }

    /** Wakes claims waiting for a busy domain, a released slot or a death. Called under the lock. */
    private fun signalLocked() {
        signal.countDown()
        signal = CountDownLatch(1)
    }

    /** Starts clean spares up to the limit, a failed start leaves the next claim to bind cold. */
    fun refill() {
        while (true) {
            val spare =
                synchronized(lock) {
                    val spares = generations.count { it.domain == null && !it.retiring }
                    if (closed || spares >= spareLimit || generations.size >= capacity) null else reserve()
                } ?: return
            if (!start(spare)) return
        }
    }

    /** Adds one not-yet-bound generation with a unique instance name, counted against the live bound. */
    private fun reserve(): MediaWorker {
        val generation = bindingSequence.incrementAndGet()
        return MediaWorker(generation, workerInstanceName(instanceName, generation)).also { worker ->
            worker.reservedAtNanos = System.nanoTime()
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
                    signalLocked()
                }
                // A generation retired before it connected never received input: stop it at once.
                if (worker.retiring) retire(worker, escalate = true)
                dispose(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected(worker)

            override fun onBindingDied(name: ComponentName) = disconnected(worker)

            override fun onNullBinding(name: ComponentName) = disconnected(worker)
        }

    /**
     * A binding Android declares dead or null before it ever connected never received input: it is unbound
     * and its slot freed. After a connection, a binding notification is no death receipt, so the generation
     * is stopped through Android but keeps its slot until its Binder death.
     */
    private fun disconnected(worker: MediaWorker) {
        val neverConnected =
            synchronized(lock) {
                if (worker.disposed) return
                worker.retiring = true
                worker.connected.countDown()
                worker.abortExchange()
                ended(worker)
                signalLocked()
                (worker.binder == null && worker.domain == null).also { worker.abandoned = it }
            }
        if (neverConnected) dispose(worker) else retire(worker, escalate = true)
    }

    private fun died(worker: MediaWorker) {
        synchronized(lock) {
            worker.retiring = true
            worker.dead.countDown()
            worker.connected.countDown()
            worker.abortExchange()
            ended(worker)
            signalLocked()
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
                signalLocked()
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
        if (escalate) {
            escalate(worker)
        } else if (target != null) {
            // Nobody may wait for this death, as after an abandoned request, so escalate on our own clock too.
            handler.postDelayed({ if (worker.dead.count != 0L) escalate(worker) }, RETIRE_ESCALATION_MS)
        }
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

    /**
     * Releases a generation's binding and death link after its death, a definitive bind refusal or a binding
     * Android declared dead before it connected.
     */
    fun dispose(worker: MediaWorker) {
        val unbind =
            synchronized(lock) {
                val gone = worker.dead.count == 0L || worker.abandoned
                if (!gone || !worker.bindComplete || worker.disposed) return
                worker.disposed = true
                generations.remove(worker)
                if (singleUseFence === worker) singleUseFence = null
                signalLocked()
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

        class Evict(
            val worker: MediaWorker,
        ) : ClaimStep

        class Start(
            val worker: MediaWorker,
        ) : ClaimStep

        data object Refused : ClaimStep
    }

    companion object {
        /** Clean spares kept ready, at most two, so the live-process bound stays at three. */
        const val MAX_SPARES = 2

        /** Single-use pre-warm depth: in matched emulator runs one spare missed the burst budget, two met it. */
        const val DEFAULT_SPARES = 2

        /** The live-process bound for every generation of one lane, held and retiring ones included. */
        const val MAX_LIVE_GENERATIONS = 3

        /** Domain lane pre-warm depth: one spare keeps a new domain off the process-start path within the bound. */
        const val DOMAIN_SPARES = 1

        /** A domain's generation is replaced after this many inputs, bounding what any one process accumulates. */
        const val DOMAIN_INPUT_CAP = 64

        /** A domain's generation idle this long is retired, freeing its memory once its conversation goes quiet. */
        const val DOMAIN_IDLE_MS = 30_000L

        /** Cooperative RETIRE normally ends a worker within milliseconds, after this grace Android stops it. */
        const val RETIRE_ESCALATION_MS = 250L
        private const val WAIT_SLICE_MS = 50L

        /** How long a claim waits on a starting spare before using a free slot for a fresh generation. */
        private const val STARTING_GRACE_MS = 1_000L
        private val bindingSequence = AtomicInteger()
    }
}

/** One binding generation, bound to at most one domain for its whole life. */
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
    var abandoned = false

    /** The domain this generation serves, set once at its first claim, null while it is a clean spare. */
    var domain: Any? = null

    /** Whether [domain] may send further inputs here, false for a single-use generation. */
    var reusable = false

    /** Claimed for an input now in flight. */
    var busy = false

    /** Its domain was retired while this request was in flight; it retires when the request is released. */
    var retireOnRelease = false

    /** Sent at least one input; a generation that never was can return to being a clean spare. */
    var served = false
    var inputs = 0
    var useStamp = 0
    var lastUsedNanos = 0L
    var reservedAtNanos = 0L
    var retiredAtNanos = 0L
    val connected = CountDownLatch(1)
    val dead = CountDownLatch(1)

    /** The one request this generation may answer now, its reply capability is revoked with it. */
    var exchange: MediaExchange? = null

    /** Connected with a death witness, never bound to a domain and not retiring. */
    fun clean(): Boolean = linked && !retiring && domain == null && dead.count != 0L

    /** Bound and still waiting for its first connection callback. */
    fun starting(): Boolean = !retiring && domain == null && connected.count != 0L && dead.count != 0L

    /** Fails the in-flight request without a result, when this generation disconnects or dies. Under the lock. */
    fun abortExchange() {
        exchange?.abort()
    }
}

/** One request's single-use reply slot, identified by [id] on the wire. */
internal class MediaExchange(
    val id: Int,
) {
    val ready = CountDownLatch(1)
    val completed = AtomicBoolean(false)

    @Volatile var result: Bundle? = null

    /** Ends the request with no result, a later reply finds it completed. */
    fun abort() {
        completed.set(true)
        result = null
        ready.countDown()
    }
}
