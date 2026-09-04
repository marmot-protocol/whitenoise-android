package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecoveryTraceTest {
    @Test
    fun everyCatchUpTriggerMapsToOneFixedPrivacySafeSection() {
        val expected =
            mapOf(
                PerformanceTrigger.FOREGROUND to "WhiteNoise.recovery.catchUp.foreground",
                PerformanceTrigger.NETWORK_RECONNECT to "WhiteNoise.recovery.catchUp.network-reconnect",
                PerformanceTrigger.PUSH_WAKE to "WhiteNoise.recovery.catchUp.push-wake",
                PerformanceTrigger.CHAT_LIST_READINESS to "WhiteNoise.recovery.catchUp.chat-list-readiness",
                PerformanceTrigger.EXPLICIT to "WhiteNoise.recovery.catchUp.explicit",
            )

        assertEquals(expected, PerformanceTrigger.entries.associateWith(RecoveryTraceSection::catchUp))
        assertTrue(expected.values.all { it.matches(Regex("WhiteNoise\\.recovery\\.[A-Za-z.-]+")) })
    }

    @Test
    fun nonCatchUpResourceSectionsAreFixed() {
        assertEquals(
            "WhiteNoise.recovery.network-attempt",
            RecoveryTraceSection.NETWORK_RECOVERY_ATTEMPT,
        )
        assertEquals(
            "WhiteNoise.recovery.push-wake-lock",
            RecoveryTraceSection.PUSH_WAKE_LOCK,
        )
    }

    @Test
    fun timeoutAndFinallyCannotCloseTheSameSliceTwice() {
        val token = RecoveryTraceToken("WhiteNoise.recovery.push-wake-lock", 1)

        assertTrue(token.claimEnd())
        assertFalse(token.claimEnd())
    }

    @Test
    fun unavailablePlatformTracingNeverBlocksRecoveryWork() =
        runBlocking {
            var ran = false

            val result =
                RecoveryTrace.catchUp(PerformanceTrigger.EXPLICIT) {
                    ran = true
                    "completed"
                }

            assertTrue(ran)
            assertEquals("completed", result)
        }

    @Test
    fun everyCatchUpEntryPointSuppliesASourceConfirmedTrigger() {
        val appState = source("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt")

        listOf(
            "PerformanceTrigger.EXPLICIT",
            "PerformanceTrigger.CHAT_LIST_READINESS",
            "PerformanceTrigger.FOREGROUND",
            "PerformanceTrigger.NETWORK_RECONNECT",
            "PerformanceTrigger.PUSH_WAKE",
        ).forEach { trigger -> assertTrue("Missing $trigger", trigger in appState) }
        assertTrue(
            Regex(
                """private fun launchAccountCatchUp\(\s*mustStartAfter: Long\?,\s*trigger: PerformanceTrigger,\s*\)""",
            ).containsMatchIn(appState),
        )
        assertTrue("actual native work owns the catch-up slice", "RecoveryTrace.catchUp(trigger)" in appState)
    }

    @Test
    fun reconnectAttemptsAndAcquiredPushWakeLocksOwnFixedSlices() {
        val recovery = source("src/main/java/dev/ipf/whitenoise/android/state/NotificationNetworkRecovery.kt")
        val service =
            source(
                "src/main/java/dev/ipf/whitenoise/android/notifications/NotificationStreamForegroundService.kt",
            )

        assertTrue("RecoveryTrace.networkRecoveryAttempt" in recovery)
        assertTrue("trigger = PerformanceTrigger.NETWORK_RECONNECT" in recovery)
        assertTrue("RecoveryTrace.beginPushWakeLock()" in service)
        assertTrue("RecoveryTrace.endPushWakeLock" in service)
    }

    private fun source(relativePath: String): String =
        sequenceOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing $relativePath")
}
