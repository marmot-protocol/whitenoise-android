package dev.ipf.whitenoise.android.ui.account

import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Presentation order never mutates the source ordering used for native quick cycling. */
class ProfileSwitcherPresentationTest {
    @Test fun activeFirstRetainsNativeOrderAndRecoveryStates() {
        val accounts =
            listOf(
                AccountSummaryFfi("a", "aa", true, false, false, true),
                AccountSummaryFfi("b", "bb", true, false, false, true),
                AccountSummaryFfi("retained", "cc", true, false, true, false),
                AccountSummaryFfi("read-only", "dd", false, false, false, false),
            )
        val state = accountSelectorState(accounts, "b", true)
        val presented = profileSwitcherPresentation(state)
        assertEquals(listOf("b", "a", "retained", "read-only"), presented.map { it.label })
        assertEquals(listOf("a", "b", "retained", "read-only"), state.accounts.map { it.label })
        assertEquals(true, presented[2].isSignedOut)
        assertEquals(true, presented[3].isReadOnly)
    }

    @Test fun removedActiveDoesNotCreateAPhantomProfile() {
        val state = accountSelectorState(emptyList(), "removed", false)
        assertEquals(emptyList<AccountSelectorAccountState>(), profileSwitcherPresentation(state))
    }
}
