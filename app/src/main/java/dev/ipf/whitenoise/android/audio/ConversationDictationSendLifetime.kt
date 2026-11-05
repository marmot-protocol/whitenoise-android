package dev.ipf.whitenoise.android.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounds admission, not the message sender's pending lifetime.
 *
 * The send is a child of the caller's process scope, outside the admission timeout. Once its
 * pending callback runs, ordinary message sending owns retries and terminal failure. Dictation
 * teardown can release its handle without leaving a hidden deadline attached to the transport.
 * Before handoff, expiry cancels and joins the one attempt; an uncertain result never retries.
 * The sender invokes callbacks on this scope's dispatcher, as required by the dispatch owner's
 * main-thread state. Production uses WhiteNoiseAppState's process-level mutationsScope.
 */
internal suspend fun sendDictationUntilHandoff(
    timeoutMillis: Long,
    request: ConversationDictationSendRequest,
    send: suspend (ConversationDictationSendRequest) -> Boolean,
): Boolean? =
    try {
        coroutineScope {
            val pending = CompletableDeferred<Unit>()
            val result =
                async {
                    send(
                        request.copy(onPendingShown = {
                            pending.complete(Unit)
                            request.onPendingShown()
                        }),
                    )
                }
            val handedOff =
                withTimeoutOrNull(timeoutMillis) {
                    select {
                        pending.onAwait { true }
                        result.onAwait { false }
                    }
                }
            if (handedOff == null && !pending.isCompleted) {
                result.cancelAndJoin()
                null
            } else {
                result.await()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
