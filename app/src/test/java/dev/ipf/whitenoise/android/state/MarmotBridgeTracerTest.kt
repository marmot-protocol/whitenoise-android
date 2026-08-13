package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

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
