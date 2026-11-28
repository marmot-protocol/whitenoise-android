package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.rememberRemoteSignerAccountActions
import dev.ipf.whitenoise.android.ui.settings.remoteSignerAccountItems
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Both signer entry points rely on one HTTPS-only browser launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteSignerApprovalLaunchTest {
    @get:Rule val composeRule = createComposeRule()

    /** Android receives a normalized HTTPS browser intent, and a launch failure is visible to the caller. */
    @Test
    fun launchesOnlyHttpsAndReportsMissingBrowser() {
        val intents = mutableListOf<Intent>()
        var hasBrowser = true
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                /** Records resolution attempts before simulating a missing activity. */
                override fun startActivity(intent: Intent) {
                    intents += intent
                    if (!hasBrowser) throw ActivityNotFoundException()
                }
            }

        assertFalse(context.openRemoteSignerApproval("http://signer.example/check"))
        assertTrue(intents.isEmpty())
        assertTrue(context.openRemoteSignerApproval("HTTPS://signer.example/check"))
        assertEquals("https://signer.example/check", intents.single().dataString)
        assertTrue(intents.single().hasCategory(Intent.CATEGORY_BROWSABLE))

        hasBrowser = false
        assertFalse(context.openRemoteSignerApproval("https://signer.example/check"))
        assertEquals(2, intents.size)
    }

    /** Account Keys reports an unavailable browser and withholds acknowledgement until a successful launch. */
    @Test
    fun accountKeysReportsLaunchFailureAndAllowsRetry() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val state =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = null,
            )
        val account = "a1b2c3"
        val session = FakeRemoteSignerSession()
        session.status = Nip46StatusFfi(Nip46StateFfi.APPROVAL, null, "HTTPS://signer.example/check", "r1", 1u)
        state.remoteSigners.adopt(Any(), account, session)
        var hasBrowser = false

        composeRule.setContent {
            val context = LocalContext.current
            val browser =
                object : ContextWrapper(context) {
                    /** Simulates Android rejecting the browser intent until a handler is available. */
                    override fun startActivity(intent: Intent) {
                        if (!hasBrowser) throw ActivityNotFoundException()
                    }
                }
            CompositionLocalProvider(LocalContext provides browser) {
                WhiteNoiseTheme {
                    val actions = checkNotNull(rememberRemoteSignerAccountActions(state, account))
                    SettingsList { remoteSignerAccountItems(actions) }
                }
            }
        }
        val openApproval = composeRule.onNodeWithTag("remote_signer.account.open_approval")
        val finishApproval = composeRule.onNodeWithTag("remote_signer.account.approval_done")
        openApproval.performClick()
        finishApproval.assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(AppText.Resource(R.string.remote_signer_approval_open_failed), state.toast?.title)
            hasBrowser = true
        }
        openApproval.performClick()
        finishApproval.assertIsDisplayed()
    }
}
