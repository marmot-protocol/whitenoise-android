package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNotificationDeliveryTest {
    @Test
    fun nativePushDisablesPersistentConnectionWhenAvailable() =
        runTest {
            var nativePushEnableCalls = 0
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = {
                        nativePushEnableCalls += 1
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        true
                    },
                )

            assertTrue(configured)
            assertEquals(1, nativePushEnableCalls)
            assertEquals(listOf(false), backgroundUpdates)
        }

    @Test
    fun missingNativePushUsesPersistentConnection() =
        runTest {
            var nativePushEnableCalled = false
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = false,
                    enableNativePush = {
                        nativePushEnableCalled = true
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        true
                    },
                )

            assertTrue(configured)
            assertFalse(nativePushEnableCalled)
            assertEquals(listOf(true), backgroundUpdates)
        }

    @Test
    fun failedNativePushEnableFallsBackToPersistentConnection() =
        runTest {
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = { false },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        true
                    },
                )

            assertTrue(configured)
            assertEquals(listOf(true), backgroundUpdates)
        }

    @Test
    fun failedNativePushShutdownRestoresPersistentConnection() =
        runTest {
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = { true },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        it
                    },
                )

            assertTrue(configured)
            assertEquals(listOf(false, true), backgroundUpdates)
        }
}
