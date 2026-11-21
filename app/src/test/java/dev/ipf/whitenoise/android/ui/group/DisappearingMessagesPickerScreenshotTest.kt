package dev.ipf.whitenoise.android.ui.group

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The green action colour from the quick-swatch palette, used for the dark baselines. */
private const val GREEN_ACCENT_ARGB = 0xFF15803DL

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xhdpi")
class DisappearingMessagesPickerScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Every preset, Off and Custom fit in one screen without scrolling; this is the baseline for the sheet. */
    @Test
    fun everyDurationIsVisibleAtOnce() {
        render(currentSecs = 604_800L)

        (disappearingGridSecs + 0L).forEach { secs ->
            composeRule.onNodeWithTag(disappearingPresetTag(secs)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(disappearingPresetTag(604_800L)).assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.disappearing_explainer)).assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_picker.png")
    }

    @Test
    fun darkGreenSheetBaseline() {
        render(currentSecs = 604_800L, dark = true)
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_sheet_dark_green.png")
    }

    @Test
    fun amoledSheetBaseline() {
        render(currentSecs = 0L, dark = true, amoled = true)
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_sheet_amoled.png")
    }

    /** A pick that keeps history applies on the tap itself: no Save, no second dialog. */
    @Test
    fun safePickAppliesOnTap() {
        val events = mutableListOf<String>()
        render(
            currentSecs = 3_600L,
            onPick = { events += "pick:$it" },
            onDismiss = { events += "dismiss" },
        )

        composeRule.onNodeWithTag(disappearingPresetTag(604_800L)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(listOf("pick:604800", "dismiss"), events) }
    }

    /** Tapping the current value just closes the sheet without a mutation. */
    @Test
    fun unchangedPickOnlyCloses() {
        val events = mutableListOf<String>()
        render(currentSecs = 3_600L, onPick = { events += "pick:$it" }, onDismiss = { events += "dismiss" })

        composeRule.onNodeWithTag(disappearingPresetTag(3_600L)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(listOf("dismiss"), events) }
    }

    /** A pick that removes history expands an inline warning; nothing applies until it is confirmed. */
    @Test
    fun pruningPickConfirmsInline() {
        var picked: Long? = null
        render(currentSecs = 0L, dark = true, onPick = { picked = it }, requiresConfirmation = { it > 0L })

        composeRule.onNodeWithTag(disappearingPresetTag(300L)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DISAPPEARING_CONFIRM_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(disappearingPresetTag(0L)).assertIsSelected()
        assertNull(picked)
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_sheet_confirm_dark_green.png")

        composeRule.onNodeWithTag(DISAPPEARING_CONFIRM_ACTION_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil { composeRule.runOnUiThread { picked != null } }
        composeRule.runOnIdle { assertEquals(300L, picked) }
    }

    /** Cancelling the warning restores the current selection and keeps the sheet open. */
    @Test
    fun cancellingInlineConfirmKeepsCurrentTimer() {
        var picked: Long? = null
        var dismissed = false
        render(
            currentSecs = 86_400L,
            onPick = { picked = it },
            onDismiss = { dismissed = true },
            requiresConfirmation = { it in 1L until 86_400L },
        )

        composeRule.onNodeWithTag(disappearingPresetTag(30L)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(disappearingPresetTag(86_400L)).assertIsSelected()
        composeRule.onNodeWithTag(DISAPPEARING_CONFIRM_TAG).assertDoesNotExist()
        assertNull(picked)
        assertTrue(!dismissed)
    }

    /** Custom opens a second page in the same sheet; unit, stepper and Set produce the exact duration. */
    @Test
    fun customPageComposesUnitAndValue() {
        var picked: Long? = null
        render(currentSecs = 0L, dark = true, onPick = { picked = it })

        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_ACTION_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(disappearingUnitTag(unitIndex(DAY_SECONDS))).performClick()
        repeat(2) { composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_INCREASE_TAG).performClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 days").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_sheet_custom_dark_green.png")

        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_SET_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(3L * DAY_SECONDS, picked) }
    }

    /** Switching to a smaller unit clamps the value into that unit's range. */
    @Test
    fun customUnitSwitchClampsValue() {
        var picked: Long? = null
        render(currentSecs = 31_104_000L, onPick = { picked = it })

        composeRule.onNodeWithText("12 months").assertIsDisplayed()
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_ACTION_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(disappearingUnitTag(unitIndex(WEEK_SECONDS))).performClick()
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_SET_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(4L * WEEK_SECONDS, picked) }
    }

    /** Back from Custom returns to the presets without picking anything. */
    @Test
    fun customBackReturnsToPresets() {
        var picked: Long? = null
        render(currentSecs = 3_600L, onPick = { picked = it })

        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_ACTION_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("1 hour").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(disappearingPresetTag(3_600L)).assertIsSelected()
        assertNull(picked)
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w320dp-h780dp-xhdpi")
    fun compactLargeFontRtlKeepsEveryChoiceReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, accentColorArgb = GREEN_ACCENT_ARGB, fontScale = 1.6f) {
                    DisappearingMessagesPickerSheet(currentSecs = 172_800L, onDismiss = {}, onPick = {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(disappearingPresetTag(0L)).assertIsDisplayed()
        composeRule.onNodeWithTag(DISAPPEARING_CUSTOM_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("2 days").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/disappearing_messages_sheet_dark_large_rtl.png")
    }

    @Test
    fun twelveMonthLabelUsesPluralMonths() = assertLabel(31_104_000L, "12 months")

    @Test
    fun oneMonthLabelUsesSingularMonth() = assertLabel(2_592_000L, "1 month")

    @Test
    fun oneYearLabelUsesSingularYear() = assertLabel(31_536_000L, "1 year")

    @Test
    fun tenYearLabelUsesPluralYears() = assertLabel(315_360_000L, "10 years")

    private fun assertLabel(
        secs: Long,
        expected: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { androidx.compose.material3.Text(disappearingMessagesLabel(secs)) }
        }
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun unitIndex(seconds: Long): Int = disappearingCustomUnits.indexOfFirst { it.seconds == seconds }

    @Suppress("LongParameterList")
    private fun render(
        currentSecs: Long,
        dark: Boolean = false,
        amoled: Boolean = false,
        onPick: (Long) -> Unit = {},
        onDismiss: () -> Unit = {},
        requiresConfirmation: (Long) -> Boolean = { false },
    ) {
        composeRule.setContent {
            Themed(dark, amoled) {
                DisappearingMessagesPickerSheet(
                    currentSecs = currentSecs,
                    onDismiss = onDismiss,
                    onPick = onPick,
                    requiresConfirmation = requiresConfirmation,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Themed(
        dark: Boolean,
        amoled: Boolean,
        content: @Composable () -> Unit,
    ) {
        WhiteNoiseTheme(
            darkTheme = dark,
            amoled = amoled,
            accentColorArgb = if (dark && !amoled) GREEN_ACCENT_ARGB else null,
            content = content,
        )
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val WEEK_SECONDS = 604_800L
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
                DisappearingMessagesPickerSheet(currentSecs = 0L, onDismiss = {}, onPick = {})
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
