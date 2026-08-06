package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.RecoveryCall
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The add-account sheet has no consent prompt, so it must never reach the
 * recovering login — not even for the engine state that offers recovery on the
 * sign-in screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddIdentitySheetRecoveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val nsec = "nsec1" + "q".repeat(58)

    @Test
    fun addingAnAccountNeverRecoversAnIncompleteSetup() {
        val engine =
            RecordingIdentityLoginCalls(
                loginFails = { MarmotKitException.AccountSetupRecoveryRequired() },
            )
        val appState = signInTestAppState(app, engine)
        composeRule.setContent {
            WhiteNoiseTheme {
                AddIdentitySheet(appState = appState, onDismiss = {})
            }
        }

        val importLabel = app.getString(R.string.import_existing_identity)
        composeRule.onAllNodesWithText(importLabel)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.nsec_or_npub)).performTextInput(nsec)
        composeRule.onAllNodesWithText(importLabel)[1].performClick()
        composeRule.waitForIdle()

        assertEquals(1, engine.logins.size)
        assertEquals(emptyList<RecoveryCall>(), engine.recoveries)
        composeRule.onNodeWithText(app.getString(R.string.sign_in_recovery_title)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.identity_entry_error_import_failed)).assertExists()
    }
}
