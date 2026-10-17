package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * One controller's timeline lifetime, independent of mutation and attachment presentation.
 *
 * Owns startup admission, bounded initial reads, reconnect waits and native handle retirement.
 * Pages still commit through the controller's existing atomic window boundary. Its monitor and
 * active-call mutex are the same authorities used by that boundary and by paging: retiring a
 * timeline cannot close a handle under an admitted page or detach a replacement handle.
 * App/native-owned commands are deliberately absent from this scope.
 */
@Suppress("TooManyFunctions") // One lifecycle owner exposes admission, reads and retirement to the facade.
internal class ConversationTimelineSession {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val lock = Any()
    val activeCallMutex = Mutex()
    private val initialSubscriptionRead =
        SingleFlightBoundedInitialResourceRead<ConversationTimelineSubscriptionHandle>(
            closeUnclaimed = { lateStream -> runCatching { lateStream.close() } },
        )
    private val initialSnapshotRead = SingleFlightBoundedInitialSnapshotRead<TimelinePageFfi?>()
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)
    private var startJob: Job? = null

    // staleness-exempt: captured subscription-start token, not a counter owner.
    private var lastStartedGeneration: Long? = null
    private var groupSubscription: ConversationGroupStateSubscriptionHandle? = null
    private var handoff: ConversationTimelineHandoff? = null
    var currentScope: CoroutineScope? = null
        private set

    @Volatile
    var timelineSubscription: ConversationTimelineSubscriptionHandle? = null
        private set

    @Volatile
    var accountTeardownRequested = false
        private set

    @Volatile
    var cleared = false
        private set

    /** Admits one start per explicit retry generation; repeated composition calls do no work. */
    fun start(
        generation: Long,
        run: suspend CoroutineScope.() -> Unit,
    ) {
        val candidate =
            scope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                try {
                    coroutineScope {
                        currentScope = this
                        run()
                    }
                } finally {
                    currentScope = null
                    closeTimeline(timelineSubscription)
                    synchronized(lock) {
                        if (startJob === currentJob) startJob = null
                    }
                }
            }
        val admitted =
            synchronized(lock) {
                val retired = accountTeardownRequested || cleared
                val alreadyStarted = startJob?.isActive == true || lastStartedGeneration == generation
                if (retired || alreadyStarted) {
                    false
                } else {
                    lastStartedGeneration = generation
                    startJob = candidate
                    true
                }
            }
        if (admitted) candidate.start() else candidate.cancel()
    }

    /** Reconnects local projections with the existing backoff and explicit retry signal. */
    suspend fun runReconnectLoop(attempt: suspend () -> Pair<Boolean, Boolean>) {
        var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
        while (coroutineContext.isActive && !accountTeardownRequested) {
            val (shouldExit, connected) = attempt()
            if (shouldExit) return
            if (connected) retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            if (!coroutineContext.isActive || accountTeardownRequested) break
            val userRequestedRetry = withTimeoutOrNull(retryDelayMs) { retrySignal.receive() } != null
            retryDelayMs =
                if (userRequestedRetry) {
                    LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
                } else {
                    nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
                }
        }
    }

    /** Wakes a reconnect wait without starting a second subscription pair. */
    fun retry() {
        retrySignal.trySend(Unit)
    }

    /** Opens once, retaining bounded-read ownership until a late native result can be released. */
    suspend fun openTimeline(
        openPinnedConversationSubscription: suspend () -> ConversationTimelineSubscriptionHandle,
    ): ConversationTimelineSubscriptionHandle? {
        val stream = initialSubscriptionRead.await(openPinnedConversationSubscription)
        val admitted =
            synchronized(lock) {
                if (accountTeardownRequested || cleared) {
                    false
                } else {
                    timelineSubscription = stream
                    true
                }
            }
        if (admitted) return stream
        withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
        return null
    }

    /** Registers a paired group handle, releasing it immediately if the controller has retired. */
    suspend fun openGroup(
        openPinnedConversationSubscription: suspend () -> ConversationGroupStateSubscriptionHandle,
    ): ConversationGroupStateSubscriptionHandle? {
        val stream = openPinnedConversationSubscription()
        val admitted =
            synchronized(lock) {
                if (accountTeardownRequested || cleared) {
                    false
                } else {
                    groupSubscription = stream
                    true
                }
            }
        if (admitted) return stream
        withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
        return null
    }

    /** Bounds each replacement subscription's one-shot snapshot before waiting for live rows. */
    suspend fun snapshot(stream: ConversationTimelineSubscriptionHandle): TimelinePageFfi? =
        initialSnapshotRead.await(
            read = { stream.snapshot() },
        )

    /** Stops future opens after a native-open timeout without cancelling accepted mutations. */
    fun cancelInitialOpen() = initialSubscriptionRead.cancel()

    /** Installs the foreground receipt owner for the current pipeline only. */
    fun installHandoff(current: ConversationTimelineHandoff) {
        synchronized(lock) { handoff = current }
    }

    /** An old pipeline finalizer cannot detach its replacement's foreground receipts. */
    fun retireHandoff(current: ConversationTimelineHandoff) {
        current.close()
        synchronized(lock) { if (handoff === current) handoff = null }
    }

    /** Captures only pages received by this pipeline before the foreground boundary. */
    fun pendingAtForeground(): CompletableDeferred<Boolean>? = synchronized(lock) { handoff?.pendingAtForeground() }

    /** Retires the attempt's paired handles once, including teardown racing its finalizer. */
    suspend fun closeHandles(
        group: ConversationGroupStateSubscriptionHandle?,
        timeline: ConversationTimelineSubscriptionHandle?,
    ) {
        val closeGroup =
            synchronized(lock) {
                if (group != null && groupSubscription === group) {
                    groupSubscription = null
                    true
                } else {
                    false
                }
            }
        if (closeGroup) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { group?.close() } }
        }
        closeTimeline(timeline)
    }

    /** Holds the paging mutex through cancel/close and clears only the installed handle's sidecar. */
    private suspend fun closeTimeline(stream: ConversationTimelineSubscriptionHandle?) {
        if (stream == null) return
        withContext(NonCancellable) {
            activeCallMutex.withLock {
                val close =
                    synchronized(lock) {
                        if (timelineSubscription === stream) {
                            timelineSubscription = null
                            onTimelineRetired()
                            true
                        } else {
                            false
                        }
                    }
                if (close) {
                    withContext(Dispatchers.IO) {
                        runCatching { stream.cancel() }
                        runCatching { stream.close() }
                    }
                }
            }
        }
    }

    /** The window sidecar retires atomically with its handle under [lock]. */
    var onTimelineRetired: () -> Unit = {}

    /** Revokes admission before closing handles and awaiting the old start, avoiding self-join. */
    suspend fun closeForAccountTeardown(invalidate: () -> Unit) {
        val (group, timeline, job) =
            synchronized(lock) {
                accountTeardownRequested = true
                invalidate()
                Triple(groupSubscription, timelineSubscription, startJob).also { startJob = null }
            }
        closeHandles(group, timeline)
        if (shouldCancelLiveSubscriptionJob(job, coroutineContext[Job])) job?.cancelAndJoin()
    }

    /** Ends screen reads and receipts; mutation and native operation scopes have different owners. */
    fun dispose(invalidate: () -> Unit) {
        synchronized(lock) {
            cleared = true
            invalidate()
            handoff?.close()
        }
        initialSubscriptionRead.cancel()
        initialSnapshotRead.cancel()
        scope.cancel()
    }
}
