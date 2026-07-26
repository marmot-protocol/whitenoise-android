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
            var nativePushDisableCalls = 0
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = {
                        nativePushEnableCalls += 1
                        true
                    },
                    disableNativePush = {
                        nativePushDisableCalls += 1
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        true
                    },
                )

            assertTrue(configured)
            assertEquals(1, nativePushEnableCalls)
            assertEquals(0, nativePushDisableCalls)
            assertEquals(listOf(false), backgroundUpdates)
        }

    @Test
    fun missingNativePushUsesPersistentConnection() =
        runTest {
            var nativePushEnableCalled = false
            var nativePushDisableCalled = false
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = false,
                    enableNativePush = {
                        nativePushEnableCalled = true
                        true
                    },
                    disableNativePush = {
                        nativePushDisableCalled = true
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        backgroundUpdates += it
                        true
                    },
                )

            assertTrue(configured)
            assertFalse(nativePushEnableCalled)
            assertFalse(nativePushDisableCalled)
            assertEquals(listOf(true), backgroundUpdates)
        }

    @Test
    fun failedNativePushEnableFallsBackToPersistentConnection() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = {
                        updates += "native:on"
                        false
                    },
                    disableNativePush = {
                        updates += "native:off"
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        updates += "background:$it"
                        true
                    },
                )

            assertTrue(configured)
            assertEquals(listOf("native:on", "native:off", "background:true"), updates)
        }

    @Test
    fun failedNativePushShutdownRestoresPersistentConnection() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = {
                        updates += "native:on"
                        true
                    },
                    disableNativePush = {
                        updates += "native:off"
                        true
                    },
                    setBackgroundConnectionEnabled = {
                        updates += "background:$it"
                        it
                    },
                )

            assertTrue(configured)
            assertEquals(
                listOf("native:on", "background:false", "native:off", "background:true"),
                updates,
            )
        }

    @Test
    fun failedNativePushRollbackReportsUnconfiguredButRestoresPersistentConnection() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushAvailable = true,
                    enableNativePush = {
                        updates += "native:on"
                        true
                    },
                    disableNativePush = {
                        updates += "native:off"
                        false
                    },
                    setBackgroundConnectionEnabled = {
                        updates += "background:$it"
                        it
                    },
                )

            assertFalse(configured)
            assertEquals(
                listOf("native:on", "background:false", "native:off", "background:true"),
                updates,
            )
        }
}
