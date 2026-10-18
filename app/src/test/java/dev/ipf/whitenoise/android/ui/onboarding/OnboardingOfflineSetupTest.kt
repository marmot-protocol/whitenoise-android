package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
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
    fun onlyNewIdentityCreationIsHeldWithoutAnActiveRoute() {
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

        assertEquals(listOf(OnboardingAction.Creating), offline)
        assertEquals(2, starts)
    }

    @Test
    fun retryStartsTheSameActionOnceAfterDefaultRouteReturns() {
        var availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = false)
        var pending: OnboardingAction? = null
        var starts = 0

        fun dispatch(action: OnboardingAction) {
            dispatchOnboardingAction(
                inFlightAction = OnboardingAction.Idle,
                hasActiveNetwork = availability.hasActiveNetwork(),
                requestedAction = action,
                onOffline = { pending = it },
                onStart = {
                    pending = null
                    starts += 1
                },
            )
        }

        dispatch(OnboardingAction.Creating)
        assertEquals(OnboardingAction.Creating, pending)
        assertEquals(0, starts)

        availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = true)
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
    fun explicitCreateStartsWithAnActiveRouteWithoutAnOsValidationVeto() {
        var availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = false)
        var creations = 0
        val native =
            java.lang.reflect.Proxy
                .newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, _ ->
                    check(method.name == "createIdentity") { "Unexpected native call: ${method.name}" }
                    creations++
                    error("Synthetic creation failure")
                } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                initialMarmotRuntime =
                    AppMarmotRuntime(
                        context.cacheDir.resolve("legacy-route-test").path,
                        native,
                    ),
            )
        var pending: OnboardingAction? = null
        fun dispatch() {
            dispatchOnboardingAction(
                inFlightAction = OnboardingAction.Idle,
                hasActiveNetwork = availability.hasActiveNetwork(),
                requestedAction = OnboardingAction.Creating,
                onOffline = { pending = it },
                onStart = {
                    pending = null
                    runBlocking { app.createIdentity() }
                },
            )
        }
        dispatch()
        assertEquals(OnboardingAction.Creating, pending)
        assertEquals(0, creations)
        availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = true)
        dispatch()
        assertEquals(null, pending)
        assertEquals(1, creations)
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

    private companion object {
        val setupActions =
            listOf(
                OnboardingAction.Creating,
                OnboardingAction.Importing,
                OnboardingAction.AmberLogin,
            )
    }
}
