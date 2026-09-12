package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Actual secure window and native import boundary behind the prototype Add Profile navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddProfileNativeEntryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shown = mutableStateOf(true)
    private val release = CompletableDeferred<Unit>()
    private val secret = "nsec1" + "q".repeat(58)
    private var dismissals = 0

    /** Two callbacks in one event loop reach the process-owned import only once, using the frozen submitted key. */
    @Test fun duplicateImportCallbacksStartOnlyOneNativeRequest() {
        val engine = heldEngine()
        show(signInTestAppState(context, engine))
        try {
            enterKey(secret)
            val click =
                composeRule
                    .onNodeWithTag("onboarding.sign_in.action")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            composeRule.runOnIdle {
                click()
                click()
            }
            composeRule.waitUntil { engine.setupBegins.isNotEmpty() }
            assertEquals(listOf(secret), engine.setupBegins)
            assertTrue(engine.logins.isEmpty())
        } finally {
            release.complete(Unit)
        }
    }

    /** System Back retains local dismissal during work; its late failure cannot close or contaminate the new flow. */
    @Test fun dismissedImportFailureCannotAffectReopenedAddProfile() {
        val engine = heldEngine()
        show(signInTestAppState(context, engine))
        try {
            enterKey(secret)
            composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
            composeRule.waitUntil { engine.setupBegins.isNotEmpty() }
            composeRule.runOnIdle {
                (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
            assertEquals(1, dismissals)
            composeRule.runOnIdle { shown.value = true }
            composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertExists()
            release.complete(Unit)
            composeRule.waitForIdle()
            assertEquals(1, dismissals)
            composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
            composeRule
                .onNodeWithText(context.getString(R.string.identity_entry_error_import_failed))
                .assertDoesNotExist()
            assertEquals(listOf(secret), engine.setupBegins)
        } finally {
            release.complete(Unit)
        }
    }

    /** Public keys remain a dedicated validation failure, never a newly created read-only account. */
    @Test fun publicKeyCannotReachAnyNativeLoginOrRecovery() {
        val engine = heldEngine()
        show(signInTestAppState(context, engine))
        enterKey("npub1" + "q".repeat(58))
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.onNodeWithText(context.getString(R.string.sign_in_error_public_key)).assertExists()
        assertTrue(engine.setupBegins.isEmpty())
        assertTrue(engine.logins.isEmpty())
        assertTrue(engine.recoveries.isEmpty())
    }

    /** Both host and full-screen dialog windows protect the secure form. */
    @Test fun secureFlagsCoverHostAndDialog() {
        show(signInTestAppState(context, heldEngine()))
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.runOnIdle {
            val secure = WindowManager.LayoutParams.FLAG_SECURE
            assertTrue(composeRule.activity.window.attributes.flags and secure != 0)
            assertTrue(checkNotNull(ShadowDialog.getLatestDialog().window).attributes.flags and secure != 0)
        }
    }

    /** The real teardown flag blocks a previously captured import callback before its native entry. */
    @Test fun wipeRevokesCapturedNativeEntry() {
        val engine = heldEngine()
        val app = signInTestAppState(context, engine)
        show(app)
        enterKey(secret)
        val click =
            composeRule
                .onNodeWithTag("onboarding.sign_in.action")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            app.wipeInProgress = true
            click()
        }
        composeRule.waitForIdle()
        assertTrue(engine.setupBegins.isEmpty())
        assertTrue(engine.logins.isEmpty())
    }

    /** Fails preflight only after the test releases the actual injected native binding. */
    private fun heldEngine() =
        RecordingIdentityLoginCalls(
            loginFails = { error("unexpected legacy login") },
            beginFails = {
                release.await()
                MarmotKitException.Runtime("controlled preflight failure")
            },
        )

    /** Opens the actual shared Sign In form and fills only its masked native text field. */
    private fun enterKey(value: String) {
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(value)
    }

    /** Models the real caller removing the window on dismissal; no native readiness or account is fabricated. */
    private fun show(app: WhiteNoiseAppState) {
        composeRule.setContent {
            WhiteNoiseTheme {
                if (shown.value) {
                    AddIdentitySheet(app) {
                        dismissals++
                        shown.value = false
                    }
                }
            }
        }
    }
}
