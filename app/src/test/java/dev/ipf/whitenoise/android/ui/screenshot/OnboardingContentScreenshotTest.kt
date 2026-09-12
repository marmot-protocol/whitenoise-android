package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingContent
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountUi
import dev.ipf.whitenoise.android.ui.onboarding.SignInContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Existing baseline names now capture Welcome, retained accounts and secure Sign In with offline recovery. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class OnboardingContentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingIdleLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingContent(
                        identity = "",
                        creatingIdentity = false,
                        signingInBusy = false,
                        onIdentityChange = {},
                        onCreateIdentity = {},
                        onImportIdentity = {},
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/onboarding_content_idle_light.png")
    }

    @Test
    fun onboardingOfflineLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingContent(
                        identity = "",
                        creatingIdentity = false,
                        signingInBusy = false,
                        onIdentityChange = {},
                        onCreateIdentity = {},
                        onImportIdentity = {},
                        offlineErrorVisible = true,
                        onOfflineRetry = {},
                        amberSignerAvailable = true,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/onboarding_content_offline_light.png")
    }

    @Test
    fun onboardingRetainedAccountLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingContent(
                        identity = "",
                        creatingIdentity = false,
                        signingInBusy = false,
                        onIdentityChange = {},
                        onCreateIdentity = {},
                        onImportIdentity = {},
                        amberSignerAvailable = true,
                        savedAccounts =
                            listOf(
                                OnboardingSavedAccountUi(
                                    label = "amber-account",
                                    accountIdHex = "01".repeat(32),
                                    displayName = "Amber User",
                                    shortIdentity = "npub1amber…user",
                                    avatarUrl = null,
                                ),
                            ),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/onboarding_content_saved_account_light.png")
    }

    @Test
    fun signInOfflineDarkLargeText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SignInContent(
                            identity = "nsec1••••••••••••••••",
                            busy = false,
                            errorRes = null,
                            offlineErrorVisible = true,
                            onOfflineRetry = {},
                            onIdentityChange = {},
                            onErrorChange = {},
                            onBack = {},
                            onSignIn = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/onboarding_sign_in_offline_dark_large.png")
    }
}
