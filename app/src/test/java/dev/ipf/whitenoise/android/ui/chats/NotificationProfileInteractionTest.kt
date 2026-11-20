package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.whitenoise.android.notifications.NotificationProfile
import dev.ipf.whitenoise.android.notifications.NotificationProfileSchedule
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.notificationProfiles
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditActions
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditContent
import dev.ipf.whitenoise.android.ui.settings.NotificationProfileEditFormState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/** Notification profile controls act on the moment of the tap and stay read-only while a write is in flight. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class NotificationProfileInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sleep =
        NotificationProfile(
            id = "sleep",
            name = "Sleep",
            allowedChats = emptySet(),
            allowMentions = true,
            schedule = NotificationProfileSchedule(true, 22 * 60, 7 * 60, DayOfWeek.entries.toSet()),
            createdAtMs = 0L,
        )

    @Test
    fun durationsAreTimedWhenTappedNotWhenTheSheetWasDrawn() {
        var now = Instant.parse("2026-11-21T06:59:00Z").toEpochMilli()
        val turnedOn = mutableListOf<Long?>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Column {
                    NotificationProfileQuickSheetContent(
                        profiles = listOf(sleep),
                        active = null,
                        selectedId = sleep.id,
                        actions = NotificationProfileQuickActions({}, { _, until -> turnedOn += until }, {}),
                        zone = ZoneOffset.UTC,
                        tapClock = NotificationProfileTapClock(nowMs = { now }, zone = { ZoneOffset.UTC }),
                    )
                }
            }
        }
        // The sheet stays open across 07:00 before the user taps.
        now = Instant.parse("2026-11-21T07:01:00Z").toEpochMilli()
        composeRule.onNodeWithTag("notificationProfile.untilEnd").performClick()
        composeRule.onNodeWithTag("notificationProfile.forHour").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(Instant.parse("2026-11-22T07:00:00Z").toEpochMilli(), now + 3_600_000L),
                turnedOn,
            )
            assertTrue(turnedOn.all { it != null && it > now })
        }
    }

    @Test
    fun theDraftIsReadOnlyWhileASaveIsInFlight() {
        render(busy = true)
        listOf(
            "notification-profile.name",
            "notification-profile.chats",
            "notification-profile.mentions",
            "notification-profile.schedule",
            "notification-profile.start",
            "notification-profile.day.MONDAY",
            "notification-profile.delete",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun theDraftIsEditableWhenNoWriteIsInFlight() {
        render(busy = false)
        composeRule.onNodeWithTag("notification-profile.mentions").assertIsEnabled()
        composeRule.onNodeWithTag("notification-profile.day.MONDAY").performScrollTo().assertIsEnabled()
    }

    @Test
    fun theChatListButtonAppearsOnlyAfterTheUserSavesOrTurnsOnAProfile() {
        val context = RuntimeEnvironment.getApplication()
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "account-a",
            )
        try {
            val store = app.notificationProfiles
            composeRule.setContent { WhiteNoiseTheme(darkTheme = false) { NotificationProfileQuickAction(app) } }
            assertEquals(3, store.state.value.profiles.size)
            composeRule.onNodeWithTag(NOTIFICATION_PROFILE_QUICK_ACTION_TAG).assertDoesNotExist()
            composeRule.runOnIdle {
                assertTrue(
                    store.save(
                        store.state.value.profiles[0]
                            .copy(name = "Night"),
                    ),
                )
            }
            composeRule.onNodeWithTag(NOTIFICATION_PROFILE_QUICK_ACTION_TAG).assertIsDisplayed()
        } finally {
            app.mutationsScope.cancel()
        }
    }

    private fun render(busy: Boolean) {
        val form =
            NotificationProfileEditFormState(
                isNew = false,
                name = TextFieldState("Sleep"),
                allowedChatCount = 0,
                allowMentions = true,
                accountName = null,
                schedule = sleep.schedule!!,
                canSave = !busy,
                canDelete = true,
                error = null,
                busy = busy,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                NotificationProfileEditContent(form, NotificationProfileEditActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
    }
}
