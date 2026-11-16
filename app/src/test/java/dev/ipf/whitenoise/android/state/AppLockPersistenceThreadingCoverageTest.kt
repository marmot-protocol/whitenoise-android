package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPersistenceThreadingCoverageTest {
    private val appState = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt")
    private val controller = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppLockController.kt")

    /** Protect every timestamp caller from bypassing ordered off-main persistence. */
    @Test
    fun everySecureTimestampWriteUsesTheSingleOrderedIoBoundary() {
        val write = controller.functionBody("persistTimestamp")
        val writes = Regex("AppLockPreferences.writeLastUnlockedAtMillis")
        assertEquals(0, writes.findAll(appState).count())
        assertEquals(1, writes.findAll(controller).count())
        assertTrue(appState.contains("appLockPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO"))
        assertTrue(appState.contains("persistenceDispatcher = appLockPersistenceDispatcher"))
        assertTrue(write.contains("scope.launch {"))
        val acquire = write.indexOf("timestampWriteMutex.withLock")
        val dispatch = write.indexOf("withContext(persistenceDispatcher)")
        val persist = write.indexOf("AppLockPreferences.writeLastUnlockedAtMillis")
        assertTrue(acquire >= 0 && dispatch > acquire && persist > dispatch)
        assertFalse(write.contains("cancel("))
    }

    /** Keep authentication decisions immediate while preserving the background eligibility gate. */
    @Test
    fun unlockAndEligibleBackgroundCallbacksPublishTheCapturedBaselineBeforeEnqueueing() {
        for (name in listOf("markUnlockSucceeded", "onAppBackgrounded")) {
            val body = controller.functionBody(name)
            assertTrue(body.contains("nowMillis.coerceAtLeast(0L)"))
            val liveUpdate = body.indexOf("lastUnlockAtMillis = normalizedNow")
            val enqueue = body.indexOf("persistTimestamp(normalizedNow)")
            assertTrue(liveUpdate >= 0 && enqueue > liveUpdate)
        }
        val background = controller.functionBody("onAppBackgrounded")
        assertTrue(background.contains("shouldRefreshAppLockDelayBaselineOnBackground("))
        assertTrue(appState.functionBody("setAppInForeground").contains("appLock.onAppBackgrounded()"))
    }
}
