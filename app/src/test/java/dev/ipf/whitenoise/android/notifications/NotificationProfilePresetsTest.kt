package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/** A new install starts with three ordinary, switched-off profiles, added once and never again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationProfilePresetsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val utc = ZoneId.of("UTC")
    private var now = localMs("2026-11-23T23:30", utc) // A Monday night, inside Sleep's hours.

    private fun fresh(name: String = "notification-profile-presets"): SharedPreferences {
        val mode = Context.MODE_PRIVATE
        return context.getSharedPreferences(name, mode)
    }

    private fun store(preferences: SharedPreferences = fresh()): NotificationProfilePreferences {
        val clock = { now }
        return NotificationProfilePreferences(context, preferences, clock = clock)
    }

    @Test
    fun aFreshInstallStartsWithThreeSwitchedOffPresets() {
        val state = store().state.value
        assertEquals(listOf("Sleep", "Work", "Focus"), state.profiles.map { it.name })
        val (sleep, work, focus) = state.profiles
        assertEquals(profileSchedule("22:00", "07:00", enabled = false), sleep.schedule)
        assertFalse(sleep.allowMentions)
        assertEquals(profileSchedule("09:00", "17:00", WeekdaysOnly, enabled = false), work.schedule)
        assertTrue(work.allowMentions)
        assertNull(focus.schedule)
        assertTrue(focus.allowMentions)
        assertTrue(state.profiles.all { it.allowedChats.isEmpty() })
        assertTrue(state.presetsSeeded)
        assertFalse(state.engaged)
        assertNull(state.manual)
    }

    @Test
    fun presetsHoldNothingBackUntilTheUserTurnsOneOn() {
        val store = store()
        for (time in listOf("2026-11-23T23:30", "2026-11-24T03:00", "2026-11-24T10:00")) {
            assertNull(time, store.active(localMs(time, utc), utc))
        }
        assertNull(nextNotificationProfileTransitionMs(store.state.value, now, utc))
        val work = store.state.value.profiles[1]
        assertTrue(store.save(work.copy(schedule = work.schedule!!.copy(enabled = true))))
        assertEquals("preset-work", store.active(localMs("2026-11-24T10:00", utc), utc)?.profile?.id)
    }

    @Test
    fun presetsAreStoredOnceAndNeverComeBackAfterDeletion() {
        val preferences = fresh()
        val first = store(preferences)
        assertEquals(first.state.value, store(preferences).state.value)
        first.state.value.profiles
            .forEach { assertTrue(first.delete(it.id)) }
        val reopened = store(preferences).state.value
        assertTrue(reopened.profiles.isEmpty())
        assertTrue(reopened.presetsSeeded)
    }

    @Test
    fun unreadableStateIsNeitherSeededNorOverwritten() {
        val corrupt = fresh("notification-profile-presets-corrupt")
        corrupt.edit().putString("state.v1", "{not json").commit()
        assertTrue(
            store(corrupt)
                .state.value.profiles
                .isEmpty(),
        )
        assertEquals("{not json", corrupt.getString("state.v1", null))

        val wrongType = fresh("notification-profile-presets-wrong-type")
        wrongType.edit().putInt("state.v1", 1).commit()
        assertTrue(
            store(wrongType)
                .state.value.profiles
                .isEmpty(),
        )
        assertEquals(1, wrongType.getInt("state.v1", 0))
    }

    @Test
    fun onlyASaveOrATurnOnCountsAsUsingProfiles() {
        val preferences = fresh()
        val store = store(preferences)
        assertTrue(store.turnOff())
        assertTrue(store.delete("preset-work"))
        assertFalse(store.state.value.engaged)
        assertTrue(store.turnOn("preset-focus", null))
        assertTrue(store.state.value.engaged)
        assertTrue(store(preferences).state.value.engaged)

        val saving = store(fresh("notification-profile-presets-save"))
        val sleep = saving.state.value.profiles[0]
        assertTrue(saving.save(sleep.copy(name = "Night")))
        assertTrue(saving.state.value.engaged)
        assertEquals(
            "Night",
            saving.state.value.profiles[0]
                .name,
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun presetNamesAreStoredInTheDeviceLanguage() {
        val names =
            store()
                .state.value.profiles
                .map { it.name }
        assertEquals(listOf("Schlafen", "Arbeit", "Fokus"), names)
    }
}
