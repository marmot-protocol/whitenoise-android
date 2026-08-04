package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationVibrationPatternDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun pickerShowsEveryChoiceAndAccessiblePreviewActions() {
        render()

        ConversationVibrationPattern.entries.forEach { pattern ->
            val label = label(pattern)
            composeRule.onNodeWithText(label).assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.preview_vibration_pattern, label))
                .assertIsDisplayed()
        }
    }

    @Test
    fun saveReturnsTheNewSelection() {
        var selected: ConversationVibrationPattern? = null
        render { selected = it }

        composeRule.onNodeWithText(label(ConversationVibrationPattern.DOUBLE)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()

        composeRule.runOnIdle { assertEquals(ConversationVibrationPattern.DOUBLE, selected) }
    }

    private fun render(onSelect: (ConversationVibrationPattern) -> Unit = {}) {
        composeRule.setContent {
            WhiteNoiseTheme {
                VibrationPatternDialog(
                    currentPattern = ConversationVibrationPattern.SHORT,
                    onDismiss = {},
                    onSelect = onSelect,
                )
            }
        }
    }

    private fun label(pattern: ConversationVibrationPattern): String =
        context.getString(
            when (pattern) {
                ConversationVibrationPattern.SYSTEM_DEFAULT -> R.string.vibration_pattern_system_default
                ConversationVibrationPattern.SHORT -> R.string.vibration_pattern_short
                ConversationVibrationPattern.DOUBLE -> R.string.vibration_pattern_double
                ConversationVibrationPattern.LONG -> R.string.vibration_pattern_long
            },
        )
}
