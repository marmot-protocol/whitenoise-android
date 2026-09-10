package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductEventModeFfi
import dev.ipf.marmotkit.ProductPropertyKindFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MarmotBridgeTracerTest {
    @Test
    fun disabledTracingRunsBlockWithoutEmittingSections() =
        runBlocking {
            val backend = RecordingTraceBackend(enabled = false)

            val result = MarmotBridgeTracer(backend).trace(MarmotTraceSection.INVITE_MEMBERS) { "result" }

            assertEquals("result", result)
            assertEquals(emptyList<String>(), backend.events)
        }

    @Test
    fun enabledTracingPairsAsyncSectionAroundSuccessfulCall() =
        runBlocking {
            val backend = RecordingTraceBackend(enabled = true)

            MarmotBridgeTracer(backend).trace(MarmotTraceSection.REFRESH_GROUP_ROSTER) {
                backend.events += "call"
            }

            assertEquals(
                listOf(
                    "begin:${MarmotTraceSection.REFRESH_GROUP_ROSTER}:1",
                    "call",
                    "end:${MarmotTraceSection.REFRESH_GROUP_ROSTER}:1",
                ),
                backend.events,
            )
        }

    @Test
    fun failedOrCancelledCallStillClosesSection() {
        val failure = IllegalStateException("failed")
        val failedBackend = RecordingTraceBackend(enabled = true)
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    MarmotBridgeTracer(failedBackend).trace(MarmotTraceSection.PROMOTE_ADMIN) { throw failure }
                }
            },
        )
        assertEquals(
            listOf(
                "begin:${MarmotTraceSection.PROMOTE_ADMIN}:1",
                "end:${MarmotTraceSection.PROMOTE_ADMIN}:1",
            ),
            failedBackend.events,
        )

        val cancelledBackend = RecordingTraceBackend(enabled = true)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                MarmotBridgeTracer(cancelledBackend).trace(MarmotTraceSection.ACCEPT_GROUP_INVITE) {
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(
            listOf(
                "begin:${MarmotTraceSection.ACCEPT_GROUP_INVITE}:1",
                "end:${MarmotTraceSection.ACCEPT_GROUP_INVITE}:1",
            ),
            cancelledBackend.events,
        )
    }

    @Test
    fun timingsPreserveOutcomes() =
        runBlocking {
            var now = 100L
            val tracer = MarmotBridgeTracer(RecordingTraceBackend(false), nowMs = { now })
            val events = mutableListOf<Triple<String, Long, HostPerformanceOutcomeFfi>>()
            val record: (String, Long, HostPerformanceOutcomeFfi) -> Unit = { name, duration, outcome ->
                events += Triple(name, duration, outcome)
            }
            assertEquals(
                "sent",
                tracer.trace(MarmotTraceSection.TEXT_SEND, record) {
                    now += 25L
                    "sent"
                },
            )
            for (error in listOf(IllegalStateException("private error"), CancellationException("cancelled"))) {
                try {
                    tracer.trace(MarmotTraceSection.TEXT_SEND, record) {
                        now += 50L
                        throw error
                    }
                    throw AssertionError("Operation must fail")
                } catch (actual: Exception) {
                    assertSame(error, actual)
                }
            }
            assertEquals(
                listOf(
                    Triple("app_text_send", 25L, HostPerformanceOutcomeFfi.SUCCESS),
                    Triple("app_text_send", 50L, HostPerformanceOutcomeFfi.FAILURE),
                    Triple("app_text_send", 50L, HostPerformanceOutcomeFfi.FAILURE),
                ),
                events,
            )
        }

    @Test
    fun unregisteredNamesStayLocal() =
        runBlocking {
            val tracer = MarmotBridgeTracer(RecordingTraceBackend(false), nowMs = { error("Must not measure") })
            val result = tracer.trace("private/user/input", { _, _, _ -> error("Must not export") }) { 42 }
            assertEquals(42, result)
        }

    @Test
    fun rejectionDisablesRecording() =
        runBlocking {
            val tracer = MarmotBridgeTracer(RecordingTraceBackend(false), nowMs = { 0L })
            var attempts = 0
            val record: (String, Long, HostPerformanceOutcomeFfi) -> Unit = { _, _, _ ->
                attempts += 1
                throw MarmotKitException.InvalidProductObservation()
            }
            val failure = IllegalStateException("original failure")
            try {
                tracer.trace(MarmotTraceSection.TEXT_SEND, record) { throw failure }
                throw AssertionError("Operation must fail")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(42, tracer.trace(MarmotTraceSection.TEXT_SEND, record) { 42 })
            assertEquals(1, attempts)
        }

    @Test
    fun registryMatchesNativeSchema() {
        val registry = MarmotTraceSection.hostTimingRegistry
        assertTrue(registry.size in 1..32)
        assertEquals(registry.size, registry.map { it.name }.toSet().size)
        assertEquals(MarmotTraceSection.hostTimingNames.values.toSet(), registry.map { it.name }.toSet())
        for (schema in registry) {
            assertTrue(schema.name.matches(Regex("app_[a-z_]{1,56}")))
            assertEquals(ProductEventModeFfi.AGGREGATE, schema.mode)
            assertEquals(listOf("elapsed", "outcome"), schema.properties.map { it.name })
            assertEquals(ProductPropertyKindFfi.DURATION_BUCKET, schema.properties[0].kind)
            assertEquals(emptyList<String>(), schema.properties[0].choices)
            assertEquals(ProductPropertyKindFfi.ENUM, schema.properties[1].kind)
            assertEquals(listOf("success", "failure"), schema.properties[1].choices)
        }
    }

    private class RecordingTraceBackend(
        private val enabled: Boolean,
    ) : AsyncTraceBackend {
        val events = mutableListOf<String>()

        override fun isEnabled(): Boolean = enabled

        override fun beginAsyncSection(
            sectionName: String,
            cookie: Int,
        ) {
            events += "begin:$sectionName:$cookie"
        }

        override fun endAsyncSection(
            sectionName: String,
            cookie: Int,
        ) {
            events += "end:$sectionName:$cookie"
        }
    }
}
