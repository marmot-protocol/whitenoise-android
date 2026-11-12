package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EventSizeClassFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueuedSizeRefusalNoticeTest {
    /** A queued invite whose Welcome fits none of the invitee's inbox relays reuses the invitation copy. */
    @Test
    fun refusedWelcomeSaysTheInvitationIsTooLarge() {
        assertEquals(
            QueuedSizeRefusal(ACCOUNT, GROUP, INTENT, AppText.Resource(R.string.error_invite_welcome_too_large)),
            queuedSizeRefusal(refusal(OversizedArtifactFfi.WELCOME)),
        )
    }

    /** A queued commit too large for every group relay, or one MDK did not classify, says the change was not made. */
    @Test
    fun refusedCommitSaysTheGroupChangeWasNotMade() {
        val expected = AppText.Resource(R.string.error_group_change_too_large)
        assertEquals(expected, queuedSizeRefusal(refusal(OversizedArtifactFfi.COMMIT))?.message)
        assertEquals(expected, queuedSizeRefusal(refusal(artifact = null))?.message)
    }

    /** Refusals for other reasons and other events are not size refusals. */
    @Test
    fun otherRefusalsAndEventsAreIgnored() {
        assertNull(queuedSizeRefusal(refusal(reason = "not_group_admin")))
        assertNull(
            queuedSizeRefusal(MarmotEventFfi.GroupEvent(ACCOUNT_HEX, ACCOUNT, GROUP, GroupEventKindFfi.GroupCreated)),
        )
        assertNull(queuedSizeRefusal(MarmotEventFfi.AccountError(ACCOUNT_HEX, ACCOUNT, "x")))
    }

    /** The active account sees the refusal; with no chat-list title it falls back to the message alone. */
    @Test
    fun activeAccountSeesTheRefusal() {
        val state = appState(active = ACCOUNT)
        val message = AppText.Resource(R.string.error_group_change_too_large)
        state.presentQueuedSizeRefusal(QueuedSizeRefusal(ACCOUNT, GROUP, INTENT, message))
        assertEquals(message, state.toast?.title)
        assertNull(state.toast?.detail)
    }

    /** A chat the chat list knows titles the toast; an unknown one leaves the chat-neutral message alone. */
    @Test
    fun knownChatTitlesTheToast() {
        val message = AppText.Resource(R.string.error_group_change_too_large)
        assertEquals(AppText.Plain("Team") to message, queuedSizeRefusalToast("Team", message))
        assertEquals(message to null, queuedSizeRefusalToast(null, message))
    }

    /** A refusal MDK delivers again after the stream resubscribes is shown once; a different intent still shows. */
    @Test
    fun repeatedRefusalIsShownOnce() {
        val state = appState(active = ACCOUNT)
        val refusal = queuedSizeRefusal(refusal(OversizedArtifactFfi.COMMIT))!!
        state.presentQueuedSizeRefusal(refusal)
        assertEquals(refusal.message, state.toast?.title)
        state.clearToast()
        state.presentQueuedSizeRefusal(queuedSizeRefusal(refusal(OversizedArtifactFfi.COMMIT))!!)
        assertNull(state.toast)
        state.presentQueuedSizeRefusal(refusal.copy(intentIdHex = "12".repeat(32)))
        assertEquals(refusal.message, state.toast?.title)
    }

    /** The shown set stays bounded, so the oldest refusal can be shown again once evicted. */
    @Test
    fun shownRefusalsAreBounded() {
        val shown = ShownQueuedRefusals(capacity = 2)
        assertTrue(shown.firstShowing(ACCOUNT, "a"))
        assertFalse(shown.firstShowing(ACCOUNT, "A"))
        assertTrue(shown.firstShowing("other", "a"))
        assertTrue(shown.firstShowing(ACCOUNT, "b"))
        assertTrue(shown.firstShowing(ACCOUNT, "a"))
    }

    /** A refusal for an account that is no longer active shows nothing. */
    @Test
    fun anotherAccountsRefusalShowsNothing() {
        val state = appState(active = "other")
        state.presentQueuedSizeRefusal(
            QueuedSizeRefusal(ACCOUNT, GROUP, INTENT, AppText.Resource(R.string.error_group_change_too_large)),
        )
        assertNull(state.toast)
    }

    /** An app state whose active account is [active] and which has no chat list yet. */
    private fun appState(active: String) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = active,
        )

    /** A `QueuedIntentRefused` group event for [ACCOUNT] with the given [reason] and [artifact]. */
    private fun refusal(
        artifact: OversizedArtifactFfi? = OversizedArtifactFfi.COMMIT,
        reason: String = QUEUED_INTENT_REFUSED_TOO_LARGE,
    ) = MarmotEventFfi.GroupEvent(
        accountIdHex = ACCOUNT_HEX,
        accountLabel = ACCOUNT,
        groupIdHex = GROUP,
        event =
            GroupEventKindFfi.QueuedIntentRefused(
                intentIdHex = INTENT,
                intentKind = "invite",
                reason = reason,
                artifact = artifact,
                sizeClass = artifact?.let { EventSizeClassFfi.FROM64_TO128_KIB },
            ),
    )

    private companion object {
        const val ACCOUNT = "account-a"
        val ACCOUNT_HEX = "ab".repeat(32)
        val GROUP = "ef".repeat(32)
        val INTENT = "cd".repeat(32)
    }
}
