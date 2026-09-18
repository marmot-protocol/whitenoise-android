package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which accounts the chat list offers beside the active avatar, and the scope of the opt-in that reveals them.
 *
 * The order is the stable native one, deliberately independent of the selector's active-first presentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickAccountSwitchingStateTest {
    /** Off, empty, single-account and missing-active states all offer nothing to switch to. */
    @Test fun defaultAndMissingActiveOfferNoAccounts() {
        assertEquals(emptyList<AccountSummaryFfi>(), quickSwitchAccounts(listOf(A, B), A.label, enabled = false))
        assertEquals(emptyList<AccountSummaryFfi>(), quickSwitchAccounts(emptyList(), A.label, enabled = true))
        assertEquals(emptyList<AccountSummaryFfi>(), quickSwitchAccounts(listOf(A), A.label, enabled = true))
        assertEquals(emptyList<AccountSummaryFfi>(), quickSwitchAccounts(listOf(A, B), null, enabled = true))
        assertEquals(emptyList<AccountSummaryFfi>(), quickSwitchAccounts(listOf(A, B), "removed", enabled = true))
    }

    /** Every other signed-in account is offered, in source order, with the active one left out. */
    @Test fun stableOrderExcludesOnlyTheActiveAccount() {
        assertEquals(listOf(B, C), quickSwitchAccounts(listOf(A, B, C), A.label, true))
        assertEquals(listOf(A, C), quickSwitchAccounts(listOf(A, B, C), B.label, true))
        assertEquals(listOf(A, B), quickSwitchAccounts(listOf(A, B, C), C.label, true))
        assertEquals(listOf(C, B), quickSwitchAccounts(listOf(A, C, B), A.label, true))
    }

    /** Retained sign-out, watch-only and incomplete setup are excluded; dormant external signers remain eligible. */
    @Test fun nativeSigningAndSetupEligibilityArePreserved() {
        val retained = B.copy(signedOut = true)
        val readOnly = B.copy(localSigning = false)
        val external = B.copy(localSigning = false, externalSigning = true, running = false)
        assertEquals(listOf(C), quickSwitchAccounts(listOf(A, retained, C), A.label, true))
        assertEquals(listOf(C), quickSwitchAccounts(listOf(A, readOnly, C), A.label, true))
        assertEquals(listOf(external, C), quickSwitchAccounts(listOf(A, external, C), A.label, true))
        assertEquals(listOf(C), quickSwitchAccounts(listOf(A, B, C), A.label, true) { it.label != B.label })
    }

    /** The explicit app-wide value survives new account owners and sign-out; clearing app preferences resets it. */
    @Test fun preferenceDefaultsOffPersistsAcrossAccountsAndResetsWithErase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("quick-switch-preference-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        /** Builds the state fixture for the test. */
        fun state(
            accounts: List<AccountSummaryFfi>,
            active: String,
        ) = WhiteNoiseAppState(
            context,
            DraftStore.forContext(context),
            { null },
            accounts,
            active,
            preferences = preferences,
        )
        val first = state(listOf(A, B), A.label)
        assertFalse(first.quickAccountSwitching)
        first.updateQuickAccountSwitching(true)
        assertEquals(A.label, first.activeAccountRef)
        assertTrue(state(listOf(A, B, C), C.label).quickAccountSwitching)
        assertTrue(state(listOf(A.copy(signedOut = true), B), B.label).quickAccountSwitching)
        preferences.edit().clear().commit()
        assertFalse(state(listOf(A, B), A.label).quickAccountSwitching)
    }

    /** Destructive transitions empty the row and reject a switch before a native request can start. */
    @Test fun teardownHidesTheRowAndGatesCompletionCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = WhiteNoiseAppState(context, DraftStore.forContext(context), { null }, listOf(A, B), A.label)
        app.updateQuickAccountSwitching(true)
        app.wipeInProgress = true
        assertEquals(emptyList<AccountSummaryFfi>(), app.quickSwitchAvatarAccounts())
        app.requestQuickAccountSwitchTo(B.label, { _, _ -> error("Wipe must reject") }, { error("No activation") })
        app.wipeInProgress = false
        app.signOutInProgress = true
        assertEquals(emptyList<AccountSummaryFfi>(), app.quickSwitchAvatarAccounts())
        app.requestQuickAccountSwitchTo(B.label, { _, _ -> error("Sign out must reject") }, { error("No activation") })
    }

    /** A label that is not an offered account never reaches the native switch owner. */
    @Test fun unknownTargetIsRejectedBeforeAnyNativeRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = WhiteNoiseAppState(context, DraftStore.forContext(context), { null }, listOf(A, B), A.label)
        app.updateQuickAccountSwitching(true)

        app.requestQuickAccountSwitchTo("removed", { _, _ -> error("Unknown target must reject") }, { error("None") })
        app.requestQuickAccountSwitchTo(A.label, { _, _ -> error("Active account must reject") }, { error("None") })
    }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true)
        val C = AccountSummaryFfi("c", "cc".repeat(32), true, false, false, true)
    }
}
