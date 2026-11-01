package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Durable push cleanup admission must precede external-signer session teardown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class ExternalSignerPushCleanupAdmissionTest : ExternalSignerSignOutFixture() {
    /**
     * Parent cancellation after journal commitment waits for admitted cleanup
     * before a queued successor can publish.
     */
    @Test
    fun parentCancellationAfterCommittedIntentFinishesOwnedCleanupBeforeSuccessor() {
        assertCancelledSignOutBookkeeping(queueExplicit = true)
    }

    /** Caller cancellation still commits an available automatic successor before cancellation escapes. */
    @Test
    fun parentCancellationAfterCommittedIntentActivatesAutomaticSuccessor() {
        assertCancelledSignOutBookkeeping(queueExplicit = false)
    }

    private fun assertCancelledSignOutBookkeeping(queueExplicit: Boolean) =
        kotlinx.coroutines.runBlocking {
            val backing =
                context.getSharedPreferences("cancelled-admitted-signout", android.content.Context.MODE_PRIVATE)
            backing.edit().clear().commit()
            val seed = PushTokenStore(backing)
            assertTrue(seed.recordPendingDisable(otherAccountRef))
            val committed = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val intercepted =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val store = blockingDisableStore(backing, committed, release, intercepted)
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                )
            val state = appState(pushTokenStore = store, profileReader = { null })
            val logout = async(Dispatchers.Main.immediate) { state.signOutActiveAccount() }
            var successor: kotlinx.coroutines.Deferred<Unit>? = null
            val successorEntered =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            try {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (committed.count > 0) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                        kotlinx.coroutines.delay(1)
                    }
                }
                logout.cancel()
                if (queueExplicit) {
                    successor =
                        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            state.accountSession.publishCreatedIdentity("replacement") { successorEntered.set(true) }
                        }
                }
                assertFalse(successorEntered.get())
                release.countDown()
                awaitSignOutJobs(logout, successor)
                logout.join()
                successor?.await()
                assertTrue(logout.isCancelled)
                assertEquals(queueExplicit, successorEntered.get())
                if (!queueExplicit) {
                    assertAutomaticSuccessorReady(state)
                }
                assertEquals(listOf("disable", "signOut"), nativeCallOrder)
                assertFalse(nativePushEnabled)
                assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
                assertTrue(store.pendingDisables().contains(otherAccountRef))
            } finally {
                release.countDown()
                logout.cancel()
                successor?.cancel()
                awaitSignOutJobs(logout, successor)
                logout.join()
                successor?.join()
            }
        }

    private fun assertAutomaticSuccessorReady(state: WhiteNoiseAppState) {
        assertEquals(otherAccountRef, state.activeAccountRef)
        assertEquals(AppPhase.Ready, state.phase)
        assertEquals(0, nativePrivacyConfigCalls.get())
    }

    private suspend fun awaitSignOutJobs(
        logout: kotlinx.coroutines.Job,
        successor: kotlinx.coroutines.Job?,
    ) {
        kotlinx.coroutines.withTimeout(5_000) {
            while (!logout.isCompleted || successor?.isCompleted == false) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                kotlinx.coroutines.delay(1)
            }
        }
    }

    private fun blockingDisableStore(
        backing: android.content.SharedPreferences,
        committed: java.util.concurrent.CountDownLatch,
        release: java.util.concurrent.CountDownLatch,
        intercepted: java.util.concurrent.atomic.AtomicBoolean,
    ): PushTokenStore =
        PushTokenStore(
            object : android.content.SharedPreferences by backing {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = backing.edit()
                    var admitsA = false
                    return object : android.content.SharedPreferences.Editor by editor {
                        override fun putStringSet(
                            key: String?,
                            values: MutableSet<String>?,
                        ): android.content.SharedPreferences.Editor =
                            apply {
                                admitsA =
                                    key == dev.ipf.whitenoise.android.notifications.KEY_PENDING_DISABLES &&
                                    values?.contains(accountRef) == true
                                editor.putStringSet(key, values)
                            }

                        override fun commit(): Boolean {
                            val result = editor.commit()
                            if (admitsA && intercepted.compareAndSet(false, true)) {
                                committed.countDown()
                                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            return result
                        }
                    }
                }
            },
        )

    /** A confirmed still-signed-in native partial outcome retains Android ownership without enabling push. */
    @Test
    fun partialCleanupWithConfirmedSignedInRetainsOwnerAndUnrelatedMarker() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "teardown failed"),
                )
            val store = PushTokenStore.create(context)
            assertTrue(store.recordPendingDisable(otherAccountRef))
            val state = appState(pushTokenStore = store)
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(state.accounts.single().signedOut)
            assertFalse(nativePushEnabled)
            assertTrue(store.pendingDisables().contains(otherAccountRef))
        }

    /** A successful Android journal never substitutes for native false authority. */
    @Test
    fun committedJournalWithFailedNativeDisableRetainsOwnerAndSkipsSignOut() =
        runBlocking {
            nativeDisableFailure = java.io.IOException("scripted native failure")
            val state = appState()
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(1, nativeDisableCalls.get())
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertTrue(PushTokenStore.create(context).pendingDisables().contains(accountRef))
        }

    /** The mocked signOut does not mutate push, so only the preceding setter can establish this postcondition. */
    @Test
    fun committedJournalStillRequiresNativeFalseBeforeSignOut() =
        runBlocking {
            val state = appState()
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
            assertFalse(nativePushEnabled)
            assertFalse(PushTokenStore.create(context).pendingDisables().contains(accountRef))
        }

    /** Already-disabled delivery stays disabled; cleanup never compensates by enabling it. */
    @Test
    fun alreadyDisabledNativeDeliveryIsNeverEnabledByLogout() =
        runBlocking {
            nativePushEnabled = false
            val state = appState()
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertFalse(nativePushEnabled)
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
        }

    /** Native cleanup can report failure after its signed-out bit committed; Android follows authoritative readback. */
    @Test
    fun partialCleanupAfterNativeSignedOutStillDeactivatesAndroidWithoutClaimingComplete() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "report cleanup"),
                )
            persistSignedOutBeforePartialFailure = true
            val state = appState()
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertNull(state.activeAccountRef)
            assertTrue(state.accounts.single().signedOut)
            assertFalse(nativePushEnabled)
        }

    /** An unconfirmed native partial result cannot be described as a live or completed native session. */
    @Test
    fun partialCleanupWithUnreadableNativeStateRetainsUnknownOwner() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "report cleanup"),
                )
            listAccountsFailure = java.io.IOException("readback unavailable")
            val state = appState()
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(nativePushEnabled)
        }

    /** A confirmed native transaction admits logout despite failed Android journal acknowledgement. */
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

    /** Durable push-disable intent does not prove native sign-out when the runtime is unavailable. */
    @Test
    fun committedJournalRetainsSessionWithoutNativeSignOutAuthority() =
        runBlocking {
            val state = appState()
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, null)
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
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
