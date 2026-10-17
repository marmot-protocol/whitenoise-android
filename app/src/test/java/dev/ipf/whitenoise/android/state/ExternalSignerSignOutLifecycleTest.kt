package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
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

/**
 * Android-side coverage for #2132 after MarmotKit 0.9.15 made external-signer
 * accounts first-class sign-out participants. Android must follow MDK's
 * structured local-cleanup verdict instead of inferring support from signing
 * mode or clearing the active session after an unfinished engine teardown.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class ExternalSignerSignOutLifecycleTest : ExternalSignerSignOutFixture() {
    /** Completed non-destructive sign-out clears geometry only after engine-owned teardown completes. */
    @Test
    fun successfulExternalSignerSignOutUsesTheNormalCompletionPath() =
        runBlocking {
            val appState = appState()
            retainContactPictures(appState)
            retainComposerExpansion(appState)

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertNull(appState.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertEquals(
                composerExpansion,
                appState.composerExpansionStateRetention.preferenceFor(otherAccountRef, groupId),
            )
            assertNull(appState.activeAccountRef)
            assertContactPicturesRemovedOnlyForThisAccount(appState)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    /** A failed local alert write cannot leave an account active after native sign-out already completed. */
    @Test
    fun failedProfileAlertCleanupStillCompletesSignOutAndRetriesOnRefresh() =
        runBlocking {
            val state = appState(profileCleanupFailureContext(throws = false))
            seedProfileAlertChoices(state)
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertNull(state.activeAccountRef)
            assertTrue(state.accounts.single().signedOut)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(
                dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.DEFAULT,
                state.profileNotificationOverrides.get(accountRef, accountHex).mode,
            )
        }

    /** Even an exceptional preferences failure must not bypass post-wipe account refresh and safe routing. */
    @Test
    fun exceptionalProfileAlertCleanupStillCompletesWipeAndRefresh() =
        runBlocking {
            val state = appState(profileCleanupFailureContext(throws = true))
            seedProfileAlertChoices(state)
            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            assertEquals(1, wipeCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertNull(state.activeAccountRef)
            assertTrue(state.accounts.isEmpty())
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(
                dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.DEFAULT,
                state.profileNotificationOverrides.get(accountRef, accountHex).mode,
            )
        }

    /** An unfinished engine teardown retains both the active session and its composer geometry. */
    @Test
    fun unfinishedEngineTeardownKeepsTheExternalSignerSessionActive() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            retainComposerExpansion(appState)
            val phaseBefore = appState.phase

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(accountRef, appState.activeAccountRef)
            assertEquals(listOf(accountRef), appState.accounts.map { it.label })
            assertEquals(
                composerExpansion,
                appState.composerExpansionStateRetention.preferenceFor(accountRef, groupId),
            )
            assertEquals(phaseBefore, appState.phase)
        }

    /**
     * Verifies thrown native sign-out retains the established local fallback without changing the completion contract.
     */
    @Test
    fun transientEngineFailureRetainsTheExistingLocalSignOutFallback() =
        runBlocking {
            signOutFailure = RuntimeException("relay unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
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

    /** A late native success clears the old account's keyed state while preserving the replacement session. */
    @Test
    fun lateSignOutSuccessClearsOnlyTheCapturedAccount() =
        runBlocking {
            // This native reply hook simulates a Main-thread account switch before delivering its result.
            val state = appState(marmotIoDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            retainComposerExpansion(state)
            retainContactPictures(state)
            val originalPhase = state.phase
            beforeSignOutReply = {
                WhiteNoiseAppState::class.java
                    .getDeclaredField("marmotRuntime")
                    .apply { isAccessible = true }
                    .set(state, AppMarmotRuntime(rootPath = "replacement", marmot = marmot))
                state.switchMediaSessionForTest(otherAccountRef)
            }
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(originalPhase, state.phase)
            assertTrue(state.accounts.single().signedOut)
            assertNull(state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertEquals(
                composerExpansion,
                state.composerExpansionStateRetention.preferenceFor(otherAccountRef, groupId),
            )
            assertContactPicturesRemovedOnlyForThisAccount(state)
        }

    /** A replacement runtime with the same ref owns its keyed state until it completes its own sign-out. */
    @Test
    fun lateSuccessCannotEraseReplacementUsingTheSameAccountRef() =
        runBlocking {
            val state = appState()
            retainComposerExpansion(state)
            beforeSignOutReply = {
                WhiteNoiseAppState::class.java
                    .getDeclaredField("marmotRuntime")
                    .apply { isAccessible = true }
                    .set(state, AppMarmotRuntime(rootPath = "replacement", marmot = marmot))
            }
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(composerExpansion, state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertTrue(PushTokenStore.create(context).pendingDisables().contains(accountRef))
        }

    /** A replaced runtime's failed reply cannot report sign-out or tear down the still-active session. */
    @Test
    fun lateSignOutFailureKeepsAccountAndReportsIncompleteCleanup() =
        runBlocking {
            val state = appState()
            retainComposerExpansion(state)
            signOutFailure = java.io.IOException("scripted late failure")
            beforeSignOutReply = {
                WhiteNoiseAppState::class.java
                    .getDeclaredField("marmotRuntime")
                    .apply { isAccessible = true }
                    .set(state, AppMarmotRuntime(rootPath = "replacement", marmot = marmot))
            }
            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertFalse(state.accounts.single().signedOut)
            assertEquals(composerExpansion, state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
            assertTrue(PushTokenStore.create(context).pendingDisables().contains(accountRef))
        }

    /** Push bookkeeping failure preserves the completed native sign-out and local cleanup. */
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
                        if (engineSignedOut) {
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
