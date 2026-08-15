package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.RecoveryCall
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The consent gate, driven through the real onboarding surface against a
 * counting stand-in for the engine's two login bindings. Which binding a tap
 * reaches is the whole guarantee — the recovering variant rotates key material
 * the engine can't prove was never published — so it is counted here rather
 * than read out of the source text, where a bypass routed through any other
 * wrapper would be invisible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SignInRecoveryConsentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val nsec = "nsec1" + "q".repeat(58)
    private val otherNsec = "nsec1" + "p".repeat(58)

    private fun string(res: Int): String = app.getString(res)

    private fun recoveryRequiredEngine() = recoveryRequiredEngine { MarmotKitException.Runtime("no") }

    private fun recoveryRequiredEngine(recoveryFails: suspend () -> Throwable) =
        RecordingIdentityLoginCalls(
            loginFails = { MarmotKitException.AccountSetupRecoveryRequired() },
            recoveryFails = recoveryFails,
        )

    private fun openSignIn(engine: RecordingIdentityLoginCalls) {
        val appState = signInTestAppState(app, engine)
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingScreen(appState = appState, hasActiveNetwork = { true })
            }
        }
        composeRule.onNodeWithText(string(R.string.onboarding_login)).performClick()
    }

    private fun signIn(key: String) {
        composeRule.onNodeWithText(string(R.string.nostr_nsec)).performTextInput(key)
        composeRule.onNodeWithText(string(R.string.sign_in)).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun theSignInAttemptItselfNeverRecovers() {
        val engine = recoveryRequiredEngine()
        openSignIn(engine)

        signIn(nsec)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_message)).assertExists()
        assertTrue(
            "the prompt must name the orphaned-KeyPackage risk",
            string(R.string.sign_in_recovery_message).contains("orphaned"),
        )
        assertEquals("the prompt alone must reach no recovery", emptyList<RecoveryCall>(), engine.recoveries)
        assertEquals(1, engine.logins.size)
    }

    @Test
    fun confirmingRecoversTheSameKeyExactlyOnceWithTheAcknowledgement() {
        val engine = recoveryRequiredEngine()
        openSignIn(engine)
        signIn(nsec)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()
        composeRule.waitForIdle()

        val ordinaryLogin = engine.logins.single()
        assertEquals(
            listOf(
                RecoveryCall(
                    nsec = nsec,
                    relays = ordinaryLogin.relays,
                    keyPackageRelays = ordinaryLogin.keyPackageRelays,
                    acknowledged = true,
                ),
            ),
            engine.recoveries,
        )
        assertEquals(MarmotClient.bootstrapRelays, ordinaryLogin.relays)
    }

    @Test
    fun aSecondConfirmWhileTheFirstRecoveryIsStillRunningRecoversOnce() {
        val inFlight = CompletableDeferred<Unit>()
        val engine =
            recoveryRequiredEngine {
                inFlight.await()
                MarmotKitException.Runtime("no")
            }
        openSignIn(engine)
        signIn(nsec)

        // Holding the clock keeps the dialog attached, so both confirms reach the
        // same composition — the window a user's second tap actually lands in.
        composeRule.mainClock.autoAdvance = false
        val confirm = composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm))
        confirm.performSemanticsAction(SemanticsActions.OnClick)
        confirm.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, engine.recoveries.size)
        inFlight.complete(Unit)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun decliningRecoversNothingAndSaysSo() {
        val engine = recoveryRequiredEngine()
        openSignIn(engine)
        signIn(nsec)

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals("declining must reach no engine call", emptyList<RecoveryCall>(), engine.recoveries)
        composeRule.onNodeWithText(string(R.string.sign_in_error_setup_recovery_declined)).assertExists()
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertDoesNotExist()
    }

    @Test
    fun anOrdinarySignInFailureNeverAsksForConsentOrRecovers() {
        val engine = RecordingIdentityLoginCalls(loginFails = { MarmotKitException.Runtime("no") })
        openSignIn(engine)

        signIn(nsec)

        assertEquals(emptyList<RecoveryCall>(), engine.recoveries)
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.identity_entry_error_import_failed)).assertExists()
    }

    @Test
    fun aFailedRecoveryReportsItselfWithoutReArmingTheConsentPrompt() {
        val engine = recoveryRequiredEngine { MarmotKitException.AccountSetupRecoveryRequired() }
        openSignIn(engine)
        signIn(nsec)
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.sign_in_error_setup_recovery_failed)).assertExists()
        composeRule.onNodeWithText(string(R.string.sign_in)).performClick()
        composeRule.waitForIdle()

        assertEquals("the second round must not re-prompt", 1, engine.recoveries.size)
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.sign_in_error_setup_recovery_failed)).assertExists()
    }

    @Test
    fun noPostRecoveryMessageClaimsTheAccountWasLeftUntouched() {
        // "Nothing was changed" is provable from a plain sign-in only: after the
        // recovering login has run, the engine may already have applied part of it.
        val untouched = "Nothing was changed"

        assertTrue(string(R.string.sign_in_error_setup_unexpected_state).contains(untouched))
        listOf(
            R.string.sign_in_error_setup_recovery_unexpected_state,
            R.string.sign_in_error_setup_recovery_failed,
        ).forEach { res ->
            assertFalse("post-recovery copy must not promise an untouched account", string(res).contains(untouched))
        }
    }

    @Test
    fun aDifferentKeyStillGetsItsOwnConsentPrompt() {
        val engine = recoveryRequiredEngine { MarmotKitException.AccountSetupRecoveryRequired() }
        openSignIn(engine)
        signIn(nsec)
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(string(R.string.clear)).performClick()
        signIn(otherNsec)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(nsec, otherNsec), engine.recoveries.map { it.nsec })
    }

    @Test
    fun editingTheFieldEndsTheAcknowledgementItBelongedTo() {
        val engine = recoveryRequiredEngine { MarmotKitException.AccountSetupRecoveryRequired() }
        openSignIn(engine)
        signIn(nsec)
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(string(R.string.clear)).performClick()
        signIn(nsec)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertExists()
    }
}
