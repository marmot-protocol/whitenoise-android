package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.ActiveNotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.notifications.NotificationProfileSource
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileBanner
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileQuickActions
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileQuickSheetContent
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileTapClock
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditActions
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditContent
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditFormState
import dev.ipf.whitenoise.android.ui.settings.NotificationProfilesContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/** Notification profiles (#3601): the list, the editor, the chat-list quick sheet and the active banner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class NotificationProfilesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-20T23:15:00Z").toEpochMilli()
    private val weekdays =
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val sleep = profile("sleep", "Sleep", NotificationProfileSchedule(true, 22 * 60, 7 * 60, weekdays))
    private val work = profile("work", "Work", NotificationProfileSchedule(true, 9 * 60, 17 * 60 + 30, weekdays))
    private val focus = profile("focus", "Focus", null)
    private val longName =
        profile(
            "long",
            "Family dinner and evening reading time",
            NotificationProfileSchedule(
                true,
                18 * 60,
                21 * 60,
                setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY),
            ),
        )
    private val active =
        ActiveNotificationProfile(
            sleep,
            NotificationProfileSource.SCHEDULE,
            Instant.parse("2026-11-21T07:00:00Z").toEpochMilli(),
        )

    @Test fun listEmptyLight() = captureList("notification_profiles_empty_light", emptyList(), Theme.Light)

    @Test fun listEmptyDark() = captureList("notification_profiles_empty_dark", emptyList(), Theme.Dark)

    @Test fun listEmptyAmoled() = captureList("notification_profiles_empty_amoled", emptyList(), Theme.Amoled)

    @Test fun listEmptyRtlLargeFont() {
        captureList("notification_profiles_empty_rtl_large_font", emptyList(), Theme.LightRtlLarge)
    }

    @Test fun listLight() = captureList("notification_profiles_list_light", allProfiles(), Theme.Light, active)

    @Test fun listDark() = captureList("notification_profiles_list_dark", allProfiles(), Theme.Dark, active)

    @Test fun listAmoled() = captureList("notification_profiles_list_amoled", allProfiles(), Theme.Amoled, active)

    @Test fun listRtlLargeFont() {
        captureList("notification_profiles_list_rtl_large_font", allProfiles(), Theme.LightRtlLarge, active)
    }

    @Test fun editorNewLight() = captureEditor("notification_profile_editor_new_light", newForm(), Theme.Light)

    @Test fun editorNewAmoled() = captureEditor("notification_profile_editor_new_amoled", newForm(), Theme.Amoled)

    @Test fun editorScheduleLight() {
        captureEditor(
            "notification_profile_editor_schedule_light",
            scheduleForm(),
            Theme.Light,
        )
    }

    @Test fun editorScheduleDark() {
        captureEditor(
            "notification_profile_editor_schedule_dark",
            scheduleForm(),
            Theme.Dark,
        )
    }

    @Test fun editorScheduleAmoled() {
        captureEditor("notification_profile_editor_schedule_amoled", scheduleForm(), Theme.Amoled)
    }

    @Test
    fun editorScheduleRtlLargeFont() {
        captureEditor("notification_profile_editor_schedule_rtl_large_font", scheduleForm(), Theme.LightRtlLarge)
    }

    @Test
    fun editorNoDaysAmoled() =
        captureEditor(
            "notification_profile_editor_no_days_amoled",
            scheduleForm().let { it.copy(schedule = it.schedule.copy(days = emptySet()), canSave = false) },
            Theme.Amoled,
        )

    @Test fun quickSheetLight() = captureSheet("notification_profile_quick_sheet_light", null, Theme.Light)

    @Test fun quickSheetAmoled() = captureSheet("notification_profile_quick_sheet_amoled", null, Theme.Amoled)

    @Test fun quickSheetRtlLargeFont() {
        captureSheet("notification_profile_quick_sheet_rtl_large_font", null, Theme.LightRtlLarge)
    }

    @Test fun quickSheetActiveAmoled() {
        captureSheet(
            "notification_profile_quick_sheet_active_amoled",
            active,
            Theme.Amoled,
        )
    }

    @Test fun bannerLight() = captureBanner("notification_profile_banner_light", Theme.Light)

    @Test fun bannerDark() = captureBanner("notification_profile_banner_dark", Theme.Dark)

    @Test fun bannerAmoled() = captureBanner("notification_profile_banner_amoled", Theme.Amoled)

    @Test fun bannerRtlLargeFont() = captureBanner("notification_profile_banner_rtl_large_font", Theme.LightRtlLarge)

    private fun allProfiles() = listOf(sleep, work, focus, longName)

    private fun newForm() =
        NotificationProfileEditFormState(
            isNew = true,
            name = TextFieldState(),
            allowedChatCount = 0,
            allowMentions = true,
            accountName = null,
            schedule = NotificationProfileSchedule.Default.copy(enabled = false),
            canSave = false,
            canDelete = false,
            error = null,
        )

    private fun scheduleForm() =
        NotificationProfileEditFormState(
            isNew = false,
            name = TextFieldState("Sleep"),
            allowedChatCount = 2,
            allowMentions = true,
            accountName = "Alice",
            schedule = sleep.schedule!!,
            canSave = true,
            canDelete = true,
            error = null,
        )

    private fun captureList(
        name: String,
        profiles: List<NotificationProfile>,
        theme: Theme,
        active: ActiveNotificationProfile? = null,
    ) {
        render(theme) { NotificationProfilesContent(profiles, active, {}, {}, {}, {}, zone) }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun captureEditor(
        name: String,
        form: NotificationProfileEditFormState,
        theme: Theme,
    ) {
        val actions = NotificationProfileEditActions({}, {}, {}, {}, {}, {}, {}, {}, {})
        render(theme) { NotificationProfileEditContent(form, actions) }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun captureSheet(
        name: String,
        active: ActiveNotificationProfile?,
        theme: Theme,
    ) {
        render(theme) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth().testTag(SHEET_TAG),
            ) {
                Column(Modifier.padding(top = 24.dp)) {
                    NotificationProfileQuickSheetContent(
                        profiles = allProfiles(),
                        active = active,
                        selectedId = sleep.id,
                        actions = NotificationProfileQuickActions({}, { _, _ -> }, {}),
                        zone = zone,
                        tapClock = NotificationProfileTapClock(nowMs = { now }, zone = { zone }),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(SHEET_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun captureBanner(
        name: String,
        theme: Theme,
    ) {
        render(theme) {
            Surface(Modifier.fillMaxWidth().testTag(BANNER_TAG), color = MaterialTheme.colorScheme.surface) {
                Column {
                    NotificationProfileBanner(active = active, onClick = {}, zone = zone)
                    NotificationProfileBanner(
                        active =
                            ActiveNotificationProfile(
                                longName.copy(allowMentions = false),
                                NotificationProfileSource.MANUAL,
                                null,
                            ),
                        onClick = {},
                        zone = zone,
                    )
                }
            }
        }
        composeRule.onNodeWithTag(BANNER_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun render(
        theme: Theme,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (theme == Theme.LightRtlLarge) 2f else 1f),
                LocalLayoutDirection provides
                    if (theme == Theme.LightRtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = theme == Theme.Dark || theme == Theme.Amoled,
                    amoled = theme == Theme.Amoled,
                ) {
                    content()
                }
            }
        }
    }

    private enum class Theme { Light, Dark, Amoled, LightRtlLarge }

    private companion object {
        const val SHEET_TAG = "screenshot.notificationProfileSheet"
        const val BANNER_TAG = "screenshot.notificationProfileBanner"

        fun profile(
            id: String,
            name: String,
            schedule: NotificationProfileSchedule?,
        ) = NotificationProfile(id, name, emptySet(), allowMentions = true, schedule = schedule, createdAtMs = 0L)
    }
}
