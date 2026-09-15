package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class MuteDurationDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun customWallClockUsesTheDeviceZoneAtConfirmation() {
        val localDate = LocalDate.of(2026, 7, 1)
        val localTime = LocalTime.of(10, 30)

        val newYorkExpiry = customMuteExpiryMillis(localDate, localTime, ZoneId.of("America/New_York"))
        val tokyoExpiry = customMuteExpiryMillis(localDate, localTime, ZoneId.of("Asia/Tokyo"))

        assertEquals(1_782_916_200_000L, newYorkExpiry)
        assertEquals(1_782_869_400_000L, tokyoExpiry)
    }

    @Test
    fun customWallClockUsesZoneRulesAcrossDstTransitions() {
        val newYork = ZoneId.of("America/New_York")

        val springGap = customMuteExpiryMillis(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), newYork)
        val fallOverlap = customMuteExpiryMillis(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), newYork)

        assertEquals(1_772_955_000_000L, springGap)
        assertEquals(1_793_511_000_000L, fallOverlap)
    }

    @Test
    fun customDateSelectionRejectsDatesBeforeToday() {
        val today = LocalDate.of(2026, 8, 10)
        val yesterdayUtcMillis =
            LocalDate
                .of(2026, 8, 9)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertFalse(isDateAllowed(yesterdayUtcMillis, today))
    }

    @Test
    fun customDateSelectionAllowsTodayAndFutureDates() {
        val today = LocalDate.of(2026, 8, 10)
        val todayUtcMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val tomorrowUtcMillis =
            today
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertTrue(isDateAllowed(todayUtcMillis, today))
        assertTrue(isDateAllowed(tomorrowUtcMillis, today))
    }

    /** Durations are listed shortest first, with Always ahead of the optional custom picker. */
    @Test
    fun durationsAreListedInTheDesignedOrderUnderTheMuteForTitle() {
        render()

        composeRule.onNodeWithText(context.getString(R.string.mute_for)).assertExists()
        val tops =
            presetLabels().map { label ->
                composeRule
                    .onNodeWithText(label)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
    }

    /** No confirm button: a duration commits itself, and Cancel is the only way out. */
    @Test
    fun durationDialogOffersCancelAsItsOnlyButton() {
        render()

        composeRule.onNodeWithTag(MUTE_DURATION_DIALOG_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.ok)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.mute)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).assertExists()
    }

    /** A surface without a date/time picker lists presets alone. */
    @Test
    fun customRowIsOmittedWhenNoCustomPickerIsAvailable() {
        render(customTimeAvailable = false)

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.mute_duration_always)).assertExists()
    }

    @Test
    fun oneHourPresetAppliesOnSelection() {
        assertPreset(R.string.mute_duration_1_hour, MuteTarget.After(3_600_000L))
    }

    @Test
    fun eightHourPresetAppliesOnSelection() {
        assertPreset(R.string.mute_duration_8_hours, MuteTarget.After(28_800_000L))
    }

    @Test
    fun oneDayPresetAppliesOnSelection() {
        assertPreset(R.string.mute_duration_1_day, MuteTarget.After(86_400_000L))
    }

    @Test
    fun oneWeekPresetAppliesOnSelection() {
        assertPreset(R.string.mute_duration_1_week, MuteTarget.After(604_800_000L))
    }

    @Test
    fun alwaysAppliesOnSelection() {
        assertPreset(R.string.mute_duration_always, MuteTarget.Always)
    }

    @Test
    fun cancellingDismissesWithoutSelectingADuration() {
        var selected: MuteTarget? = null
        var dismissed = false
        render(onDismiss = { dismissed = true }, onSelect = { selected = it })

        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()

        composeRule.runOnIdle {
            assertNull(selected)
            assertTrue(dismissed)
        }
    }

    @Test
    fun customDateAndTimeApplyTheResolvedInstant() {
        var selected: MuteTarget? = null
        val customDateTime = LocalDateTime.of(2026, 8, 11, 18, 30)
        val selectedDateTime = LocalDateTime.of(2026, 8, 12, 19, 30)
        render(
            nowMillis = { LocalDateTime.of(2026, 8, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() },
            zoneId = { ZoneOffset.UTC },
            initialCustomDateTime = customDateTime,
            onSelect = { selected = it },
        )

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule
            .onNode(hasText("Wednesday, August 12, 2026", substring = true) and hasClickAction())
            .performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CONFIRM_TAG).performClick()
        composeRule.onNode(hasContentDescription("7 o'clock", substring = true)).performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_CONFIRM_TAG).performClick()

        val expectedExpiry = selectedDateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
        composeRule.runOnIdle { assertEquals(MuteTarget.At(expectedExpiry), selected) }
    }

    @Test
    fun cancellingTheCustomDateReturnsToThePresetsWithoutSelecting() {
        var selected: MuteTarget? = null
        render(onSelect = { selected = it })

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CANCEL_TAG).performClick()

        composeRule.runOnIdle { assertNull(selected) }
        composeRule.onNodeWithText(context.getString(R.string.mute_for)).assertExists()
    }

    @Test
    fun currentCustomTimeShowsValidationAndKeepsTheTimePickerOpen() {
        var selected: MuteTarget? = null
        val current = LocalDateTime.of(2026, 8, 10, 12, 0)
        render(
            nowMillis = { current.toInstant(ZoneOffset.UTC).toEpochMilli() },
            zoneId = { ZoneOffset.UTC },
            initialCustomDateTime = current,
            onSelect = { selected = it },
        )

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_CONFIRM_TAG).performClick()

        composeRule.runOnIdle { assertNull(selected) }
        composeRule.onNodeWithText(context.getString(R.string.mute_custom_future_error)).assertExists()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_PICKER_TAG).assertExists()
    }

    /** The prototype's preset order, top to bottom. */
    private fun presetLabels(): List<String> =
        listOf(
            R.string.mute_duration_1_hour,
            R.string.mute_duration_8_hours,
            R.string.mute_duration_1_day,
            R.string.mute_duration_1_week,
            R.string.mute_duration_always,
            R.string.mute_duration_custom,
        ).map(context::getString)

    /** Selecting a preset must both report the target and close the picker in one tap. */
    private fun assertPreset(
        labelId: Int,
        expected: MuteTarget,
    ) {
        var selected: MuteTarget? = null
        render(onSelect = { selected = it })

        composeRule.onNodeWithText(context.getString(labelId)).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(expected, selected) }
    }

    /** Renders the picker with fully injected clock and zone so no assertion depends on the host. */
    @Suppress("LongParameterList")
    private fun render(
        nowMillis: () -> Long = System::currentTimeMillis,
        zoneId: () -> ZoneId = ZoneId::systemDefault,
        initialCustomDateTime: LocalDateTime? = null,
        customTimeAvailable: Boolean = true,
        onDismiss: () -> Unit = {},
        onSelect: (MuteTarget) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MuteDurationDialog(
                    onDismiss = onDismiss,
                    onSelect = onSelect,
                    nowMillis = nowMillis,
                    zoneId = zoneId,
                    initialCustomDateTime = initialCustomDateTime,
                    customTimeAvailable = customTimeAvailable,
                )
            }
        }
    }
}
