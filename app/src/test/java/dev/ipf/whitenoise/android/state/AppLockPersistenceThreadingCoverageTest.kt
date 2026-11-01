package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLockPersistenceThreadingCoverageTest {
    private val source =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).first(File::isFile).readText()

    /** Protect every timestamp caller from bypassing ordered off-main persistence. */
    @Test
    fun everySecureTimestampWriteUsesTheSingleOrderedIoBoundary() {
        val write = source.functionBody("persistAppLockTimestamp")
        assertEquals(1, Regex("AppLockPreferences.writeLastUnlockedAtMillis").findAll(source).count())
        assertTrue(source.contains("appLockPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO"))
        assertTrue(write.contains("mutationsScope.launch {"))
        val acquire = write.indexOf("appLockTimestampWriteMutex.withLock")
        val dispatch = write.indexOf("withContext(appLockPersistenceDispatcher)")
        val persist = write.indexOf("AppLockPreferences.writeLastUnlockedAtMillis")
        assertTrue(acquire >= 0 && dispatch > acquire && persist > dispatch)
        assertFalse(write.contains("cancel("))
    }

    /** Keep authentication decisions immediate while preserving the background eligibility gate. */
    @Test
    fun unlockAndEligibleBackgroundCallbacksPublishTheCapturedBaselineBeforeEnqueueing() {
        for (name in listOf("markAppUnlockSucceeded", "recordAppLockBackgrounded")) {
            val body = source.functionBody(name)
            assertTrue(body.contains("nowMillis.coerceAtLeast(0L)"))
            val liveUpdate = body.indexOf("lastAppUnlockAtMillis = normalizedNow")
            val enqueue = body.indexOf("persistAppLockTimestamp(normalizedNow)")
            assertTrue(liveUpdate >= 0 && enqueue > liveUpdate)
        }
        val background = source.functionBody("recordAppLockBackgrounded")
        assertTrue(background.contains("shouldRefreshAppLockDelayBaselineOnBackground("))
        assertTrue(source.functionBody("setAppInForeground").contains("recordAppLockBackgrounded()"))
    }
}
