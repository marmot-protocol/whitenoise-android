package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sign-in stays available offline; SignUpProfileDraftSubmissionTest covers the real creation route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class OnboardingOfflineSetupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nsec = "nsec1" + "q".repeat(58)

    /** Closing remote-signer pairing returns first-run onboarding to the same sign-in screen. */
    @Test
    fun closingRemoteSignerReturnsToFirstRunSignIn() {
        val engine = RecordingIdentityLoginCalls(loginFails = { error("opening pairing must not import a key") })
        val appState = signInTestAppState(context, engine)
        composeRule.setContent {
            WhiteNoiseTheme { OnboardingScreen(appState, hasActiveNetwork = { true }) }
        }
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.remote_signer").performClick()
        composeRule.onNodeWithTag("remote_signer.screen").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("remote_signer.screen").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").assertExists()
    }

    /** Valid nsec reaches identity acceptance even while offline. */
    @Test
    fun validNsecReachesIdentityAcceptanceEvenWhileOffline() {
        val engine = RecordingIdentityLoginCalls(loginFails = { MarmotKitException.Runtime("expected") })
        val appState = signInTestAppState(context, engine)
        composeRule.setContent {
            WhiteNoiseTheme { OnboardingScreen(appState = appState, hasActiveNetwork = { false }) }
        }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(nsec)
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertDoesNotExist()
        assertEquals(1, engine.logins.size)
        assertEquals(nsec, engine.logins.single().nsec)
    }

    /** Locally invalid key keeps its specific error while offline. */
    @Test
    fun locallyInvalidKeyKeepsItsSpecificErrorWhileOffline() {
        val engine = RecordingIdentityLoginCalls(loginFails = { MarmotKitException.Runtime("unexpected") })
        val appState = signInTestAppState(context, engine)
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingScreen(appState = appState, hasActiveNetwork = { false })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput("not-a-key")
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()

        composeRule.onNodeWithText(context.getString(R.string.identity_entry_error_invalid_key)).assertExists()
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertDoesNotExist()
        assertEquals(0, engine.logins.size)
    }
}
