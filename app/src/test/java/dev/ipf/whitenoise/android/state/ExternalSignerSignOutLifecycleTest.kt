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
    fun callerCancellationKeepsNewerExplicitAccountAheadOfAutomaticSuccessor() =
        runBlocking {
            val explicitRef = "explicit-account"
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                    externalSignerAccount().copy(label = explicitRef, accountIdHex = "ee".repeat(32)),
                )
            val state = appState(profileReader = { null })
            var explicit: kotlinx.coroutines.Deferred<Boolean>? = null
            val logout = async(start = CoroutineStart.LAZY) { state.signOutActiveAccount() }
            beforeSignOutReply = {
                explicit = async(start = CoroutineStart.UNDISPATCHED) { state.setActiveAccount(explicitRef) }
                logout.cancel()
            }
            try {
                logout.start()
                logout.join()
                assertTrue(logout.isCancelled)
                assertTrue(checkNotNull(explicit).await())
                assertEquals(explicitRef, state.activeAccountRef)
                assertEquals(AppPhase.Ready, state.phase)
                assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
            } finally {
                logout.cancel()
                logout.join()
                explicit?.cancel()
                explicit?.join()
            }
        }

    @Test
    fun staleSameRuntimeSetupRouteCannotMountAfterNewerPublicActivation() =
        runBlocking {
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                )
            val state = appState(profileReader = { null })
            val epoch = state.runtimeGeneration
            val read = CompletableDeferred<kotlin.coroutines.Continuation<Any?>>()
            setupSnapshotReply = { continuation ->
                read.complete(continuation)
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
            val earlier = async { runCatching { state.setActiveAccount(accountRef) } }
            val continuation = withTimeout(5_000) { read.await() }
            setupSnapshotReply = null
            try {
                assertTrue(state.setActiveAccount(otherAccountRef))
            } finally {
                continuation.resumeWith(
                    Result.success(
                        dev.ipf.whitenoise.android.ui.onboarding.setup
                            .setupSnapshot()
                            .copy(accountIdHex = accountRef),
                    ),
                )
            }
            val result = withTimeout(5_000) { earlier.await() }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(epoch, state.runtimeGeneration)
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertNull(state.accountSetup.controller)
        }

    @Test
    fun nativeCancellationAfterSignedOutCommitsAutomaticSuccessorBeforePropagation() =
        runBlocking {
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                )
            signOutFailure = kotlinx.coroutines.CancellationException("native reply cancelled")
            persistSignedOutBeforePartialFailure = true
            val state = appState(profileReader = { null })
            val result = runCatching { state.signOutActiveAccount() }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
            assertEquals(0, nativePrivacyConfigCalls.get())
        }

    @Test
    fun explicitAccountQueuedDuringNativeSignOutWinsSuccessorAndBecomesReady() =
        runBlocking {
            val explicitRef = "explicit-account"
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                    externalSignerAccount().copy(label = explicitRef, accountIdHex = "ee".repeat(32)),
                )
            val state = appState(profileReader = { null })
            var explicit: kotlinx.coroutines.Deferred<Boolean>? = null
            beforeSignOutReply = {
                explicit = async(start = CoroutineStart.UNDISPATCHED) { state.setActiveAccount(explicitRef) }
            }
            try {
                assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
                assertTrue(checkNotNull(explicit).await())
                assertEquals(explicitRef, state.activeAccountRef)
                assertEquals(AppPhase.Ready, state.phase)
                assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
                assertEquals(listOf("disable", "signOut"), nativeCallOrder.take(2))
            } finally {
                explicit?.cancel()
                explicit?.join()
            }
        }

    @Test
    fun successfulSignOutActivatesRetainedSuccessorInReadyPhase() =
        runBlocking {
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                )
            val state = appState(profileReader = { null })
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertEquals(listOf("disable", "signOut"), nativeCallOrder.take(2))
            assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
        }

    @Test
    fun retainedCreationReceiptCannotQualifyOrActivateAfterPublicWipe() =
        runBlocking {
            val state = appState()
            createdIdentity = externalSignerAccount().copy(label = "created", accountIdHex = "cc".repeat(32))
            createdPolicyFailure = java.io.IOException("policy qualification unavailable")
            state.createIdentity()
            assertEquals(1, createdIdentityCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            createdPolicyFailure = null
            val retry = runCatching { state.createIdentity() }
            assertTrue(retry.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(1, createdIdentityCalls.get())
            assertNull(state.activeAccountRef)
        }

    @Test
    fun cancelledNativeSignOutRetainsAuthoritativeSignedOutCacheWhenRefreshFails() =
        runBlocking {
            val state = appState()
            signOutFailure = kotlinx.coroutines.CancellationException("native completion reply cancelled")
            persistSignedOutBeforePartialFailure = true
            beforeListAccounts = {
                if (listAccountsCalls.get() > 0) error("later refresh unavailable")
            }
            val result = runCatching { state.signOutActiveAccount() }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(state.accounts.single().signedOut)
            assertNull(state.activeAccountRef)
            assertFalse(nativePushEnabled)
        }

    @Test
    fun staleSetupSnapshotCannotMountAfterPublicWipe() =
        runBlocking {
            val state = appState()
            val read = CompletableDeferred<kotlin.coroutines.Continuation<Any?>>()
            setupSnapshotReply = { continuation ->
                read.complete(continuation)
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
            val routing = async { runCatching { state.accountSetup.routeIfPending(accountRef) } }
            val continuation = withTimeout(5_000) { read.await() }
            setupSnapshotReply = null
            try {
                assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            } finally {
                continuation.resumeWith(
                    Result.success(
                        dev.ipf.whitenoise.android.ui.onboarding.setup
                            .setupSnapshot(),
                    ),
                )
            }
            val result = withTimeout(5_000) { routing.await() }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertNull(state.accountSetup.controller)
        }

    /** Recovery binds its first read to the owner that initiated it, even across controller draining. */
    @Test
    fun staleSetupRecoveryReadCannotMutateAfterPublicWipe() =
        runBlocking {
            val state = appState()
            val read = CompletableDeferred<kotlin.coroutines.Continuation<Any?>>()
            setupRecoveryReply = { continuation ->
                read.complete(continuation)
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
            val recovery = async { runCatching { state.accountSetup.recover(accountRef) } }
            val continuation = withTimeout(5_000) { read.await() }
            setupRecoveryReply = null
            try {
                assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            } finally {
                continuation.resumeWith(Result.success(true))
            }
            val result = withTimeout(5_000) { recovery.await() }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(0, setupRecoverCalls.get())
            assertNull(state.accountSetup.controller)
        }

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
            val selectionBefore = appState.accountSession.handoff.capture()

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
            assertTrue(
                "unfinished cleanup must retain the selection",
                appState.accountSession.handoff.isCurrent(selectionBefore),
            )
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

    /** A failed native sign-out cannot let an older suspended selection undo completed local deactivation. */
    @Test
    fun localSignOutFallbackRejectsAnOlderSuspendedActivation() =
        runBlocking {
            signOutFailure = RuntimeException("relay unavailable")
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
    fun localSignOutFallbackRejectsActivationDuringAccountRefresh() =
        runBlocking {
            signOutFailure = RuntimeException("relay unavailable")
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
