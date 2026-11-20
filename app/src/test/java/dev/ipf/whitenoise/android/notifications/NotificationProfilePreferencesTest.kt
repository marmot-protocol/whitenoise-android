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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationProfilePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val utc = ZoneId.of("UTC")
    private val defaultName = "notification-profiles-test"
    private var now = localMs("2026-11-20T12:00", utc)

    private fun preferences(name: String = defaultName): SharedPreferences {
        val mode = Context.MODE_PRIVATE
        return context.getSharedPreferences(name, mode)
    }

    private fun store(preferences: SharedPreferences = preferences()) =
        NotificationProfilePreferences(context, preferences, clock = {
            now
        })

    @Test
    fun profilesAndManualChoiceSurviveARoundTrip() {
        val chat = NotificationProfileChat("account-a", "ABCDEF")
        val sleep =
            testProfile("sleep", profileSchedule("22:00", "07:00", WeekdaysOnly), setOf(chat), allowMentions = false)
        val first = store()
        assertTrue(first.save(sleep))
        assertTrue(first.save(testProfile("work")))
        assertTrue(first.turnOn("work", now + 60_000L))
        val reopened = store().state.value
        assertEquals(listOf("sleep", "work"), reopened.profiles.map { it.id })
        assertEquals(
            sleep.copy(allowedChats = setOf(NotificationProfileChat("account-a", "abcdef"))),
            reopened.profiles[0],
        )
        assertEquals(NotificationProfileManual.On("work", now + 60_000L), reopened.manual)
    }

    @Test
    fun corruptStoredStateFailsOpen() {
        val raw = preferences("notification-profiles-corrupt")
        raw.edit().putString("state.v1", "{not json").commit()
        val store = store(raw)
        assertTrue(
            store.state.value.profiles
                .isEmpty(),
        )
        assertFalse(notificationProfileSuppresses(store.active(now, utc)?.profile, profileTestUpdate()))
    }

    @Test
    fun aFailedCommitIsNotPublished() {
        val raw = preferences("notification-profiles-failing")
        val stable = store(raw)
        assertTrue(stable.save(testProfile("kept")))
        val failing =
            store(
                object : SharedPreferences by raw {
                    override fun edit(): SharedPreferences.Editor = FailingEditor(raw.edit())
                },
            )
        assertFalse(failing.save(testProfile("rejected")))
        assertFalse(failing.turnOn("kept", null))
        assertEquals(
            listOf("kept"),
            failing.state.value.profiles
                .map { it.id },
        )
        assertNull(failing.state.value.manual)
    }

    @Test
    fun invalidProfilesAndTheProfileLimitAreRejected() {
        val store = store()
        assertFalse(store.save(testProfile("blank", name = "   ")))
        assertFalse(store.save(testProfile("same", profileSchedule("22:00", "22:00"))))
        assertFalse(store.save(testProfile("nodays", profileSchedule("22:00", "07:00", emptySet()))))
        // A switched-off schedule keeps its times even if they would not run.
        assertTrue(store.save(testProfile("off", profileSchedule("22:00", "07:00", emptySet(), enabled = false))))
        repeat(MAX_NOTIFICATION_PROFILES - 1) { assertTrue(store.save(testProfile("p$it"))) }
        assertFalse(store.save(testProfile("eleventh")))
        assertTrue(store.save(testProfile("p0", name = "Renamed")))
        assertEquals(MAX_NOTIFICATION_PROFILES, store.state.value.profiles.size)
        assertFalse(store.turnOn("missing", null))
        assertFalse(store.turnOn("p1", now))
    }

    @Test
    fun turningOffPausesAnOpenWindowAndOnlyAScheduleChangeEndsThePause() {
        now = localMs("2026-11-20T23:00", utc)
        val store = store()
        val sleep = testProfile("sleep", profileSchedule("22:00", "07:00"))
        assertTrue(store.save(sleep))
        assertEquals("sleep", store.active(now, utc)?.profile?.id)
        assertTrue(store.turnOff(now, utc))
        val paused = NotificationProfileManual.Paused(localMs("2026-11-21T07:00", utc))
        assertEquals(paused, store.state.value.manual)
        assertNull(store.active(now, utc))
        // Renaming the paused profile or saving an unrelated one keeps the paused window quiet.
        assertTrue(store.save(sleep.copy(name = "Night")))
        assertTrue(store.save(testProfile("work")))
        assertEquals(paused, store.state.value.manual)
        assertNull(store.active(now, utc))
        assertTrue(store.save(sleep.copy(name = "Night", schedule = profileSchedule("22:30", "07:00"))))
        assertNull(store.state.value.manual)
        assertEquals("Night", store.active(now, utc)?.profile?.name)
    }

    @Test
    fun turningOffAManualProfileOutsideAnyWindowClearsTheChoice() {
        val store = store()
        assertTrue(store.save(testProfile("focus")))
        assertTrue(store.turnOn("focus", null))
        assertEquals("focus", store.active(now, utc)?.profile?.id)
        assertTrue(store.turnOff(now, utc))
        assertNull(store.state.value.manual)
        assertTrue(store.turnOn("focus", null))
        assertTrue(store.delete("focus"))
        assertNull(store.state.value.manual)
        assertTrue(
            store.state.value.profiles
                .isEmpty(),
        )
    }

    @Test
    fun accountRetentionPrunesOnlyThatAccountsChats() {
        val store = store()
        val kept = NotificationProfileChat("kept", "aa")
        val removed = NotificationProfileChat("removed", "aa")
        assertTrue(store.save(testProfile("sleep", allowedChats = setOf(kept, removed))))
        store.retainAccounts(listOf("kept"))
        assertEquals(
            setOf(kept),
            store.state.value.profiles
                .single()
                .allowedChats,
        )
        assertEquals(
            setOf(kept),
            store()
                .state.value.profiles
                .single()
                .allowedChats,
        )
    }

    /** An unavailable disk: writes are staged but never committed. */
    private class FailingEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = this

        override fun commit(): Boolean = false
    }
}
