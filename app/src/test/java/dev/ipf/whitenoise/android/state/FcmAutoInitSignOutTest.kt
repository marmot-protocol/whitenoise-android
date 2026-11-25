package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signing out or wiping the last signed-in account turns Firebase auto-init off (#4081), even though the
 * native account list keeps a signed-out entry after a sign-out, and a selection in flight cannot undo it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class FcmAutoInitSignOutTest : ExternalSignerSignOutFixture() {
    /** The only account signs out while the refreshed list still holds its signed-out entry. */
    @Test
    fun signingOutTheLastAccountTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)

            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())

            assertTrue("the signed-out entry must remain listed", engineSignedOut)
            assertEquals(false, platform.autoInitWrites.lastOrNull())
            assertTrue("sign-out must never enable auto-init", true !in platform.autoInitWrites)
        }

    /**
     * A Push selection still in flight when the last signer is wiped cannot turn auto-init back on, read a
     * token or keep one. The owner is captured before the wipe, as the selection captured it.
     */
    @Test
    fun selectionResumedAfterLastSignerWipeLeavesAutoInitOffWithoutToken() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val tokenReads = AtomicInteger()
            val store = PushTokenStore.create(context)
            val state =
                appState(
                    pushTokenStore = store,
                    nativePushFallbackPlatform = platform,
                    pushServerConfigProvider = { PushServerConfig("0".repeat(64), null) },
                    fcmTokenReader = {
                        tokenReads.incrementAndGet()
                        "late-token"
                    },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            val delivery = state.notificationDelivery
            val selectionOwner =
                checkNotNull(
                    delivery.captureNotificationDeliveryModeOwner(delivery.notificationDeliveryModeIntent.capture()),
                ) { "the signed-in signer must own delivery before the wipe" }

            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)
            assertEquals(false, platform.autoInitWrites.lastOrNull())
            platform.autoInitWrites.clear()
            tokenReads.set(0)
            // Positive control: push storage is ready, so only the ownership fence can stop the token read.
            assertTrue(store.registrationAllowed())

            assertFalse(delivery.selectFcmDelivery(selectionOwner))

            val writes = platform.autoInitWrites.toList()
            assertTrue("a wiped owner must not turn auto-init on: $writes", true !in writes)
            assertEquals(0, tokenReads.get())
            assertNull(store.lastToken())
        }

    /**
     * A last-signer wipe that lands while the selection waits on Firebase completes without waiting for it,
     * ends with auto-init off, and the selection keeps none of the token it then reads.
     */
    @Test
    fun wipeDuringSelectionTokenReadLeavesAutoInitOffWithoutToken() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val store = PushTokenStore.create(context)
            lateinit var state: WhiteNoiseAppState
            val wiped = AtomicBoolean(false)
            state =
                appState(
                    pushTokenStore = store,
                    nativePushFallbackPlatform = platform,
                    pushServerConfigProvider = { PushServerConfig("0".repeat(64), null) },
                    fcmTokenReader = {
                        // The wipe must not need the lock the selection holds around its owner checks.
                        val completion = withTimeout(WIPE_TIMEOUT_MILLIS) { state.signOutAndWipeActiveAccount() }
                        wiped.set(checkNotNull(completion).native.localCleanup.completed)
                        "token-read-across-wipe"
                    },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            val delivery = state.notificationDelivery
            val selectionOwner =
                checkNotNull(
                    delivery.captureNotificationDeliveryModeOwner(delivery.notificationDeliveryModeIntent.capture()),
                )
            assertTrue(store.registrationAllowed())

            assertFalse(delivery.selectFcmDelivery(selectionOwner))

            assertTrue("the wipe must complete during the token read", wiped.get())
            assertEquals(false, platform.autoInitWrites.lastOrNull())
            assertNull(store.lastToken())
        }

    /**
     * A sign-out that commits the native-push disable but then fails keeps the account signed in and off Push,
     * so auto-init must already be off when it returns incomplete.
     */
    @Test
    fun incompleteSignOutAfterNativeDisableTurnsAutoInitOff() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "report cleanup"),
                )
            listAccountsFailure = java.io.IOException("readback unavailable")
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())

            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(nativePushEnabled)
            assertEquals(false, platform.autoInitWrites.lastOrNull())
            assertTrue(true !in platform.autoInitWrites)
        }

    /** Signing out while another signed-in account still uses Push never turns auto-init off. */
    @Test
    fun signOutWithAnotherPushAccountKeepsAutoInit() =
        runBlocking {
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            additionalNativePush[otherAccountRef] = true
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(profileReader = { null }, nativePushFallbackPlatform = platform)

            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())

            assertEquals(otherAccountRef, state.activeAccountRef)
            val writes = platform.autoInitWrites.toList()
            assertFalse("a remaining Push account must keep auto-init: $writes", false in writes)
        }

    /** A wipe cancelled right after its native commit still turns auto-init off once no signer is left. */
    @Test
    fun wipeCancelledAfterItsNativeCommitTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)
            lateinit var wipe: Job
            beforeWipeReply = { wipe.cancel() }

            wipe = launch { state.signOutAndWipeActiveAccount() }
            wipe.join()

            assertTrue("the native wipe must commit before the cancellation", engineWiped)
            assertTrue("the wipe must end cancelled", wipe.isCancelled)
            assertEquals(false, platform.autoInitWrites.lastOrNull())
        }

    /** Wiping the only Push account turns auto-init off even while a signer on local delivery remains. */
    @Test
    fun wipingTheOnlyPushAccountBesideALocalSignerTurnsAutoInitOff() =
        runBlocking {
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            additionalNativePush[otherAccountRef] = false
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(profileReader = { null }, nativePushFallbackPlatform = platform)

            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)

            assertTrue("a signer must survive the wipe", state.accounts.any { it.label == otherAccountRef })
            assertEquals(false, platform.autoInitWrites.lastOrNull())
        }

    /** Wiping one Push account while another signer still uses Push never turns auto-init off. */
    @Test
    fun wipeWithAnotherPushAccountKeepsAutoInit() =
        runBlocking {
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            additionalNativePush[otherAccountRef] = true
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(profileReader = { null }, nativePushFallbackPlatform = platform)

            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)

            val writes = platform.autoInitWrites.toList()
            assertFalse("a remaining Push account must keep auto-init: $writes", false in writes)
        }

    /** A wipe whose account list cannot be read afterwards leaves a durable registration sync to settle auto-init. */
    @Test
    fun wipeWithUnreadableAccountsAfterwardsRecordsPendingSync() =
        runBlocking {
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            val store = PushTokenStore.create(context)
            val platform = RecordingNativePushFallbackPlatform(context)
            val state =
                appState(pushTokenStore = store, profileReader = { null }, nativePushFallbackPlatform = platform)
            store.clearPendingNativePushRegistrationSync()
            beforeWipeReply = { listAccountsFailure = java.io.IOException("account list unavailable") }

            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)

            assertTrue("the next sync must settle auto-init", store.nativePushRegistrationSyncPending())
            assertTrue("an unread demand never turns auto-init on", true !in platform.autoInitWrites)
        }

    /** A remaining signed-in signer keeps auto-init and the token for the next sync to decide. */
    @Test
    fun remainingSignedInSignerLeavesAutoInit() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)
            val remaining =
                listOf(
                    externalSignerAccount(signedOut = true),
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "b".repeat(64)),
                )

            state.notificationDelivery.settleFcmAfterSignOut(nextActiveRef = null, remaining = remaining)

            assertTrue(platform.autoInitWrites.isEmpty())
        }

    private companion object {
        const val WIPE_TIMEOUT_MILLIS = 10_000L
    }
}
