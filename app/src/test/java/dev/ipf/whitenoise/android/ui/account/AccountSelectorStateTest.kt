package dev.ipf.whitenoise.android.ui.account

import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSelectorStateTest {
    @Test
    fun preservesAccountOrderAndDerivesActiveHighlight() {
        val state =
            accountSelectorState(
                accounts = listOf(account("work"), account("personal"), account("archive")),
                activeAccountRef = "personal",
                refreshing = false,
            )

        assertEquals(listOf("work", "personal", "archive"), state.accounts.map { it.label })
        assertEquals(listOf(false, true, false), state.accounts.map { it.isActive })
    }

    @Test
    fun addAccountActionRemainsVisibleWhileRefreshing() {
        val state =
            accountSelectorState(
                accounts = emptyList(),
                activeAccountRef = null,
                refreshing = true,
            )

        assertTrue(state.showAddAccount)
        assertTrue(state.refreshing)
    }

    private fun account(label: String): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "hex-$label",
            localSigning = true,
            signedOut = false,
            running = true,
            externalSigning = false,
        )
}
