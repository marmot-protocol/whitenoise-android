package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TtsRatePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun customRateDialogAcceptsDecimalTextInput() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                TtsCustomRateDialog(
                    initialRate = 1.0f,
                    onDismiss = {},
                    onRateSelected = {},
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("1.2")
    }

    @Test
    fun clearedCustomRateShowsInlineAndAccessibilityError() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                TtsCustomRateDialog(
                    initialRate = 1.0f,
                    onDismiss = {},
                    onRateSelected = {},
                )
            }
        }

        val input = composeRule.onNode(hasSetTextAction())
        input.performTextReplacement("")

        val errorMessage = app.getString(R.string.tts_rate_custom_error)
        composeRule.onNodeWithText(errorMessage).assertIsDisplayed()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, errorMessage))
        composeRule.onNodeWithText(app.getString(R.string.tts_rate_apply)).assertIsNotEnabled()
    }
}
