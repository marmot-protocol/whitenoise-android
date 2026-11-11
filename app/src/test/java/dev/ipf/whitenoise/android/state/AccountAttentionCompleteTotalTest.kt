package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountAttentionEntryFfi
import dev.ipf.marmotkit.AccountAttentionSnapshotFfi
import dev.ipf.marmotkit.AccountAttentionStateFfi
import dev.ipf.marmotkit.AccountAttentionTotalFfi
import dev.ipf.marmotkit.AccountAttentionUnavailableFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account unread dots follow the complete native attention total. A chat-list recompute that folds only the
 * bounded loaded rows cannot clear a dot the complete total still lights (#3729).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountAttentionCompleteTotalTest {
    /** An unread chat outside the loaded window keeps the dot through a bounded recompute that sees none. */
    @Test
    fun boundedRecomputeKeepsAnOffWindowUnreadDot() {
        val state = appState()
        state.applyAccountAttention(snapshot(1uL, ready(unread = 3uL)))

        state.updateAccountUnreadProjection(ACCOUNT, unreadCount = 0uL, hasManualUnread = false)

        assertTrue(state.accountShowsUnreadDot(ACCOUNT))
    }

    /** A manual-only reminder outside the window keeps the dot the same way. */
    @Test
    fun boundedRecomputeKeepsAnOffWindowManualReminderDot() {
        val state = appState()
        state.applyAccountAttention(snapshot(1uL, ready(unread = 0uL, attentionOnly = 1uL)))

        state.updateAccountUnreadProjection(ACCOUNT, unreadCount = 0uL, hasManualUnread = false)

        assertTrue(state.accountShowsUnreadDot(ACCOUNT))
    }

    /** A later complete zero clears the dot, including a manual-only flag a projection left behind. */
    @Test
    fun completeZeroClearsTheDotIncludingAStaleManualFlag() {
        val state = appState()
        state.updateAccountUnreadProjection(ACCOUNT, unreadCount = 0uL, hasManualUnread = true)
        assertTrue(state.accountShowsUnreadDot(ACCOUNT))

        state.applyAccountAttention(snapshot(1uL, ready(unread = 0uL)))

        assertFalse(state.accountShowsUnreadDot(ACCOUNT))
    }

    /** Without a Ready total, for example while the account is unavailable, the row fold still publishes. */
    @Test
    fun rowFoldStillPublishesWhenNoCompleteTotalIsReady() {
        val state = appState()
        state.applyAccountAttention(
            snapshot(
                1uL,
                AccountAttentionStateFfi.Unavailable(AccountAttentionUnavailableFfi.RESETTING),
            ),
        )

        state.updateAccountUnreadProjection(ACCOUNT, unreadCount = 2uL, hasManualUnread = false)

        assertTrue(state.accountShowsUnreadDot(ACCOUNT))
    }

    /** A Ready attention state for this account. */
    private fun ready(
        unread: ULong,
        attentionOnly: ULong = 0uL,
    ) = AccountAttentionStateFfi.Ready(AccountAttentionTotalFfi(unread, 0uL, unread, attentionOnly))

    /** A summary holding only this account's [state]. */
    private fun snapshot(
        revision: ULong,
        state: AccountAttentionStateFfi,
    ) = AccountAttentionSnapshotFfi("generation", revision, listOf(AccountAttentionEntryFfi(ACCOUNT_ID, state)))

    /** An app state with one signed-in account. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true, false)),
            activeAccountRef = ACCOUNT,
        )

    private companion object {
        const val ACCOUNT = "personal"
        val ACCOUNT_ID = "ab".repeat(32)
    }
}
