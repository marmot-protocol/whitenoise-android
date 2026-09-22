package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.group.disappearingCustomUnits
import dev.ipf.whitenoise.android.ui.group.disappearingPresetSecs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultDisappearingMessagesPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(DefaultDisappearingMessagesPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun missingAndMalformedValuesFailClosedToOff() {
        val account = "alice"
        val key = requireNotNull(DefaultDisappearingMessagesPreferences.preferenceKey(account))
        assertEquals(0L, store().durationFor(account))

        preferences.edit().putString(key, "604800").commit()
        assertEquals(0L, store().durationFor(account))

        preferences.edit().putLong(key, Long.MAX_VALUE).commit()
        assertEquals(0L, store().durationFor(account))
    }

    @Test
    fun valuesSurviveReloadAndStayIsolatedByAccount() {
        val first = store()
        assertTrue(first.setDuration("alice", 604_800L))
        assertTrue(first.setDuration("bob", 3_600L))

        val reloaded = store()
        assertEquals(604_800L, reloaded.durationFor("alice"))
        assertEquals(3_600L, reloaded.durationFor("bob"))
        assertEquals(0L, reloaded.durationFor("carol"))
    }

    @Test
    fun offIsSavedExplicitlyAndInvalidWritesAreRejected() {
        val store = store()
        assertTrue(store.setDuration("alice", 300L))
        assertTrue(store.setDuration("alice", 0L))
        assertTrue(preferences.contains(requireNotNull(DefaultDisappearingMessagesPreferences.preferenceKey("alice"))))
        assertEquals(0L, store.durationFor("alice"))
        assertTrue(store.setDuration("alice", 0L))

        assertFalse(store.setDuration("alice", -1L))
        assertFalse(store.setDuration("alice", 31_536_000L * 11L))
        assertEquals(0L, store.durationFor("alice"))
    }

    @Test
    fun presetsAndBoundedCustomValuesAreAccepted() {
        listOf(30L, 300L, 3_600L, 86_400L, 2_419_200L, 7_776_000L, 59L, 23L * 3_600L, 10L * 31_536_000L)
            .forEach { assertTrue("expected $it to be valid", isValidDisappearingMessageDurationSeconds(it)) }
        listOf(-1L, 61L, 3_660L, 5L * 604_800L, 13L * 2_592_000L)
            .forEach { assertFalse("expected $it to be invalid", isValidDisappearingMessageDurationSeconds(it)) }
    }

    @Test
    fun everyValueEmittedByTheSharedPickerIsAccepted() {
        disappearingPresetSecs.forEach {
            assertTrue("expected preset $it to be valid", isValidDisappearingMessageDurationSeconds(it))
        }
        disappearingCustomUnits.forEach { unit ->
            (1..unit.max).forEach { value ->
                val seconds = value.toLong() * unit.seconds
                assertTrue("expected $value x ${unit.seconds} to be valid", isValidDisappearingMessageDurationSeconds(seconds))
            }
        }
    }

    @Test
    fun destructiveRemovalClearsOnlyTheWipedAccount() {
        val store = store()
        assertTrue(store.setDuration("alice", 604_800L))
        assertTrue(store.setDuration("bob", 3_600L))

        store.removeAccount("alice")

        assertEquals(0L, store.durationFor("alice"))
        assertEquals(3_600L, store.durationFor("bob"))
        assertFalse(preferences.contains(requireNotNull(DefaultDisappearingMessagesPreferences.preferenceKey("alice"))))
    }

    private fun store() = DefaultDisappearingMessagesPreferences(context, preferences)
}
