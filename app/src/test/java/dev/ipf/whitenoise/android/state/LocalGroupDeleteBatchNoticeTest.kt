package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A partial batch is an actionable error, not a checkmarked success or an unrelated diagnostic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGroupDeleteBatchNoticeTest {
    @Test
    fun partialCountAndRetryGuidanceRetainTheExactFailingAttemptsDiagnostic() {
        val state = emptyAppState()
        val failure =
            LocalGroupDeleteFailure(
                LocalDeletePhase.PresenceReconciliation,
                3,
                true,
                null,
                MarmotKitException.TransportClosed(),
            )
        unrelatedFailure(state)
        state.presentStoppedLocalChatDeleteBatch(LocalChatDeleteBatchResult(500, 52, 51), failure)
        val toast = requireNotNull(state.toast)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(AppText.Resource(R.string.chat_list_delete_stopped), toast.title)
        val detail = requireNotNull(toast.detail).resolve(context)
        assertEquals(
            context.getString(R.string.chat_list_delete_stopped_detail, 51, 500),
            detail,
        )
        assertTrue("numeric progress must preserve its content direction in RTL", detail.startsWith('\u2068'))
        assertTrue("directional isolation must be closed", detail.endsWith('\u2069'))
        assertTrue(toast.copyable)
        assertEquals(NoticeTier.ActionableError, toast.tier)
        val report = requireNotNull(toast.diagnosticReport)
        assertTrue(report.contains("operation=CHAT_LOCAL_DELETE"))
        assertTrue(report.contains("phase=presence_reconciliation;attempt=3;exhausted=1;presence=unknown"))
        assertTrue(report.contains("marmot=TransportClosed"))
        assertFalse(report.contains("OTHER_OPERATION"))
        assertEquals(null, state.transientNotice)
    }

    @Test
    fun stoppedWithoutCapturedFailureDoesNotCopyAnUnrelatedReportOrShowSuccess() {
        val state = emptyAppState()
        unrelatedFailure(state)
        state.presentStoppedLocalChatDeleteBatch(LocalChatDeleteBatchResult(2, 1, 0), null)
        val toast = requireNotNull(state.toast)
        assertFalse(toast.copyable)
        assertEquals(null, toast.diagnosticReport)
        assertEquals(NoticeTier.ActionableError, toast.tier)
        assertEquals(AppText.Resource(R.string.chat_list_delete_stopped), toast.title)
        assertEquals(AppText.Resource(R.string.chat_list_delete_stopped_detail, listOf(0, 2)), toast.detail)
        assertEquals(null, state.transientNotice)
    }

    private fun unrelatedFailure(state: WhiteNoiseAppState) {
        state.presentFailure(
            R.string.toast_couldnt_delete_chat,
            "OTHER_OPERATION",
            IllegalArgumentException("unrelated"),
        )
    }
}
