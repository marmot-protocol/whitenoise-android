package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class OnboardingOfflineSetupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nsec = "nsec1" + "q".repeat(58)

    @Test
    fun everySetupActionIsHeldBeforeDispatchWhileOffline() {
        val offline = mutableListOf<OnboardingAction>()
        var starts = 0

        setupActions.forEach { action ->
            dispatchOnboardingAction(
                inFlightAction = OnboardingAction.Idle,
                hasActiveNetwork = false,
                requestedAction = action,
                onOffline = offline::add,
                onStart = { starts += 1 },
            )
        }

        assertEquals(setupActions, offline)
        assertEquals(0, starts)
    }

    @Test
    fun retryStartsTheSameActionOnceAfterNetworkReturns() {
        var online = false
        var pending: OnboardingAction? = null
        var starts = 0

        fun dispatch(action: OnboardingAction) {
            dispatchOnboardingAction(
                inFlightAction = OnboardingAction.Idle,
                hasActiveNetwork = online,
                requestedAction = action,
                onOffline = { pending = it },
                onStart = {
                    pending = null
                    starts += 1
                },
            )
        }

        dispatch(OnboardingAction.Importing)
        assertEquals(OnboardingAction.Importing, pending)
        assertEquals(0, starts)

        online = true
        dispatch(requireNotNull(pending))

        assertEquals(null, pending)
        assertEquals(1, starts)
    }

    @Test
    fun anInFlightActionRejectsRepeatedSetupTaps() {
        setupActions.forEach { requested ->
            dispatchOnboardingAction(
                inFlightAction = OnboardingAction.Creating,
                hasActiveNetwork = true,
                requestedAction = requested,
                onOffline = { error("busy work must not be replaced by an offline prompt") },
                onStart = { error("busy work must not start a second setup call") },
            )
        }
    }

    @Test
    fun nsecInputAndOwningSurfaceSurviveOfflineThenRetryOnline() {
        var online = false
        val engine = RecordingIdentityLoginCalls(loginFails = { MarmotKitException.Runtime("expected") })
        val appState = signInTestAppState(context, engine)
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingScreen(appState = appState, hasActiveNetwork = { online })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nostr_nsec)).performTextInput(nsec)
        composeRule.onNodeWithText(context.getString(R.string.sign_in)).performClick()

        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_offline_setup_message))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals("offline sign-in must reach no engine call", 0, engine.logins.size)

        online = true
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, engine.logins.size)
        assertEquals(nsec, engine.logins.single().nsec)
    }

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
        composeRule.onNodeWithText(context.getString(R.string.nostr_nsec)).performTextInput("not-a-key")
        composeRule.onNodeWithText(context.getString(R.string.sign_in)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.identity_entry_error_invalid_key)).assertExists()
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertDoesNotExist()
        assertEquals(0, engine.logins.size)
    }

    private companion object {
        val setupActions =
            listOf(
                OnboardingAction.Creating,
                OnboardingAction.Importing,
                OnboardingAction.AmberLogin,
            )
    }
}
