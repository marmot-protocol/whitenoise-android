package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NativePushCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNotificationDeliveryTest {
    /** Requires both global registration success and an active-account fingerprint. */
    @Test
    fun nativePushEnablementRequiresAllAccountsAndActiveRegistration() {
        assertTrue(
            nativePushEnablementConfirmed(
                allAccountsReady = true,
                activeAccountRegistered = true,
            ),
        )
        assertFalse(
            nativePushEnablementConfirmed(
                allAccountsReady = false,
                activeAccountRegistered = true,
            ),
        )
        assertFalse(
            nativePushEnablementConfirmed(
                allAccountsReady = true,
                activeAccountRegistered = false,
            ),
        )
    }

    /** Confirms a usable native-push path replaces the persistent connection. */
    @Test
    fun nativePushDisablesPersistentConnectionWhenAvailable() =
        runTest {
            var nativePushEnableCalls = 0
            var nativePushDisableCalls = 0
            val backgroundUpdates = mutableListOf<Boolean>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushCapability = NativePushCapability.Available,
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

    /** Every unavailable capability selects persistent delivery without native enablement. */
    @Test
    fun missingNativePushUsesPersistentConnection() =
        runTest {
            NativePushCapability.entries.filterNot { it.isAvailable }.forEach { capability ->
                var nativePushEnableCalled = false
                var nativePushDisableCalled = false
                val backgroundUpdates = mutableListOf<Boolean>()

                val configured =
                    configureDefaultNotificationDelivery(
                        nativePushCapability = capability,
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

                assertTrue("$capability should configure persistent delivery", configured)
                assertFalse("$capability must not enable native push", nativePushEnableCalled)
                assertFalse("$capability must not disable native push", nativePushDisableCalled)
                assertEquals("$capability fallback", listOf(true), backgroundUpdates)
            }
        }

    /** Rolls back a failed native-push enable before restoring persistent delivery. */
    @Test
    fun failedNativePushEnableFallsBackToPersistentConnection() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushCapability = NativePushCapability.Available,
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

    /** Rolls native push back when the persistent connection cannot be disabled. */
    @Test
    fun failedPersistentConnectionShutdownRollsBackNativePush() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushCapability = NativePushCapability.Available,
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

    /** Reports incomplete configuration when rollback fails despite restoring delivery. */
    @Test
    fun failedNativePushRollbackReportsUnconfiguredButRestoresPersistentConnection() =
        runTest {
            val updates = mutableListOf<String>()

            val configured =
                configureDefaultNotificationDelivery(
                    nativePushCapability = NativePushCapability.Available,
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
