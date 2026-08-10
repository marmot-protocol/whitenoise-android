package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
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
}
