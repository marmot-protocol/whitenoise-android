package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

/** Account cleanup publication and retained ownership regressions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class ExternalSignerCleanupPublicationTest : ExternalSignerSignOutFixture() {
    /** A lost native reply after commit cannot let an older selection undo confirmed local deactivation. */
    @Test
    fun confirmedSignOutWithLostReplyRejectsAnOlderSuspendedActivation() =
        runBlocking {
            signOutFailure = RuntimeException("native reply unavailable")
            persistSignedOutBeforePartialFailure = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val state =
                appState(
                    profileReader = {
                        entered.complete(Unit)
                        release.await()
                        null
                    },
                )
            val activation =
                async {
                    state.setActiveAccount(
                        accountRef,
                        preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                    )
                }
            try {
                withTimeout(3_000) { entered.await() }
                assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
                assertNull(state.activeAccountRef)
                assertEquals(AppPhase.Onboarding, state.phase)

                release.complete(Unit)
                assertFalse("the retired activation must not restore the signed-out identity", activation.await())
                assertNull(state.activeAccountRef)
                assertEquals(AppPhase.Onboarding, state.phase)
            } finally {
                release.complete(Unit)
                activation.cancel()
            }
        }

    /** Selection retirement precedes suspended cleanup once local sign-out is admitted. */
    @Test
    fun confirmedSignOutWithLostReplyRejectsActivationDuringAccountRefresh() =
        runBlocking {
            signOutFailure = RuntimeException("native reply unavailable")
            persistSignedOutBeforePartialFailure = true
            val profileEntered = CompletableDeferred<Unit>()
            val profileRelease = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val state =
                appState(
                    profileReader = {
                        profileEntered.complete(Unit)
                        profileRelease.await()
                        null
                    },
                )
            val activation =
                async {
                    state.setActiveAccount(
                        accountRef,
                        preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                    )
                }
            val signOut = async(start = CoroutineStart.LAZY) { state.signOutActiveAccount() }
            try {
                withTimeout(3_000) { profileEntered.await() }
                beforeListAccounts = {
                    cleanupEntered.complete(Unit)
                    runBlocking { withTimeout(5_000) { cleanupRelease.await() } }
                }
                signOut.start()
                withTimeout(3_000) { cleanupEntered.await() }
                profileRelease.complete(Unit)
                assertFalse(
                    "cleanup must already fence the older selection",
                    withTimeout(3_000) { activation.await() },
                )
                assertFalse("the account refresh remains held", signOut.isCompleted)
                cleanupRelease.complete(Unit)
                assertEquals(
                    SignOutCompletion.RelayCleanupIncomplete,
                    withTimeout(3_000) { signOut.await() },
                )
                assertNull(state.activeAccountRef)
                assertEquals(AppPhase.Onboarding, state.phase)
            } finally {
                profileRelease.complete(Unit)
                cleanupRelease.complete(Unit)
                activation.cancel()
                signOut.cancel()
            }
        }

    /** A failed account refresh after native completion must not revive the removed active session. */
    @Test
    fun successfulSignOutRefreshFailureStillClearsTheActiveSession() =
        runBlocking {
            listAccountsFailure = RuntimeException("account refresh unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(1, listAccountsCalls.get())
            assertTrue(appState.accounts.single().signedOut)
            assertFalse(appState.accounts.single().running)
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    /** Completed destructive wipe removes the account-scoped composer geometry with the session. */
    @Test
    fun successfulExternalSignerWipeUsesTheNormalRemovalPath() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            val appState = appState()
            retainContactPictures(appState)
            retainComposerExpansion(appState)

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(appState.accounts.isEmpty())
            assertNull(appState.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertEquals(
                composerExpansion,
                appState.composerExpansionStateRetention.preferenceFor(otherAccountRef, groupId),
            )
            assertNull(appState.activeAccountRef)
            assertContactPicturesRemovedOnlyForThisAccount(appState)
            assertTrue(appState.phase is AppPhase.Onboarding)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcutId })
        }

    /** A failed pre-native credential write returns a visible incomplete result and permits a later retry. */
    @Test
    fun credentialRevocationFailureRetainsSessionAndReturnsAnIncompleteResult() =
        runBlocking {
            var failWrites = true
            val owner = CredentialFailureContext(context) { failWrites }
            val state = appState(owner)
            retainComposerExpansion(state)
            val phaseBefore = state.phase

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(0, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(state.accounts.single().signedOut)
            assertEquals(phaseBefore, state.phase)
            assertEquals(composerExpansion, state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertTrue(owner.failedWrites > 0)

            failWrites = false
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertEquals(1, signOutCalls.get())
            assertNull(state.activeAccountRef)
        }

    /** Production identity publication queued before logout makes the captured A request stale before effects. */
    @Test
    fun queuedAccountPublicationCannotTurnSignOutAIntoSignOutB() =
        runBlocking {
            val state = appState()
            retainComposerExpansion(state)
            val release = CompletableDeferred<Unit>()
            val hold = async(start = CoroutineStart.UNDISPATCHED) { state.withAccountLifetime { release.await() } }
            val replacement =
                async(start = CoroutineStart.UNDISPATCHED) {
                    state.accountSession.publishCreatedIdentity(otherAccountRef) {
                        state.switchMediaSessionForTest(otherAccountRef)
                    }
                }
            val logout = async(start = CoroutineStart.UNDISPATCHED) { state.signOutActiveAccount() }
            release.complete(Unit)
            hold.await()
            replacement.await()
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, logout.await())
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(0, nativeDisableCalls.get())
            assertEquals(0, signOutCalls.get())
            assertTrue(PushTokenStore.create(context).pendingDisables().isEmpty())
        }

    /** Same-ref publication is a new selection lifetime even without replacing the native object. */
    @Test
    fun queuedSameAccountReactivationRejectsOlderLogoutWithoutPushEffects() =
        runBlocking {
            val state = appState()
            val release = CompletableDeferred<Unit>()
            val hold = async(start = CoroutineStart.UNDISPATCHED) { state.withAccountLifetime { release.await() } }
            val logout = async(start = CoroutineStart.UNDISPATCHED) { state.signOutActiveAccount() }
            val replacement =
                async(start = CoroutineStart.UNDISPATCHED) {
                    state.accountSession.publishCreatedIdentity(accountRef) {
                        state.switchMediaSessionForTest(accountRef)
                    }
                }
            release.complete(Unit)
            hold.await()
            replacement.await()
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, logout.await())
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(0, nativeDisableCalls.get())
            assertEquals(0, signOutCalls.get())
        }

    /** A real queued publication cannot overtake native completion and old shared-data cleanup. */
    @Test
    fun publicationRequestedDuringNativeReplyWaitsForOwnedCleanup() =
        runBlocking {
            val state = appState()
            retainComposerExpansion(state)
            var replacement: kotlinx.coroutines.Deferred<Unit>? = null
            beforeSignOutReply = {
                replacement =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        state.accountSession.publishCreatedIdentity(otherAccountRef) {
                            assertNull(state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
                            state.switchMediaSessionForTest(otherAccountRef)
                        }
                    }
                assertFalse(checkNotNull(replacement).isCompleted)
                assertEquals(accountRef, state.activeAccountRef)
            }
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            checkNotNull(replacement).await()
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
        }

    /**
     * Push bookkeeping that fails once native push is confirmed off — the pending-disable clear that now precedes
     * the native logout — preserves the completed native sign-out and local cleanup.
     */
    @Test
    fun postNativePushCleanupFailurePreservesSignOutCompletion() =
        runBlocking {
            var failedPushReads = 0
            val original = context.getSharedPreferences("sign-out-push-corruption", Context.MODE_PRIVATE)
            val corruptAfterSignOut =
                object : SharedPreferences by original {
                    override fun getStringSet(
                        key: String?,
                        defValues: MutableSet<String>?,
                    ): MutableSet<String>? {
                        if (nativeDisableCalls.get() > 0) {
                            failedPushReads += 1
                            error("scripted encrypted push value corruption")
                        }
                        return original.getStringSet(key, defValues)
                    }
                }
            val state = appState(pushTokenStore = PushTokenStore(corruptAfterSignOut))

            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertTrue(failedPushReads > 0)
            assertEquals(1, signOutCalls.get())
            assertTrue(engineSignedOut)
            assertTrue(state.accounts.single().signedOut)
        }

    /** A completed native sign-out cannot be interrupted by a second credential write during platform cleanup. */
    @Test
    fun signOutPublishesCompletionWhenPostNativeCredentialWritesWouldFail() =
        runBlocking {
            val owner = CredentialFailureContext(context) { engineSignedOut }
            val state = appState(owner)
            beforeListAccounts = {
                assertTrue(state.accounts.single().signedOut)
                assertNull(PinnedConversationTokens.create(owner).issue(accountRef, "ab".repeat(16)))
            }
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(state.accounts.single().signedOut)
            assertNull(state.activeAccountRef)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(0, owner.failedWrites)
        }

    /** Wipe removes Android ownership and launcher presentation without another fallible credential flush. */
    @Test
    fun wipePublishesCompletionWhenPostNativeCredentialWritesWouldFail() =
        runBlocking {
            val owner = CredentialFailureContext(context) { engineWiped }
            val state = appState(owner)
            beforeListAccounts = {
                assertTrue(state.accounts.isEmpty())
                assertNull(PinnedConversationTokens.create(owner).issue(accountRef, "ab".repeat(16)))
            }
            assertEquals(wipeOutcome, state.signOutAndWipeActiveAccount())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(state.accounts.isEmpty())
            assertNull(state.activeAccountRef)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(0, owner.failedWrites)
        }

    /** Corrupt push values after a successful native wipe cannot restore the removed account's pin eligibility. */
    @Test
    fun completedWipePublishesRemovedOwnershipBeforePushCleanupCanThrow() =
        runBlocking {
            var failedPushReads = 0
            val original = context.getSharedPreferences("wipe-push-corruption", Context.MODE_PRIVATE)
            val corruptAfterWipe =
                object : SharedPreferences by original {
                    override fun getStringSet(
                        key: String?,
                        defValues: MutableSet<String>?,
                    ): MutableSet<String>? {
                        if (engineWiped) {
                            failedPushReads += 1
                            error("scripted encrypted push value corruption")
                        }
                        return original.getStringSet(key, defValues)
                    }
                }
            val state = appState(pushTokenStore = PushTokenStore(corruptAfterWipe))
            assertEquals(wipeOutcome, state.signOutAndWipeActiveAccount())
            assertTrue(failedPushReads > 0)
            assertTrue(engineWiped)
            assertTrue(state.accounts.isEmpty())
        }

    /** An unfinished destructive wipe restores the active session without dropping its geometry. */
    @Test
    fun unfinishedExternalSignerWipeRestoresTheActiveSession() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            wipeOutcome =
                wipeOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            retainComposerExpansion(appState)
            val phaseBefore = appState.phase

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(listOf(accountRef), appState.accounts.map { it.label })
            assertEquals(accountRef, appState.activeAccountRef)
            assertEquals(
                composerExpansion,
                appState.composerExpansionStateRetention.preferenceFor(accountRef, groupId),
            )
            assertEquals(phaseBefore, appState.phase)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId })
        }
}
