package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What happens to someone who had chosen "send when finished" before it was retired.
 *
 * #2605 replaced the app-wide delivery default with a choice made per dictation. The danger in that
 * change is not the settings row disappearing, it is the value left on disk: a stored SendOnFinish
 * that still reached a session would send a message the person never asked to send, on the first
 * dictation after an ordinary upgrade. These pin that it cannot.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationDictationPreferenceMigrationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    /** A stored send-on-finish choice is never read back, so no session can inherit it. */
    @Test
    fun aStoredSendOnFinishIsNotCarriedIntoTheNewState() {
        preferences().edit().putString(KEY_DELIVERY_MODE, "SendOnFinish").commit()

        val state = ConversationDictationPreferences(context, preferences()).current()

        // The field is gone from the state entirely; nothing downstream has a delivery mode to act on.
        assertNull("an upgraded install still starts with no silence threshold", state.finishAfterSilenceMillis)
    }

    /** The stored key is cleared on the first write rather than left dormant on disk. */
    @Test
    fun theStoredKeyIsRemovedOnTheFirstWrite() {
        preferences().edit().putString(KEY_DELIVERY_MODE, "SendOnFinish").commit()
        val migrated = ConversationDictationPreferences(context, preferences())

        migrated.setFinishAfterSilenceMillis(5_000L)

        assertFalse(
            "a retired preference must not survive on disk waiting to be read again",
            preferences().contains(KEY_DELIVERY_MODE),
        )
    }

    /** An install that never chose a delivery mode is unaffected. */
    @Test
    fun aFreshInstallIsUnaffected() {
        val state = ConversationDictationPreferences(context, preferences()).current()

        assertNull(state.finishAfterSilenceMillis)
        assertFalse(preferences().contains(KEY_DELIVERY_MODE))
    }

    private fun preferences(): SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "whitenoise.composer_dictation"
        const val KEY_DELIVERY_MODE = "deliveryMode"
    }
}
