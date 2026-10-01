package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountAttentionEntryFfi
import dev.ipf.marmotkit.AccountAttentionSnapshotFfi
import dev.ipf.marmotkit.AccountAttentionStateFfi
import dev.ipf.marmotkit.AccountAttentionTotalFfi
import dev.ipf.marmotkit.AccountAttentionUnavailableFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Chats pill consumes only the ready native message total for its own account. */
class AppStateAccountAttentionMessageCountTest {
    /** Replacement snapshots suppress unavailable and other-account values without adding reminders. */
    @Test fun selectsMessageOnlyTotalFromCurrentReplacement() {
        val ready =
            AccountAttentionEntryFfi(
                "a",
                AccountAttentionStateFfi.Ready(AccountAttentionTotalFfi(5uL, 0uL, 1uL, 7uL)),
            )
        val other =
            AccountAttentionEntryFfi(
                "b",
                AccountAttentionStateFfi.Ready(AccountAttentionTotalFfi(ULong.MAX_VALUE, 0uL, 1uL, 0uL)),
            )
        val initial = AccountAttentionSnapshotFfi("generation", 1uL, listOf(ready, other))
        assertEquals(5uL, initial.readyMessageCount("a"))
        assertEquals(ULong.MAX_VALUE, initial.readyMessageCount("b"))
        assertNull(initial.readyMessageCount("c"))
        val unavailable =
            AccountAttentionSnapshotFfi(
                "generation",
                2uL,
                listOf(
                    AccountAttentionEntryFfi(
                        "a",
                        AccountAttentionStateFfi.Unavailable(AccountAttentionUnavailableFfi.RESETTING),
                    ),
                ),
            )
        assertNull(unavailable.readyMessageCount("a"))
        val reminderOnly =
            AccountAttentionSnapshotFfi(
                "generation",
                3uL,
                listOf(
                    ready.copy(
                        state = AccountAttentionStateFfi.Ready(AccountAttentionTotalFfi(0uL, 0uL, 0uL, 7uL)),
                    ),
                ),
            )
        assertEquals(0uL, reminderOnly.readyMessageCount("a"))
    }
}
