package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AppRuntimeListenerTeardownOwnerTest {
    @Test
    fun teardownWaitsForEachProducerBeforeProceedingInOrder() =
        runBlocking {
            val stages = listOf("recovery", "push drain", "listener", "unread refresh")
            val entered = stages.map { CompletableDeferred<Unit>() }
            val finished = stages.map { CompletableDeferred<Unit>() }
            val calls = mutableListOf<String>()

            fun stop(index: Int): suspend () -> Unit =
                {
                    calls += "${stages[index]} entered"
                    entered[index].complete(Unit)
                    finished[index].await()
                    calls += "${stages[index]} finished"
                }
            val owner =
                AppRuntimeListenerTeardownOwner(
                    cancelNetworkRecovery = stop(0),
                    cancelPushWakeDrain = stop(1),
                    cancelListener = stop(2),
                    clearUnreadRefresh = stop(3),
                    receiverActive = MutableStateFlow(false),
                )
            val stopping = launch(start = CoroutineStart.UNDISPATCHED) { owner.stopForAccountTeardown() }
            try {
                for (index in stages.indices) {
                    withTimeout(5_000) { entered[index].await() }
                    val completed = stages.take(index).flatMap { listOf("$it entered", "$it finished") }
                    assertEquals(completed + "${stages[index]} entered", calls)
                    assertFalse(stopping.isCompleted)
                    finished[index].complete(Unit)
                }
                withTimeout(5_000) { stopping.join() }
                assertEquals(
                    stages.flatMap { listOf("$it entered", "$it finished") },
                    calls,
                )
            } finally {
                finished.forEach { it.complete(Unit) }
                stopping.cancelAndJoin()
            }
        }

    @Test
    fun teardownPropagatesEachFailureWithoutRunningLaterStages() =
        runBlocking {
            val stages = listOf("recovery", "push drain", "listener", "unread refresh")
            val failures = listOf(IllegalStateException("stage failed"), CancellationException("stage cancelled"))
            for (failedStage in stages.indices) {
                for (failure in failures) {
                    val calls = mutableListOf<String>()

                    fun stop(index: Int): suspend () -> Unit =
                        {
                            calls += stages[index]
                            if (index == failedStage) throw failure
                        }
                    val owner =
                        AppRuntimeListenerTeardownOwner(
                            cancelNetworkRecovery = stop(0),
                            cancelPushWakeDrain = stop(1),
                            cancelListener = stop(2),
                            clearUnreadRefresh = stop(3),
                            receiverActive = MutableStateFlow(false),
                        )
                    try {
                        owner.stopForAccountTeardown()
                        fail("teardown swallowed the failure")
                    } catch (actual: RuntimeException) {
                        assertSame(failure, actual)
                    }
                    assertEquals(stages.take(failedStage + 1), calls)
                }
            }
        }
}
