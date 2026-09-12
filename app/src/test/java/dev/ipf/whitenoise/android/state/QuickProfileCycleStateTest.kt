package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preference scope and cycle ordering remain independent of presentation sorting or signer process liveness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickProfileCycleStateTest {
    /** Off, empty/single account and missing active account all produce no target. */
    @Test fun defaultAndMissingTargetsNeverCycle() {
        assertNull(nextQuickProfileCycleAccount(listOf(A, B), A.label, enabled = false))
        assertNull(nextQuickProfileCycleAccount(emptyList(), A.label, enabled = true))
        assertNull(nextQuickProfileCycleAccount(listOf(A), A.label, enabled = true))
        assertNull(nextQuickProfileCycleAccount(listOf(A, B), null, enabled = true))
        assertNull(nextQuickProfileCycleAccount(listOf(A, B), "removed", enabled = true))
    }

    /** Stable source order wraps; active-first presentation is never used to choose a destination. */
    @Test fun stableOrderWrapsAndTracksAddRemove() {
        assertEquals(B, nextQuickProfileCycleAccount(listOf(A, B, C), A.label, true))
        assertEquals(C, nextQuickProfileCycleAccount(listOf(A, B, C), B.label, true))
        assertEquals(A, nextQuickProfileCycleAccount(listOf(A, B, C), C.label, true))
        assertEquals(C, nextQuickProfileCycleAccount(listOf(A, C), A.label, true))
        assertEquals(B, nextQuickProfileCycleAccount(listOf(C, A, B), A.label, true))
    }

    /** Retained sign-out, watch-only and incomplete setup are excluded; dormant external signers remain eligible. */
    @Test fun nativeSigningAndSetupEligibilityArePreserved() {
        val retained = B.copy(signedOut = true)
        val readOnly = B.copy(localSigning = false)
        val external = B.copy(localSigning = false, externalSigning = true, running = false)
        assertEquals(C, nextQuickProfileCycleAccount(listOf(A, retained, C), A.label, true))
        assertEquals(C, nextQuickProfileCycleAccount(listOf(A, readOnly, C), A.label, true))
        assertEquals(external, nextQuickProfileCycleAccount(listOf(A, external, C), A.label, true))
        assertEquals(C, nextQuickProfileCycleAccount(listOf(A, B, C), A.label, true) { it.label != B.label })
    }

    /** The explicit app-wide value survives new account owners and sign-out; clearing app preferences resets it. */
    @Test fun preferenceDefaultsOffPersistsAcrossAccountsAndResetsWithErase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("cycle-preference-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

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
        assertFalse(first.quickProfileCycling)
        first.updateQuickProfileCycling(true)
        assertEquals(A.label, first.activeAccountRef)
        assertTrue(state(listOf(A, B, C), C.label).quickProfileCycling)
        assertTrue(state(listOf(A.copy(signedOut = true), B), B.label).quickProfileCycling)
        preferences.edit().clear().commit()
        assertFalse(state(listOf(A, B), A.label).quickProfileCycling)
    }

    /** Destructive transitions reject the real app-owned target before a native request can start. */
    @Test fun teardownGatesTargetsAndCompletionCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = WhiteNoiseAppState(context, DraftStore.forContext(context), { null }, listOf(A, B), A.label)
        app.updateQuickProfileCycling(true)
        app.wipeInProgress = true
        assertNull(app.quickProfileCycleTarget())
        app.requestQuickProfileCycle({ _, _ -> error("Wipe must reject") }, { error("No activation") })
        app.wipeInProgress = false
        app.signOutInProgress = true
        assertNull(app.quickProfileCycleTarget())
        app.requestQuickProfileCycle({ _, _ -> error("Sign out must reject") }, { error("No activation") })
    }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true)
        val C = AccountSummaryFfi("c", "cc".repeat(32), true, false, false, true)
    }
}
