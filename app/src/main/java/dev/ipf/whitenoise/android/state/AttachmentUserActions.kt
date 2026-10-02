package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CANCELLATION_ACK_DEADLINE_MILLIS = 5_000L

/** Orders platform-originated Retry and Cancel commands without storing native transfer or retry state. */
@Suppress("TooGenericExceptionCaught") // Any native/platform exception must release the action lane and report failure.
internal class AttachmentUserActions(
    private val scope: CoroutineScope,
) {
    private class Lane {
        val lifetime = StalenessGuard()
        var tail: Deferred<*>? = null
        var retry: Deferred<Boolean>? = null
        var retryToken = 0L
    }

    private val lock = Any()
    private val lanes = mutableMapOf<String, Lane>()
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val pendingRetries = pending.asStateFlow()

    /** Coalesces pending taps, admitting native retry before durable work or viewer delivery. */
    fun retry(
        key: String,
        admit: suspend () -> Unit,
        onAccepted: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean =
        synchronized(lock) {
            val lane = lanes.getOrPut(key, ::Lane)
            if (lane.retry?.isCompleted == false && lane.lifetime.isCurrent(lane.retryToken)) return false
            val token = lane.lifetime.advance()
            val previous = lane.tail
            lateinit var owner: Deferred<Boolean>
            owner =
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        previous?.join()
                        if (!lane.lifetime.isCurrent(token)) return@async false
                        admit()
                        synchronized(lock) { lane.lifetime.runIfCurrent(token, onAccepted) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        synchronized(lock) { lane.lifetime.runIfCurrent(token) { onFailure(failure) } }
                        false
                    } finally {
                        retire(key, lane, owner)
                    }
                }
            lane.tail = owner
            lane.retry = owner
            lane.retryToken = token
            pending.value += key
            owner.start()
            true
        }

    /** Fences pending handoffs immediately and executes native cancellation after any admitted retry. */
    fun cancel(
        key: String,
        action: suspend () -> Boolean,
        onResult: (Boolean) -> Unit = {},
    ): Deferred<Boolean> =
        synchronized(lock) {
            val lane = lanes.getOrPut(key, ::Lane)
            val token = lane.lifetime.advance()
            val previous = lane.tail
            pending.value -= key
            lateinit var owner: Deferred<Boolean>
            owner =
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        previous?.join()
                        val confirmed = action()
                        synchronized(lock) { lane.lifetime.runIfCurrent(token) { onResult(confirmed) } }
                        confirmed
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        synchronized(lock) { lane.lifetime.runIfCurrent(token) { onResult(false) } }
                        false
                    } finally {
                        retire(key, lane, owner)
                    }
                }
            lane.tail = owner
            scope.launch {
                delay(CANCELLATION_ACK_DEADLINE_MILLIS)
                synchronized(lock) {
                    if (!owner.isCompleted) lane.lifetime.runIfCurrent(token) { onResult(false) }
                }
            }
            owner.start()
            owner
        }

    /** Removes only the completed tail; older completions cannot retire a newer action. */
    private fun retire(
        key: String,
        lane: Lane,
        owner: Deferred<*>,
    ) {
        synchronized(lock) {
            if (lane.retry === owner) {
                lane.retry = null
                pending.value -= key
            }
            if (lane.tail === owner) lanes.remove(key, lane)
        }
    }
}
