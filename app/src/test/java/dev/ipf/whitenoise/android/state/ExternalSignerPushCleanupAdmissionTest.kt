package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Durable push cleanup admission must precede external-signer session teardown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class ExternalSignerPushCleanupAdmissionTest : ExternalSignerSignOutFixture() {
    /** Losing the runtime during a failed journal read cannot clear or disable the replacement session. */
    @Test
    fun replacementDuringJournalFailureKeepsItsSettingsAndSkipsNativeDisable() =
        runBlocking {
            lateinit var state: WhiteNoiseAppState
            val replacement = AppMarmotRuntime(rootPath = "replacement", marmot = marmot)
            val store =
                unavailablePushStore {
                    WhiteNoiseAppState::class.java
                        .getDeclaredField("marmotRuntime")
                        .apply { isAccessible = true }
                        .set(state, replacement)
                }
            state = appState(pushTokenStore = store)
            val settings = NotificationSettingsFfi(accountRef, accountHex, true, true)
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setLocalNotificationSettings", NotificationSettingsFfi::class.java)
                .apply { isAccessible = true }
                .invoke(state, settings)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(settings, state.localNotificationSettings)
            assertEquals(0, nativeDisableCalls.get())
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
        }

    /** A durable native disable admits sign-out when the encrypted cleanup journal cannot be written. */
    @Test
    fun nativeDisableFallbackPreservesAuthoritativeSignOutCompletion() =
        runBlocking {
            val state = appState(pushTokenStore = unavailablePushStore())
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertEquals(1, nativeDisableCalls.get())
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
            assertFalse(nativePushEnabled)
            assertEquals(1, signOutCalls.get())
            assertTrue(state.accounts.single().signedOut)
            assertNull(state.activeAccountRef)
        }

    /** The encrypted journal preserves the existing local fallback when no native runtime is available. */
    @Test
    fun committedJournalAllowsLocalFallbackWithoutNativeRuntime() =
        runBlocking {
            val state = appState()
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, null)
            assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(0, signOutCalls.get())
            assertNull(state.activeAccountRef)
            assertTrue(PushTokenStore.create(context).pendingDisables().contains(accountRef))
        }

    /** Total durable-storage failure retains the account, history owner, and native session. */
    @Test
    fun failedJournalAndNativeDisableDoNotAdmitSignOut() =
        runBlocking {
            nativeDisableFailure = java.io.IOException("scripted native failure")
            val state = appState(pushTokenStore = unavailablePushStore())
            retainComposerExpansion(state)
            val phase = state.phase
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(0, signOutCalls.get())
            assertEquals(1, nativeDisableCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(state.accounts.single().signedOut)
            assertEquals(phase, state.phase)
            assertEquals(composerExpansion, state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
        }

    /** An enabled response is not durable native cleanup authority. */
    @Test
    fun enabledNativeResponseCannotAdmitSignOut() =
        runBlocking {
            nativeDisableReturnsEnabled = true
            val state = appState(pushTokenStore = unavailablePushStore())
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
        }

    /** Cancellation of an unknown native disable must not fall through into local teardown. */
    @Test
    fun cancelledNativeDisableRetainsSessionAndPropagatesCancellation() =
        runBlocking {
            nativeDisableFailure = kotlinx.coroutines.CancellationException("scripted cancellation")
            val state = appState(pushTokenStore = unavailablePushStore())
            var cancelled = false
            try {
                state.signOutActiveAccount()
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertNull(state.localNotificationSettings)
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(state.accounts.single().signedOut)
        }

}
