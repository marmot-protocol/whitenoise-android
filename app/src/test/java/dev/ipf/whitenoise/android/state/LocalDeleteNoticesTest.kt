package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalDeleteNoticesTest {
    @Test
    fun scopedPublisherRetainsIdentityAndCannotCopyVisibleTextWithoutAReport() {
        val state = emptyAppState()
        val scope = LocalDeleteNotice("account", setOf("group"))
        state.presentText(
            ToastMessage(
                title = AppText.Plain("Failure"), copyable = true,
                diagnosticReport = "   ", localDeleteNotice = scope,
            ),
        )
        assertSame(scope, state.toast?.localDeleteNotice)
        assertEquals(false, state.toast?.copyable)
        assertEquals(null, state.toast?.diagnosticReport)
    }

    @Test
    fun unrelatedAccountOrGroupCannotRetireTheWarning() {
        val state = emptyAppState()
        state.presentLocalDeleteFailure(
            R.string.toast_couldnt_delete_chat, IllegalStateException(),
            notice = LocalDeleteNotice("account", setOf("group")),
        )
        val notice = state.toast
        state.dismissLocalDeleteFailure("other", "group")
        state.dismissLocalDeleteFailure("account", "other")
        assertSame(notice, state.toast)
    }

    @Test
    fun partialRecoveryKeepsRemainingTargetsUntilEveryTargetResolves() {
        val state = emptyAppState()
        state.presentLocalDeleteFailure(
            R.string.chat_list_delete_stopped, IllegalStateException(),
            notice = LocalDeleteNotice("account", setOf("AA", "BB")),
        )
        state.dismissLocalDeleteFailure("account", "aa")
        assertNotNull(state.toast)
        assertEquals(setOf("BB"), state.toast?.localDeleteNotice?.groupIds)
        state.dismissLocalDeleteFailure("account", "bb")
        assertEquals(null, state.toast)
    }

    @Test
    fun recoveryPreservesANewerUnrelatedFailure() {
        val state = emptyAppState()
        state.presentLocalDeleteFailure(
            R.string.toast_couldnt_delete_chat, IllegalStateException(),
            notice = LocalDeleteNotice("account", setOf("group")),
        )
        state.present(R.string.error_try_again)
        val newer = state.toast
        state.dismissLocalDeleteFailure("account", "group")
        assertSame(newer, state.toast)
    }
}
