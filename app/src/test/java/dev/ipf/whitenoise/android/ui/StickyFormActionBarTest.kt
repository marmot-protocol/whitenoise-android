package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickyFormActionBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersPrimaryActionInTheStickyImeAwareActionBar() {
        composeRule.setContent {
            WhiteNoiseTheme {
                StickyFormActionBar {
                    Button(onClick = {}) {
                        Text("Save group")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Save group").assertIsDisplayed()
    }
}
