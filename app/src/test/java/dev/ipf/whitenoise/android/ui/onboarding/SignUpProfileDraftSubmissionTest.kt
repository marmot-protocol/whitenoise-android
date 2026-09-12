package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests actual form navigation and explicit submission, not source-string wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SignUpProfileDraftSubmissionTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Welcome opens the real form; edits and Back leave no native runtime access or account receipt. */
    @Test fun welcomeToFormAndBackCreatesNothing() {
        var nativeAccess = 0
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                marmotAccessObserver = { nativeAccess++ },
            )
        composeRule.setContent { WhiteNoiseTheme { OnboardingScreen(app, hasValidatedInternet = { true }) } }
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        composeRule.runOnIdle { assertNotNull(app.pendingProfileSignUp) }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Local name")
        composeRule.onNodeWithTag("onboarding.sign_up.about").performTextInput("Local description")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").assertExists()
        composeRule.runOnIdle {
            assertNull(app.pendingProfileSignUp)
            assertEquals("", app.activeAccountRef)
            assertEquals(0, nativeAccess)
        }
    }

    /** Native field text at the explicit tap is published to the one returned account. */
    @Test fun submitUsesCurrentFieldValuesExactlyOnce() {
        var owner = SignUpOwner(1, null)
        var creations = 0
        val publications = mutableListOf<UserProfileMetadataFfi>()
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SignUpController(
                        scope,
                        { owner },
                        { true },
                        create = {
                            creations++
                            AccountSummaryFfi("new", "11".repeat(32), true, false, false, true)
                        },
                        accept = { account, old ->
                            owner = old.copy(accountRef = account.label)
                            true
                        },
                        upload = { _, _ -> error("No photo selected") },
                        publish = { account, value ->
                            assertEquals("new", account)
                            publications += value
                            true
                        },
                        finish = { _, _ -> true },
                    )
                }
            WhiteNoiseTheme { SignUpScreen(controller, { true }, {}) }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Alice")
        composeRule.onNodeWithTag("onboarding.sign_up.about").performTextInput("Current text")
        composeRule.runOnIdle { assertEquals(0, creations) }
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, creations)
            assertEquals("Alice", publications.single().displayName)
            assertEquals("Current text", publications.single().about)
        }
    }

    /** The new form remains editable offline; neither its primary action nor Retry creates an identity. */
    @Test fun offlineSubmitRetainsDraftAndStartsNoNativeOperation() {
        var creations = 0
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SignUpController(
                        scope,
                        { SignUpOwner(1, null) },
                        { true },
                        create = {
                            creations++
                            error("Offline must not create")
                        },
                        accept = { _, _ -> false },
                        upload = { _, _ -> error("Offline must not upload") },
                        publish = { _, _ -> false },
                        finish = { _, _ -> false },
                    )
                }
            WhiteNoiseTheme { SignUpScreen(controller, { false }, {}) }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Saved locally")
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertExists()
        composeRule.runOnIdle { assertEquals(0, creations) }
    }
}
