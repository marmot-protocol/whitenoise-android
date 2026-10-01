package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Pins the handoff between foreground runtime publication and a cold worker preflight. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarmotClientRootGateTest {
    /** A waiting preflight observes the foreground runtime and never opens its own root owner. */
    @Test
    fun preflightWaitsForForegroundPublicationAndReusesItsRuntime() =
        runTest {
            val constructing = CompletableDeferred<Unit>()
            val finishConstruction = CompletableDeferred<Unit>()
            var publishedRuntime: Any? = null
            var temporaryOpens = 0
            val foreground =
                backgroundScope.launch {
                    MarmotClientRootGate.withLease {
                        constructing.complete(Unit)
                        finishConstruction.await()
                        publishedRuntime = Any()
                    }
                }
            constructing.await()

            val preflight =
                backgroundScope.async {
                    MarmotClientRootGate.withLease {
                        publishedRuntime ?: Any().also { temporaryOpens++ }
                    }
                }
            runCurrent()
            assertFalse(preflight.isCompleted)

            finishConstruction.complete(Unit)
            runCurrent()
            assertSame(publishedRuntime, preflight.await())
            assertEquals(0, temporaryOpens)
            foreground.join()
        }
}
