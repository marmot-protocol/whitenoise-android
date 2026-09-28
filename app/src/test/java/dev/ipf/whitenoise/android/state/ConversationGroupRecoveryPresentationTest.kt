package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `group_recovery_status` is an advisory read. Its failure says nothing about whether a group needs
 * recovery, and for a group the account created seconds ago it is most likely a projection that has
 * not settled. These pin which failures the conversation is allowed to turn into a recovery card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationGroupRecoveryPresentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A group created and opened moments ago has no recovery story to fail at reading. */
    @Test
    fun aFreshlyCreatedGroupDoesNotShowTheCardWhenItsFirstReadThrows() {
        val appState = testAppState()
        val controller = controller(appState) { error("projection is still settling") }
        appState.freshGroupCreations.record(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration)

        runBlocking { controller.retryGroupRecoveryStatus() }

        assertFalse(controller.groupRecoveryReadFailed)
        assertNull(controller.groupRecoveryStatus)
    }

    /** An ordinary existing group keeps the failure surface it has always had. */
    @Test
    fun anExistingGroupStillShowsTheCardWhenItsReadThrows() {
        val appState = testAppState()
        val controller = controller(appState) { error("engine could not answer") }

        runBlocking { controller.retryGroupRecoveryStatus() }

        assertTrue(controller.groupRecoveryReadFailed)
    }

    /** A closed runtime worker says the advisory answer is unavailable, not that recovery is needed. */
    @Test
    fun anExistingGroupKeepsAnExhaustedTransientReadQuietWithoutEvidence() =
        runTest {
            val appState = testAppState()
            var attempts = 0
            val controller =
                controller(appState) {
                    attempts += 1
                    throw MarmotKitException.TransportClosed()
                }

            controller.retryGroupRecoveryStatus()

            assertEquals(GROUP_RECOVERY_READ_RETRY_ATTEMPTS, attempts)
            assertFalse(controller.groupRecoveryReadFailed)
            assertNull(controller.groupRecoveryStatus)
        }

    /** A short worker interruption is retried and publishes the eventual authoritative answer. */
    @Test
    fun aTransientReadRetriesThenPublishesSuccess() =
        runTest {
            val appState = testAppState()
            var attempts = 0
            val controller =
                controller(appState) {
                    attempts += 1
                    if (attempts < 2) throw MarmotKitException.TransportClosed()
                    recoveryStatus(pendingReinvites = 1u)
                }

            controller.retryGroupRecoveryStatus()

            assertEquals(2, attempts)
            assertFalse(controller.groupRecoveryReadFailed)
            assertEquals(1u, controller.groupRecoveryStatus?.pendingReinvites)
        }

    /** Confirmed evidence stays inspectable when a later refresh fails, with a retry beside it. */
    @Test
    fun aFailedRefreshKeepsAlreadyConfirmedRecoveryEvidence() {
        val appState = testAppState()
        var fail = false
        val controller =
            controller(appState) {
                if (fail) error("refresh failed") else recoveryStatus(automaticRecoveryFailed = true)
            }
        appState.freshGroupCreations.record(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration)

        runBlocking { controller.retryGroupRecoveryStatus() }
        fail = true
        runBlocking { controller.retryGroupRecoveryStatus() }

        assertTrue(controller.groupRecoveryReadFailed)
        assertEquals(true, controller.groupRecoveryStatus?.automaticRecoveryFailed)
    }

    /** A transient refresh failure keeps real recovery evidence and its bounded manual retry. */
    @Test
    fun aTransientRefreshFailureKeepsAlreadyConfirmedRecoveryEvidence() =
        runTest {
            val appState = testAppState()
            var fail = false
            val controller =
                controller(appState) {
                    if (fail) throw MarmotKitException.TransportClosed()
                    recoveryStatus(automaticRecoveryFailed = true)
                }

            controller.retryGroupRecoveryStatus()
            fail = true
            controller.retryGroupRecoveryStatus()

            assertTrue(controller.groupRecoveryReadFailed)
            assertEquals(true, controller.groupRecoveryStatus?.automaticRecoveryFailed)
        }

    /** Replacing the Marmot runtime while a read is in flight rejects its stale result. */
    @Test
    fun aReplacedRuntimeCannotPublishARecoveryRead() =
        runTest {
            val appState = testAppState()
            val controller =
                controller(appState) {
                    replaceRuntimeOwner(appState)
                    recoveryStatus(pendingReinvites = 1u)
                }

            controller.retryGroupRecoveryStatus()

            assertNull(controller.groupRecoveryStatus)
            assertFalse(controller.groupRecoveryReadFailed)
        }

    /** Disposing the controller while a read is in flight rejects its stale result. */
    @Test
    fun aDisposedControllerCannotPublishARecoveryRead() =
        runTest {
            val appState = testAppState()
            lateinit var controller: ConversationController
            controller =
                controller(appState) {
                    controller.onCleared()
                    recoveryStatus(pendingReinvites = 1u)
                }

            controller.retryGroupRecoveryStatus()

            assertNull(controller.groupRecoveryStatus)
            assertFalse(controller.groupRecoveryReadFailed)
        }

    /** A confirmed empty status is an answer, so nothing is shown and nothing is remembered as fresh. */
    @Test
    fun aConfirmedEmptyStatusRetiresTheFreshCreationContext() {
        val appState = testAppState()
        val controller = controller(appState) { recoveryStatus() }
        appState.freshGroupCreations.record(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration)

        runBlocking { controller.retryGroupRecoveryStatus() }

        assertFalse(controller.groupRecoveryReadFailed)
        assertFalse(appState.freshGroupCreations.isFresh(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration))
    }

    /** A genuine recovery state is never suppressed just because the group is new. */
    @Test
    fun aConfirmedRecoveryStateIsShownForAFreshlyCreatedGroup() {
        val appState = testAppState()
        val controller = controller(appState) { recoveryStatus(pendingReinvites = 1u) }
        appState.freshGroupCreations.record(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration)

        runBlocking { controller.retryGroupRecoveryStatus() }

        assertEquals(1u, controller.groupRecoveryStatus?.pendingReinvites)
    }

    /** After a suppressed first failure, a successful retry still publishes the engine's answer. */
    @Test
    fun retryAfterASuppressedFailureStillPublishesTheStatus() {
        val appState = testAppState()
        var fail = true
        val controller =
            controller(appState) {
                if (fail) error("still settling") else recoveryStatus(failedReinvites = 2u)
            }
        appState.freshGroupCreations.record(ACCOUNT_REF, groupIdHex(), appState.runtimeGeneration)

        runBlocking { controller.retryGroupRecoveryStatus() }
        fail = false
        runBlocking { controller.retryGroupRecoveryStatus() }

        assertFalse(controller.groupRecoveryReadFailed)
        assertEquals(2u, controller.groupRecoveryStatus?.failedReinvites)
    }

    /** Freshness belongs to one account, one group and one runtime, and only the newest creation. */
    @Test
    fun freshnessIsScopedByAccountGroupRuntimeAndSupersession() {
        val registry = FreshGroupCreationRegistry()
        registry.record(ACCOUNT_REF, GROUP_ID, 0)

        assertTrue(registry.isFresh(ACCOUNT_REF, GROUP_ID.uppercase(), 0))
        assertFalse(registry.isFresh("bob", GROUP_ID, 0))
        assertFalse(registry.isFresh(ACCOUNT_REF, OTHER_GROUP_ID, 0))
        assertFalse(registry.isFresh(ACCOUNT_REF, GROUP_ID, 1))
        assertFalse(registry.isFresh(null, GROUP_ID, 0))

        registry.record(ACCOUNT_REF, OTHER_GROUP_ID, 0)
        assertFalse("an older creation cannot stay fresh", registry.isFresh(ACCOUNT_REF, GROUP_ID, 0))

        registry.clear()
        assertFalse(registry.isFresh(ACCOUNT_REF, OTHER_GROUP_ID, 0))
    }

    /** The presentation rule itself, stated without a controller around it. */
    @Test
    fun onlyANeverAnsweredFreshGroupSuppressesItsFailure() {
        val terminal = IllegalStateException("terminal")
        assertFalse(groupRecoveryReadFailureIsPresentable(null, freshlyCreated = true, failure = terminal))
        assertTrue(groupRecoveryReadFailureIsPresentable(null, freshlyCreated = false, failure = terminal))
        assertTrue(groupRecoveryReadFailureIsPresentable(recoveryStatus(), freshlyCreated = true, failure = terminal))
        assertTrue(groupRecoveryReadFailureIsPresentable(recoveryStatus(), freshlyCreated = false, failure = terminal))
    }

    /** Applies the existing runtime-replacement publication fence without wiping test data. */
    private fun replaceRuntimeOwner(appState: WhiteNoiseAppState) {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = appState.activeAccountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = appState.runtimeGeneration + 1,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(appState, state)
    }

    /** The group id this suite's controller owns. */
    private fun groupIdHex() = conversationTimelineTestGroup().groupIdHex

    /** A recovery status with the requested evidence and this suite's group id. */
    private fun recoveryStatus(
        automaticRecoveryFailed: Boolean = false,
        pendingReinvites: UInt = 0u,
        failedReinvites: UInt = 0u,
    ) = GroupRecoveryStatusFfi(
        groupIdHex = groupIdHex(),
        automaticRecoveryFailed = automaticRecoveryFailed,
        pendingReinvites = pendingReinvites,
        failedReinvites = failedReinvites,
        rejoinInvitations = emptyList(),
    )

    /** A conversation controller whose only injected behaviour is the advisory recovery read. */
    private fun controller(
        appState: WhiteNoiseAppState,
        read: () -> GroupRecoveryStatusFfi,
    ) = ConversationController(
        appState = appState,
        initialGroup = conversationTimelineTestGroup(),
        groupRecoveryStatusReader = { _, _ -> read() },
    )

    /** One signed-in account, with no live subscriptions to start. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(RecoveryTestDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private object RecoveryTestDraftPersistence : DraftPersistence {
        /** No drafts participate in recovery presentation. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards writes; drafts are irrelevant to this suite. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "aa".repeat(32)
        val GROUP_ID = "bb".repeat(32)
        val OTHER_GROUP_ID = "cc".repeat(32)
    }
}
