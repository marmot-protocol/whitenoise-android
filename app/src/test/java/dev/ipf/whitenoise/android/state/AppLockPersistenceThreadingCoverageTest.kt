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

    @Test
    fun everySecureTimestampWriteUsesTheSingleOrderedIoBoundary() {
        val write = source.functionBody("persistAppLockTimestamp")
        assertEquals(1, Regex("AppLockPreferences.writeLastUnlockedAtMillis").findAll(source).count())
        assertTrue(source.contains("appLockPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO"))
        assertTrue(write.contains("mutationsScope.launch {"))
        assertTrue(write.indexOf("appLockTimestampWriteMutex.withLock") < write.indexOf("withContext(appLockPersistenceDispatcher)"))
        assertTrue(write.indexOf("withContext(appLockPersistenceDispatcher)") < write.indexOf("AppLockPreferences.writeLastUnlockedAtMillis"))
        assertFalse(write.contains("cancel("))
    }

    @Test
    fun unlockAndEligibleBackgroundCallbacksPublishTheCapturedBaselineBeforeEnqueueing() {
        for (name in listOf("markAppUnlockSucceeded", "recordAppLockBackgrounded")) {
            val body = source.functionBody(name)
            assertTrue(body.contains("nowMillis.coerceAtLeast(0L)"))
            assertTrue(body.indexOf("lastAppUnlockAtMillis = normalizedNow") < body.indexOf("persistAppLockTimestamp(normalizedNow)"))
        }
        assertTrue(source.functionBody("recordAppLockBackgrounded").contains("shouldRefreshAppLockDelayBaselineOnBackground("))
        assertTrue(source.functionBody("setAppInForeground").contains("recordAppLockBackgrounded()"))
    }
}
