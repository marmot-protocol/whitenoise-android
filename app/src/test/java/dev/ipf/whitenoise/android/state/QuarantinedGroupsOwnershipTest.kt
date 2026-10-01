package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

class QuarantinedGroupsOwnershipTest {
    @Test fun queuedWorkRejectsAccountABAAndRuntimeReplacementBeforeNativeEntry() =
        runTest {
            val calls = mutableListOf<String>()
            val runtime =
                AppMarmotRuntime(
                    "private",
                    native { method, _ ->
                        calls.add(method)
                        emptyList<Any>()
                    },
                )
            var epoch = 1
            var active = "a"
            var currentRuntime = runtime
            val access =
                NativeQuarantinedGroupsAccess(
                    "a",
                    runtime,
                    { active == "a" && epoch == 1 && currentRuntime === runtime },
                    StandardTestDispatcher(testScheduler),
                )
            val old = launch { access.load() }
            active = "b"
            epoch++
            active = "a"
            epoch++
            runCurrent()
            assertTrue(old.isCancelled)
            assertTrue(calls.isEmpty())
            access.close()
            val another =
                NativeQuarantinedGroupsAccess(
                    "a",
                    runtime,
                    { currentRuntime === runtime },
                    StandardTestDispatcher(testScheduler),
                )
            val queued = launch { another.retry("group") }
            currentRuntime = runtime.copy()
            runCurrent()
            assertTrue(queued.isCancelled)
            assertTrue(calls.isEmpty())
            another.close()
        }

    @Test fun admittedRecoverySurvivesDisposalAndSerializesReentryUntilItsActualResponse() =
        runTest {
            var recovery: Continuation<Boolean>? = null
            var reads = 0
            var retries = 0
            val runtime =
                AppMarmotRuntime(
                    "private",
                    native { method, arguments ->
                        when (method) {
                            "retryHydrateQuarantinedGroup" -> {
                                assertEquals("account", arguments[0])
                                assertEquals("group", arguments[1])
                                retries++
                                @Suppress("UNCHECKED_CAST")
                                recovery = arguments.last() as Continuation<Boolean>
                                COROUTINE_SUSPENDED
                            }
                            "quarantinedGroups" -> {
                                assertEquals("account", arguments[0])
                                reads++
                                emptyList<Any>()
                            }
                            else -> error("unexpected native method")
                        }
                    },
                )
            val dispatcher = StandardTestDispatcher(testScheduler)
            val old = NativeQuarantinedGroupsAccess("account", runtime, { true }, dispatcher)
            val job = launch { old.retry("group") }
            runCurrent()
            assertEquals(1, retries)
            job.cancel()
            old.close()
            val replacement = NativeQuarantinedGroupsAccess("account", runtime, { true }, dispatcher)
            val load = async { replacement.load() }
            runCurrent()
            assertEquals(0, reads)
            assertFalse(job.isCompleted)
            recovery!!.resume(true)
            runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
            assertTrue(load.await().isEmpty())
            assertEquals(1, reads)
            assertEquals(1, retries)
            replacement.close()
        }

    @Test fun retiredUnadmittedAccessNeverCallsNativeAndLeasesAreReleasedAfterAllUsers() {
        val runtime = Any()
        val lease = QuarantinedGroupsOperationLeases.acquire(runtime, "a")
        val admitted = QuarantinedGroupsOperationLeases.acquire(runtime, "a")
        val mutex = admitted.mutex
        lease.close()
        val next = QuarantinedGroupsOperationLeases.acquire(runtime, "a")
        assertSame(mutex, next.mutex)
        next.close()
        admitted.close()
        val fresh = QuarantinedGroupsOperationLeases.acquire(runtime, "a")
        assertNotSame(mutex, fresh.mutex)
        fresh.close()
    }

    @Test fun everyCurrentReasonAndPresentationFallbackHasCoarseGuidance() {
        assertEquals(5, AppGroupHydrationQuarantineReasonFfi.entries.size)
        assertEquals(
            setOf(
                QuarantinedGroupReason.StoredState,
                QuarantinedGroupReason.MissingState,
                QuarantinedGroupReason.MemberValidation,
                QuarantinedGroupReason.GroupRecord,
                QuarantinedGroupReason.PendingCommit,
            ),
            AppGroupHydrationQuarantineReasonFfi.entries.map(::quarantineReason).toSet(),
        )
        assertEquals(QuarantinedGroupReason.Unknown, quarantineReason(null))
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
