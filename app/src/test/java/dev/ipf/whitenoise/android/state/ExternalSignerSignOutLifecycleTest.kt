package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalCleanupReportFfi
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
}
