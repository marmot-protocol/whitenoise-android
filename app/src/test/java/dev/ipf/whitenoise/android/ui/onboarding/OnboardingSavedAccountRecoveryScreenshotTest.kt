package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class OnboardingSavedAccountRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unreadableCheckpointRequiresExplicitRecoveryAcknowledgement() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.material3.Surface(modifier = Modifier.width(360.dp)) {
                    OnboardingSavedAccountActions(
                        accounts =
                            listOf(
                                OnboardingSavedAccountUi(
                                    label = "account",
                                    accountIdHex = "account-id",
                                    displayName = "Marmot User",
                                    shortIdentity = "npub1marmot",
                                    avatarUrl = null,
                                    recoveryRequired = true,
                                ),
                            ),
                        reactivatingAccountLabel = null,
                        enabled = true,
                        onContinue = {},
                        onRecover = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Recover sign-in setup for Marmot User").performClick()
        composeRule.onNodeWithText("Recover sign-in setup?").assertExists()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/onboarding_saved_account_recovery_confirmation_light.png",
        )
    }
}
