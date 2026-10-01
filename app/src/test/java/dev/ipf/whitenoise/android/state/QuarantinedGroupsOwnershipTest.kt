package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class QuarantinedGroupsOwnershipTest {
    @Test fun retiredQueuedWorkNeverEntersNative() =
        runTest {
            var calls = 0
            var current = true
            val runtime =
                AppMarmotRuntime(
                    "private",
                    native { _, _ ->
                        calls++
                        emptyList<Any>()
                    },
                )
            val access =
                NativeQuarantinedGroupsAccess(
                    "account",
                    runtime,
                    { current },
                    StandardTestDispatcher(testScheduler),
                )
            val queued = launch { access.retry("group") }
            current = false
            runCurrent()
            assertTrue(queued.isCancelled)
            assertEquals(0, calls)
        }

    @Test fun admittedRecoveryCanFinishAfterCancellationWithoutBlockingAnotherNativeRead() =
        runTest {
            var recovery: Continuation<Boolean>? = null
            var reads = 0
            val runtime =
                AppMarmotRuntime(
                    "private",
                    native { method, arguments ->
                        when (method) {
                            "retryHydrateQuarantinedGroup" -> {
                                assertEquals("account", arguments[0])
                                assertEquals("group", arguments[1])
                                @Suppress("UNCHECKED_CAST")
                                recovery = arguments.last() as Continuation<Boolean>
                                COROUTINE_SUSPENDED
                            }
                            "quarantinedGroups" -> {
                                reads++
                                emptyList<Any>()
                            }
                            else -> error("unexpected native method")
                        }
                    },
                )
            val access =
                NativeQuarantinedGroupsAccess(
                    "account",
                    runtime,
                    { true },
                    StandardTestDispatcher(testScheduler),
                )
            val job = launch { access.retry("group") }
            runCurrent()
            job.cancel()
            val replacementRead = launch { access.load() }
            runCurrent()
            assertTrue(replacementRead.isCompleted)
            assertEquals(1, reads)
            assertFalse(job.isCompleted)
            recovery!!.resume(true)
            runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
        }

    private fun native(call: (String, Array<out Any?>) -> Any?): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "QuarantinedGroupsFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> call(method.name, arguments ?: emptyArray())
            }
        } as MarmotInterface
}
