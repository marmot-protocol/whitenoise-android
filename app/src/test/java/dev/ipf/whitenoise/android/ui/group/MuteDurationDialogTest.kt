package dev.ipf.whitenoise.android.ui.group

import android.text.format.DateUtils
import androidx.compose.ui.test.assertTextEquals
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

    @Test
    fun pickerShowsEveryRequestedChoiceAndExplicitAlwaysOption() {
        render()

        listOf(
            R.string.mute_duration_1_hour,
            R.string.mute_duration_8_hours,
            R.string.mute_duration_1_day,
            R.string.mute_duration_7_days,
            R.string.mute_duration_custom,
            R.string.mute_duration_always,
        ).forEach { label ->
            composeRule.onNodeWithText(context.getString(label)).assertExists()
        }
    }

    @Test
    fun oneHourPresetUsesElapsedDuration() {
        assertPreset(R.string.mute_duration_1_hour, 3_600_000L)
    }

    @Test
    fun eightHourPresetUsesElapsedDuration() {
        assertPreset(R.string.mute_duration_8_hours, 28_800_000L)
    }

    @Test
    fun oneDayPresetIsStagedUntilConfirmation() {
        assertPreset(R.string.mute_duration_1_day, 86_400_000L, assertStaged = true)
    }

    @Test
    fun sevenDayPresetUsesElapsedDuration() {
        assertPreset(R.string.mute_duration_7_days, 604_800_000L)
    }

    @Test
    fun alwaysIsAnExplicitConfirmationTarget() {
        var confirmed: MuteTarget? = null
        render(onConfirm = { confirmed = it })

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_always)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()

        composeRule.runOnIdle { assertEquals(MuteTarget.Always, confirmed) }
    }

    @Test
    fun cancellingDurationDialogDoesNotConfirmTheSelection() {
        var confirmed: MuteTarget? = null
        render(onConfirm = { confirmed = it })

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_1_hour)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertNull(confirmed) }
    }

    @Test
    fun customDateAndTimeReturnToConfirmationWithTheResolvedInstant() {
        var confirmed: MuteTarget? = null
        val customDateTime = LocalDateTime.of(2026, 8, 11, 18, 30)
        val selectedDateTime = LocalDateTime.of(2026, 8, 12, 19, 30)
        render(
            nowMillis = { LocalDateTime.of(2026, 8, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() },
            zoneId = { ZoneOffset.UTC },
            initialCustomDateTime = customDateTime,
            onConfirm = { confirmed = it },
        )

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule
            .onNode(hasText("Wednesday, August 12, 2026", substring = true) and hasClickAction())
            .performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CONFIRM_TAG).performClick()
        composeRule.onNode(hasContentDescription("7 o'clock", substring = true)).performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_CONFIRM_TAG).performClick()

        val expectedExpiry = selectedDateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
        val expectedPreview =
            context.getString(
                R.string.mute_custom_selected,
                DateUtils.formatDateTime(
                    context,
                    expectedExpiry,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                ),
            )
        composeRule.onNodeWithTag(MUTE_CUSTOM_PREVIEW_TAG).assertTextEquals(expectedPreview)
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()
        composeRule.runOnIdle {
            assertEquals(MuteTarget.At(expectedExpiry), confirmed)
        }
    }

    @Test
    fun elapsedCustomTimeIsRejectedAtFinalConfirmation() {
        var confirmed: MuteTarget? = null
        val customDateTime = LocalDateTime.of(2026, 8, 10, 12, 1)
        var now = LocalDateTime.of(2026, 8, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        render(
            nowMillis = { now },
            zoneId = { ZoneOffset.UTC },
            initialCustomDateTime = customDateTime,
            onConfirm = { confirmed = it },
        )

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_CONFIRM_TAG).performClick()

        now = customDateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()

        composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(context.getString(R.string.mute_custom_future_error)).assertExists()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_PICKER_TAG).assertExists()
    }

    @Test
    fun cancellingTheCustomDateReturnsWithoutChangingTheSelection() {
        var confirmed: MuteTarget? = null
        render(onConfirm = { confirmed = it })

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_1_day)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CANCEL_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()

        composeRule.runOnIdle { assertEquals(MuteTarget.After(86_400_000L), confirmed) }
    }

    @Test
    fun currentCustomTimeShowsValidationAndKeepsTheTimePickerOpen() {
        val current = LocalDateTime.of(2026, 8, 10, 12, 0)
        render(
            nowMillis = { current.toInstant(ZoneOffset.UTC).toEpochMilli() },
            zoneId = { ZoneOffset.UTC },
            initialCustomDateTime = current,
        )

        composeRule.onNodeWithText(context.getString(R.string.mute_duration_custom)).performScrollTo().performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_DATE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_CONFIRM_TAG).performClick()

        composeRule.onNodeWithText(context.getString(R.string.mute_custom_future_error)).assertExists()
        composeRule.onNodeWithTag(MUTE_CUSTOM_TIME_PICKER_TAG).assertExists()
    }

    private fun assertPreset(
        labelId: Int,
        expectedDurationMillis: Long,
        assertStaged: Boolean = false,
    ) {
        var confirmed: MuteTarget? = null
        render(onConfirm = { confirmed = it })

        composeRule.onNodeWithText(context.getString(labelId)).performScrollTo().performClick()
        if (assertStaged) composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(context.getString(R.string.ok)).performClick()

        composeRule.runOnIdle { assertEquals(MuteTarget.After(expectedDurationMillis), confirmed) }
    }

    private fun render(
        nowMillis: () -> Long = System::currentTimeMillis,
        zoneId: () -> ZoneId = ZoneId::systemDefault,
        initialCustomDateTime: LocalDateTime? = null,
        onConfirm: (MuteTarget) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MuteDurationDialog(
                    onDismiss = {},
                    onConfirm = onConfirm,
                    nowMillis = nowMillis,
                    zoneId = zoneId,
                    initialCustomDateTime = initialCustomDateTime,
                )
            }
        }
    }
}
