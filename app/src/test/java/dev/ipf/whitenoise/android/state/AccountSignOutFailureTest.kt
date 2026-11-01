package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayFailureFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Native acknowledgement loss must never masquerade as durable local sign-out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class AccountSignOutFailureTest : ExternalSignerSignOutFixture() {
    /** Unknown durable status retains the session instead of guessing that cleanup committed. */
    @Test
    fun thrownReplyAndFailedStatusReadKeepTheActiveAccount() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic reply failure")
            listAccountsFailure = IllegalStateException("synthetic read failure")
            val state = appState()
            markReady(state)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertFalse(state.accounts.single().signedOut)
            assertEquals(1, listAccountsCalls.get())
            assertFalse(nativePushEnabled)
            assertColdBootstrap(nativeAccounts(), accountRef)
        }

    /** Losing a reply after native commit still retires the local account and preserves a signed-in successor. */
    @Test
    fun thrownReplyAfterNativeCommitCompletesLocalSignOutAndSelectsSuccessor() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic lost reply")
            persistSignedOutBeforePartialFailure = true
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            val state = appState(profileReader = { null })

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
        }

    /** The default UI path shows failure while startup sees the same still-signed-in native account. */
    @Test
    fun defaultUiSignOutFailureIsHonestAcrossColdBootstrap() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic native rejection")
            val state = appState()
            markReady(state)
            context
                .getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("active_account", accountRef)
                .commit()

            dev.ipf.whitenoise.android.ui.settings
                .signOutActiveAccount(state, deleteKeyPackages = true)
            awaitUiSignOut(state)

            assertEquals(AppText.Resource(R.string.toast_couldnt_sign_out), state.toast?.title)
            assertNull(state.transientNotice)
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertEquals(
                accountRef,
                context
                    .getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE)
                    .getString("active_account", null),
            )
            assertColdBootstrap(nativeAccounts(), accountRef)
        }

    /** Confirmed native commit with a lost reply stays signed out on restart and reports unknown relay cleanup. */
    @Test
    fun confirmedNativeSignOutSurvivesLostReplyAndColdBootstrap() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic lost reply")
            persistSignedOutBeforePartialFailure = true
            val state = appState()
            markReady(state)

            dev.ipf.whitenoise.android.ui.settings
                .signOutActiveAccount(state, deleteKeyPackages = true)
            awaitUiSignOut(state)

            assertEquals(AppText.Resource(R.string.toast_signed_out_relay_cleanup_incomplete), state.toast?.title)
            assertNull(state.activeAccountRef)
            assertEquals(AppPhase.Onboarding, state.phase)
            assertTrue(state.accounts.single().signedOut)
            assertColdBootstrap(nativeAccounts(), null)
        }

    /** Ordinary relay failures remain best-effort cleanup after a successful native local commit. */
    @Test
    fun typedRelayFailureStillCompletesLocalSignOut() =
        runBlocking {
            signOutOutcome = signOutOutcome.copy(keyPackageFailures = listOf(RelayFailureFfi("", "unavailable")))
            val state = appState()
            assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
            assertNull(state.activeAccountRef)
            assertEquals(AppPhase.Onboarding, state.phase)
            assertTrue(state.accounts.single().signedOut)
        }

    /** Pump the actual app-owned UI mutation rather than substituting a direct completion mapper. */
    private suspend fun awaitUiSignOut(state: WhiteNoiseAppState) {
        withTimeout(10_000L) {
            while (state.signOutInProgress) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(1L)
            }
        }
    }

    /** Read the fake's durable native state independently of Android's post-operation account cache. */
    private fun nativeAccounts() = listOf(externalSignerAccount(signedOut = engineSignedOut)) + additionalAccounts

    /** A fresh runtime consumes the durable native snapshot and the saved host selection without reactivation. */
    private suspend fun assertColdBootstrap(
        accounts: List<dev.ipf.marmotkit.AccountSummaryFfi>,
        expectedRef: String?,
    ) {
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                accounts = accounts,
                initialActiveAccountRef = null,
                emitStartupNotification = false,
            )
        try {
            fixture.bootstrap()
            assertEquals(expectedRef, fixture.appState.activeAccountRef)
            assertEquals(if (expectedRef == null) AppPhase.Onboarding else AppPhase.Ready, fixture.appState.phase)
            assertTrue(fixture.signInRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }
}
