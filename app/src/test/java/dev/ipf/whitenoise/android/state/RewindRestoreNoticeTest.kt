package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.AppQuarantinedGroupFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A chat MDK could not return to its latest state tells the user, once per account and chat, to restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RewindRestoreNoticeTest {
    /** Only a rewind-restore quarantine maps to the notice, other reasons and events are ignored. */
    @Test
    fun onlyARewindRestoreQuarantineMaps() {
        assertEquals(
            RewindRestoreFailure(ACCOUNT, GROUP),
            rewindRestoreFailure(quarantined(AppGroupHydrationQuarantineReasonFfi.REWIND_RESTORE_FAILED)),
        )
        AppGroupHydrationQuarantineReasonFfi.entries
            .filter { it != AppGroupHydrationQuarantineReasonFfi.REWIND_RESTORE_FAILED }
            .forEach { assertNull(rewindRestoreFailure(quarantined(it))) }
        val created = MarmotEventFfi.GroupEvent(ACCOUNT_HEX, ACCOUNT, GROUP, GroupEventKindFfi.GroupCreated)
        assertNull(rewindRestoreFailure(created))
    }

    /** The quarantine list read at stream open keeps only the rewind-restore failures. */
    @Test
    fun theQuarantineListKeepsOnlyRewindRestoreFailures() {
        val restore = AppGroupHydrationQuarantineReasonFfi.REWIND_RESTORE_FAILED
        val other = AppGroupHydrationQuarantineReasonFfi.entries.first { it != restore }
        val listed =
            listOf(
                AppQuarantinedGroupFfi(GROUP, restore),
                AppQuarantinedGroupFfi(OTHER_GROUP, other),
            )

        assertEquals(listOf(RewindRestoreFailure(ACCOUNT, GROUP)), rewindRestoreFailures(ACCOUNT, listed))
    }

    /** A signed-in account's failure shows the restart notice once, while another chat or account still shows. */
    @Test
    fun eachChatShowsOncePerAccount() {
        val state = appState()

        state.presentRewindRestoreFailure(RewindRestoreFailure(ACCOUNT, GROUP))
        assertEquals(AppText.Resource(R.string.notice_rewind_restore_title), state.toast?.title)
        assertEquals(AppText.Resource(R.string.quarantine_rewind_restore), state.toast?.detail)
        state.clearToast()

        state.presentRewindRestoreFailure(RewindRestoreFailure(ACCOUNT, GROUP.uppercase()))
        assertNull("a repeat for the same chat showed again", state.toast)
        state.presentRewindRestoreFailure(RewindRestoreFailure(ACCOUNT, OTHER_GROUP))
        assertEquals(AppText.Resource(R.string.notice_rewind_restore_title), state.toast?.title)
    }

    /** An account that is not signed in on this device shows nothing. */
    @Test
    fun anAccountThatIsNotSignedInShowsNothing() {
        val state = appState()

        state.presentRewindRestoreFailure(RewindRestoreFailure("someone-else", GROUP))

        assertNull(state.toast)
    }

    /** An app state with one signed-in signing account and no chat list. */
    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_HEX },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT,
                        accountIdHex = ACCOUNT_HEX,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT,
        )

    /** A quarantine group event for [ACCOUNT]'s [GROUP] with [reason]. */
    private fun quarantined(reason: AppGroupHydrationQuarantineReasonFfi) =
        MarmotEventFfi.GroupEvent(ACCOUNT_HEX, ACCOUNT, GROUP, GroupEventKindFfi.GroupHydrationQuarantined(reason))

    private companion object {
        const val ACCOUNT = "account-a"
        val ACCOUNT_HEX = "ab".repeat(32)
        val GROUP = "ef".repeat(32)
        val OTHER_GROUP = "cd".repeat(32)
    }
}
