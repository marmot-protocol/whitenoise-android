package dev.ipf.whitenoise.android.notifications

import android.app.StatusBarManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** The tile mirrors the gate's answer and the chooser marks what is in force. */
class NotificationProfileTileTest {
    private val utc = ZoneId.of("UTC")
    private val now = localMs("2026-11-23T23:30", utc)
    private val sleep = testProfile("sleep", profileSchedule("22:00", "07:00")).copy(emoji = "🌙", name = "Sleep")
    private val focus = testProfile("focus", name = "Focus")
    private val profiles = NotificationProfilesState(listOf(sleep, focus))

    @Test
    fun theTileIsUnavailableOffOrOnLikeTheGate() {
        val none = NotificationProfilesState()
        assertEquals(NotificationProfileTileModel.Unavailable, notificationProfileTileModel(none, true, false, now, utc))
        assertEquals(
            NotificationProfileTileModel.Unavailable,
            notificationProfileTileModel(profiles, false, false, now, utc),
        )
        val outside = localMs("2026-11-23T12:00", utc)
        assertEquals(NotificationProfileTileModel.Off, notificationProfileTileModel(profiles, true, false, outside, utc))
        assertEquals(
            NotificationProfileTileModel.On("🌙 Sleep"),
            notificationProfileTileModel(profiles, true, false, now, utc),
        )
        assertEquals(NotificationProfileTileModel.On(null), notificationProfileTileModel(profiles, true, true, now, utc))
        val manual = profiles.copy(manual = NotificationProfileManual.On("focus", null))
        assertEquals(
            NotificationProfileTileModel.On("Focus"),
            notificationProfileTileModel(manual, true, false, outside, utc),
        )
    }

    @Test
    fun theChooserListsEveryProfileThenOffAndChecksTheOneInForce() {
        val inWindow = notificationProfileTileChoices(profiles, "Off", now, utc)
        assertEquals(listOf("sleep", "focus"), inWindow.profileIds)
        assertEquals(listOf("🌙 Sleep", "Focus", "Off"), inWindow.labels)
        assertEquals(0, inWindow.checked)
        assertEquals(2, notificationProfileTileChoices(profiles, "Off", localMs("2026-11-23T12:00", utc), utc).checked)
    }

    @Test
    fun addTileResultsMapToAddedDeclinedOrFailed() {
        assertEquals(
            NotificationProfileTileAddOutcome.ADDED,
            notificationProfileTileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED),
        )
        assertEquals(
            NotificationProfileTileAddOutcome.ADDED,
            notificationProfileTileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED),
        )
        assertEquals(
            NotificationProfileTileAddOutcome.DECLINED,
            notificationProfileTileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED),
        )
        assertEquals(
            NotificationProfileTileAddOutcome.FAILED,
            notificationProfileTileAddOutcome(StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND),
        )
    }
}
