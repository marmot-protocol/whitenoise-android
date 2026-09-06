package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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

    /** Every capability loss establishes persistent delivery before disabling an existing native path. */
    @Test
    fun capabilityLossMigratesExistingNativePushToPersistentDelivery() =
        runTest {
            NativePushCapability.entries.filterNot { it.isAvailable }.forEach { capability ->
                val updates = mutableListOf<String>()

                val reconciled =
                    reconcileUnavailableNativePushDelivery(
                        capability = capability,
                        nativePushEnabled = true,
                        ownerIsCurrent = { true },
                        enablePersistentConnection = {
                            updates += "background:on"
                            true
                        },
                        disableNativePush = {
                            updates += "native:off"
                            true
                        },
                    )

                assertTrue("$capability should reconcile", reconciled)
                assertEquals("$capability ordering", listOf("background:on", "native:off"), updates)
            }
        }

    /** Available native push and an intentional native-off state remain idempotent no-ops. */
    @Test
    fun reconciliationPreservesAvailableOrIntentionallyDisabledDelivery() =
        runTest {
            val calls = mutableListOf<String>()

            listOf(
                NativePushCapability.Available to true,
                NativePushCapability.MissingPushServerConfiguration to false,
            ).forEach { (capability, nativePushEnabled) ->
                assertTrue(
                    reconcileUnavailableNativePushDelivery(
                        capability = capability,
                        nativePushEnabled = nativePushEnabled,
                        ownerIsCurrent = { true },
                        enablePersistentConnection = {
                            calls += "background:on"
                            true
                        },
                        disableNativePush = {
                            calls += "native:off"
                            true
                        },
                    ),
                )
            }

            assertTrue(calls.isEmpty())
        }

    /** A failed fallback leaves native push enabled and therefore eligible for a later retry. */
    @Test
    fun failedPersistentFallbackDoesNotDisableNativePush() =
        runTest {
            var nativeDisableCalled = false

            val reconciled =
                reconcileUnavailableNativePushDelivery(
                    capability = NativePushCapability.FirebaseUnavailable,
                    nativePushEnabled = true,
                    ownerIsCurrent = { true },
                    enablePersistentConnection = { false },
                    disableNativePush = {
                        nativeDisableCalled = true
                        true
                    },
                )

            assertFalse(reconciled)
            assertFalse(nativeDisableCalled)
        }

    /** A failed native disable retains the already-established persistent fallback. */
    @Test
    fun failedNativeDisableKeepsPersistentFallbackActive() =
        runTest {
            val updates = mutableListOf<String>()

            val reconciled =
                reconcileUnavailableNativePushDelivery(
                    capability = NativePushCapability.GooglePlayServicesUnavailable,
                    nativePushEnabled = true,
                    ownerIsCurrent = { true },
                    enablePersistentConnection = {
                        updates += "background:on"
                        true
                    },
                    disableNativePush = {
                        updates += "native:off-failed"
                        false
                    },
                )

            assertFalse(reconciled)
            assertEquals(listOf("background:on", "native:off-failed"), updates)
        }

    /** Cancellation after fallback establishment is observed before the destructive native disable. */
    @Test
    fun cancellationBeforeNativeDisableLeavesRetryablePreference() =
        runTest {
            var nativeDisableCalled = false
            val reconciliation =
                launch {
                    reconcileUnavailableNativePushDelivery(
                        capability = NativePushCapability.MissingPushServerConfiguration,
                        nativePushEnabled = true,
                        ownerIsCurrent = { true },
                        enablePersistentConnection = {
                            currentCoroutineContext().cancel()
                            true
                        },
                        disableNativePush = {
                            nativeDisableCalled = true
                            true
                        },
                    )
                }

            reconciliation.join()

            assertTrue(reconciliation.isCancelled)
            assertFalse(nativeDisableCalled)
        }

    /** An account/runtime owner change after fallback establishment cannot disable that stale owner. */
    @Test
    fun ownerChangeBeforeNativeDisableRejectsStaleMutation() =
        runTest {
            var ownerIsCurrent = true
            var nativeDisableCalled = false

            val reconciled =
                reconcileUnavailableNativePushDelivery(
                    capability = NativePushCapability.MissingPushServerConfiguration,
                    nativePushEnabled = true,
                    ownerIsCurrent = { ownerIsCurrent },
                    enablePersistentConnection = {
                        ownerIsCurrent = false
                        true
                    },
                    disableNativePush = {
                        nativeDisableCalled = true
                        true
                    },
                )

            assertFalse(reconciled)
            assertFalse(nativeDisableCalled)
        }

    /** Background native-push preferences keep global readiness false until their account owns reconciliation. */
    @Test
    fun backgroundNativePushRequirementRemainsIncomplete() {
        val settings =
            mapOf(
                "account-a" to notificationSettings("account-a", nativePushEnabled = false),
                "account-b" to notificationSettings("account-b", nativePushEnabled = true),
            )

        val snapshot =
            NativePushFallbackSettingsSnapshot(
                active = settings.getValue("account-a"),
                knownByAccount = settings,
                allAccountsRead = true,
            )

        assertTrue(snapshot.hasUnreconciledAccountOutside("account-a"))
        assertFalse(snapshot.hasUnreconciledAccountOutside("account-b"))
    }

    /** Builds one local-notification setting for active/background readiness tests. */
    private fun notificationSettings(
        accountRef: String,
        nativePushEnabled: Boolean,
    ) = NotificationSettingsFfi(
        accountRef = accountRef,
        accountIdHex = "$accountRef-id",
        localNotificationsEnabled = true,
        nativePushEnabled = nativePushEnabled,
    )
}
