package dev.ipf.whitenoise.android.ui.group

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class DisappearingMessagesPickerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun presetListShowsThreeMonthsAboveFourWeeks() {
        render(currentSecs = 0L)

        composeRule.onNodeWithText(context.getString(R.string.disappearing_90_days)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.disappearing_4_weeks)).assertIsDisplayed()
    }

    @Test
    fun saveSubmitsStagedSelectionWithoutChangingUntilSave() {
        var picked: Long? = null
        render(currentSecs = 604_800L, onPick = { picked = it })

        composeRule.onNodeWithText(context.getString(R.string.disappearing_90_days)).performClick()
        composeRule.runOnIdle { assertNull(picked) }

        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.runOnIdle { assertEquals(7_776_000L, picked) }
    }

    @Test
    fun cancellingCustomDialogDoesNotChangeStagedPresetSelection() {
        var picked: Long? = null
        render(currentSecs = 604_800L, onPick = { picked = it })

        composeRule.onNodeWithText(context.getString(R.string.disappearing_custom)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.runOnIdle { assertEquals(604_800L, picked) }
    }

    @Test
    fun cancellingCustomDialogPreservesStagedCustomSelection() {
        var picked: Long? = null
        render(currentSecs = 2_592_000L, onPick = { picked = it })

        composeRule.onNodeWithText(context.getString(R.string.disappearing_custom)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.runOnIdle { assertEquals(2_592_000L, picked) }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun compactLargeFontKeepsPresetsSaveAndCustomReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme {
                    DisappearingMessagesPickerDialog(
                        currentSecs = 0L,
                        onDismiss = {},
                        onPick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.save)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.disappearing_custom)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.disappearing_90_days)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.disappearing_off)).assertIsDisplayed()
    }

    @Test
    fun twelveMonthLabelUsesPluralMonths() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.material3.Text(disappearingMessagesLabel(31_104_000L))
            }
        }
        composeRule.onNodeWithText("12 months").assertIsDisplayed()
    }

    @Test
    fun oneMonthLabelUsesSingularMonth() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.material3.Text(disappearingMessagesLabel(2_592_000L))
            }
        }
        composeRule.onNodeWithText("1 month").assertIsDisplayed()
    }

    @Test
    fun oneYearLabelUsesSingularYear() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.material3.Text(disappearingMessagesLabel(31_536_000L))
            }
        }
        composeRule.onNodeWithText("1 year").assertIsDisplayed()
    }

    @Test
    fun tenYearLabelUsesPluralYears() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.material3.Text(disappearingMessagesLabel(315_360_000L))
            }
        }
        composeRule.onNodeWithText("10 years").assertIsDisplayed()
    }

    private fun render(
        currentSecs: Long,
        onPick: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                DisappearingMessagesPickerDialog(
                    currentSecs = currentSecs,
                    onDismiss = {},
                    onPick = onPick,
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "fr")
class DisappearingMessagesPickerFrenchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun threeMonthPresetUsesLocalizedLabel() {
        composeRule.setContent {
            WhiteNoiseTheme {
                DisappearingMessagesPickerDialog(
                    currentSecs = 0L,
                    onDismiss = {},
                    onPick = {},
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.disappearing_90_days)).assertIsDisplayed()
    }

    @Test
    fun monthAndYearLabelsUseLocalizedPlurals() {
        composeRule.setContent {
            WhiteNoiseTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(disappearingMessagesLabel(2_592_000L))
                    androidx.compose.material3.Text(disappearingMessagesLabel(31_536_000L))
                    androidx.compose.material3.Text(disappearingMessagesLabel(315_360_000L))
                }
            }
        }
        composeRule.onNodeWithText("1 mois").assertIsDisplayed()
        composeRule.onNodeWithText("1 an").assertIsDisplayed()
        composeRule.onNodeWithText("10 ans").assertIsDisplayed()
    }
}
