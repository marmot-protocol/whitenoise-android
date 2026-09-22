package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.SendFailureAttempt
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.dismissSendFailureNotice
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A send-failure snackbar is indefinite, so only an explicit dismissal removes it. It must survive
 * everything except the recovery of the very send it reports (#2666). The host binding mirrors the
 * one in `WhiteNoiseApp`: the snackbar is shown for the current toast and torn down with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SendFailureNoticeDismissalTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The notice stays through an unrelated send's recovery and goes when its own send recovers. */
    @Test
    fun noticeDisappearsOnlyWhenItsOwnSendRecovers() {
        val appState = appState()
        composeRule.setContent { WhiteNoiseTheme { NoticeHost(appState) } }

        composeRule.runOnIdle { presentNotice(appState, R.string.toast_send_failed, "MESSAGE_SEND", ATTEMPT) }
        composeRule.onNodeWithText(sendFailedTitle(), substring = true).assertExists()

        composeRule.runOnIdle { appState.dismissSendFailureNotice(ATTEMPT.copy(optimisticKey = "msg:other-send")) }
        composeRule.onNodeWithText(sendFailedTitle(), substring = true).assertExists()

        composeRule.runOnIdle { appState.dismissSendFailureNotice(ATTEMPT.copy(groupIdHex = "b4".repeat(32))) }
        composeRule.onNodeWithText(sendFailedTitle(), substring = true).assertExists()

        composeRule.runOnIdle { appState.dismissSendFailureNotice(ATTEMPT) }
        composeRule.onNodeWithText(sendFailedTitle(), substring = true).assertDoesNotExist()
    }

    /** A notice with no correlated send is never retired by a recovery. */
    @Test
    fun uncorrelatedNoticeIsNeverRetiredByARecovery() {
        val appState = appState()
        composeRule.setContent { WhiteNoiseTheme { NoticeHost(appState) } }

        composeRule.runOnIdle { presentNotice(appState, R.string.toast_couldnt_update_group, "GROUP_UPDATE", null) }
        val title = context.getString(R.string.toast_couldnt_update_group)
        composeRule.onNodeWithText(title, substring = true).assertExists()

        composeRule.runOnIdle { appState.dismissSendFailureNotice(ATTEMPT) }
        composeRule.onNodeWithText(title, substring = true).assertExists()
    }

    /** The same toast-to-snackbar binding the app shell uses. */
    @Composable
    private fun NoticeHost(appState: WhiteNoiseAppState) {
        val hostState = remember { SnackbarHostState() }
        val toast = appState.toast
        val hostContext = LocalContext.current
        Scaffold(snackbarHost = { SnackbarHost(hostState) }) { padding -> padding.calculateTopPadding() }
        LaunchedEffect(toast) {
            if (toast != null) {
                hostState.showSnackbar(
                    ToastSnackbarVisuals(
                        message =
                            listOfNotNull(toast.title.resolve(hostContext), toast.detail?.resolve(hostContext))
                                .joinToString("\n"),
                        copyable = toast.copyable,
                        tier = toast.tier,
                        copyText = toast.diagnosticReport,
                    ),
                )
                appState.clearToast()
            }
        }
    }

    /** Raises one actionable error notice, optionally correlated with [attempt]. */
    private fun presentNotice(
        appState: WhiteNoiseAppState,
        titleRes: Int,
        operationCode: String,
        attempt: SendFailureAttempt?,
    ) {
        appState.presentFailure(titleRes, operationCode, failure(), sendAttempt = attempt)
    }

    /** The localized "Send failed" title the snackbar shows. */
    private fun sendFailedTitle(): String = context.getString(R.string.toast_send_failed)

    /** A stand-in engine failure; its text never reaches the screen. */
    private fun failure(): Throwable = IllegalStateException("illegal queue_app_message transition")

    /** One signed-in account, with no runtime attached. */
    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** Draft storage that keeps nothing. */
    private object NoDrafts : DraftPersistence {
        /** No drafts are ever restored. */
        override fun read(): Map<String, String> = emptyMap()

        /** Writes are discarded. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val ATTEMPT = SendFailureAttempt(ACCOUNT_REF, GROUP_ID, "msg:temp-1")
    }
}
