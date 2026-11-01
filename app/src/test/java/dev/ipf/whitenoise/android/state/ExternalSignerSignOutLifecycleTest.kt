package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalCleanupReportFfi
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

    /** A setup route held in its checkpoint read stands down, unmounted, after a newer public activation. */
    @Test
    fun staleSameRuntimeSetupRouteCannotMountAfterNewerPublicActivation() =
        runBlocking {
            additionalAccounts =
                listOf(
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)),
                )
            val state = appState(profileReader = { null })
            val epoch = state.runtimeGeneration
            val read = HeldNativeCall<dev.ipf.marmotkit.OnboardingSnapshotFfi?>()
            setupSnapshotReply = read::hold
            val earlier = async { runCatching { state.setActiveAccount(accountRef) } }
            read.awaitEntered()
            setupSnapshotReply = null
            try {
                assertTrue(state.setActiveAccount(otherAccountRef))
            } finally {
                read.release(
                    dev.ipf.whitenoise.android.ui.onboarding.setup
                        .setupSnapshot()
                        .copy(accountIdHex = accountRef),
                )
            }
            val result = withTimeout(5_000) { earlier.await() }
            // Like any retired switch, the older selection stands down instead of failing its caller.
            assertEquals(false, result.getOrThrow())
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

    /** A setup checkpoint read held across a public wipe cannot mount a controller afterwards. */
    @Test
    fun staleSetupSnapshotCannotMountAfterPublicWipe() =
        runBlocking {
            val state = appState()
            val read = HeldNativeCall<dev.ipf.marmotkit.OnboardingSnapshotFfi?>()
            setupSnapshotReply = read::hold
            val routing = async { runCatching { state.accountSetup.routeIfPending(accountRef) } }
            read.awaitEntered()
            setupSnapshotReply = null
            try {
                assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            } finally {
                read.release(
                    dev.ipf.whitenoise.android.ui.onboarding.setup
                        .setupSnapshot(),
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
            val read = HeldNativeCall<Boolean>()
            setupRecoveryReply = read::hold
            val recovery = async { runCatching { state.accountSetup.recover(accountRef) } }
            read.awaitEntered()
            setupRecoveryReply = null
            try {
                assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            } finally {
                read.release(true)
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
            // Partial native cleanup is not proof either way, so one readback confirms the account stayed signed in.
            assertEquals(1, listAccountsCalls.get())
            assertEquals(accountRef, appState.activeAccountRef)
            assertEquals(listOf(accountRef), appState.accounts.map { it.label })
            assertEquals(
                composerExpansion,
                appState.composerExpansionStateRetention.preferenceFor(accountRef, groupId),
            )
            assertEquals(phaseBefore, appState.phase)
            // Admission retires older selections before native work, so a selection queued during it can win.
            assertFalse(
                "admitted cleanup retires older selections even when native teardown stays unfinished",
                appState.accountSession.handoff.isCurrent(selectionBefore),
            )
        }

    /** A failed native reply is not proof that the retained identity was durably signed out. */
    @Test
    fun transientEngineFailureRetainsTheActiveSessionUntilNativeSignOutIsConfirmed() =
        runBlocking {
            signOutFailure = RuntimeException("native command unavailable")
            val state = appState()
            markReady(state)
            retainComposerExpansion(state)
            retainContactPictures(state)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(1, signOutCalls.get())
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertFalse(state.accounts.single().signedOut)
            assertFalse(nativePushEnabled)
            assertEquals(listOf("disable", "signOut"), nativeCallOrder)
            assertTrue(state.contactPictureStore.reference(accountRef, "private-contact") != null)
            assertEquals(composerExpansion, state.composerExpansionStateRetention.preferenceFor(accountRef, groupId))
        }
}
