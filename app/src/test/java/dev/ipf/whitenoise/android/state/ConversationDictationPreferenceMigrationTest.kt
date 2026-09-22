package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What happens to someone who had chosen "send when finished" before it was narrowed.
 *
 * #2605 replaced an app-wide delivery default with a choice that only automatic completion reads.
 * The danger is not the settings row moving, it is the value left on disk: the old key was chosen
 * under rules where it also governed completions a person ended by hand, so carrying it forward
 * would send a message nobody asked to send on the first dictation after an ordinary upgrade. The
 * narrowed setting therefore starts from paste and waits to be chosen again.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationDictationPreferenceMigrationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    /** A delivery mode stored under the retired key never becomes the automatic-completion choice. */
    @Test
    fun aStoredSendOnFinishIsNotCarriedIntoTheNewState() {
        preferences().edit().putString(KEY_DELIVERY_MODE, "SendOnFinish").commit()

        val state = ConversationDictationPreferences(context, preferences()).current()

        assertEquals(
            "an upgrade must not inherit a send default chosen under the old, wider rules",
            ConversationDictationDeliveryMode.PasteIntoDraft,
            state.silenceDeliveryMode,
        )
        assertNull("an upgraded install still starts with no silence threshold", state.finishAfterSilenceMillis)
    }

    /** The retired key is cleared on the first write rather than left dormant on disk. */
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

    /** Choosing send for automatic completion persists under the narrowed key, not the retired one. */
    @Test
    fun theNarrowedChoiceRoundTripsUnderItsOwnKey() {
        val stored = ConversationDictationPreferences(context, preferences())

        stored.setSilenceDeliveryMode(ConversationDictationDeliveryMode.SendOnFinish)

        assertEquals(
            ConversationDictationDeliveryMode.SendOnFinish,
            ConversationDictationPreferences(context, preferences()).current().silenceDeliveryMode,
        )
        assertFalse(
            "the narrowed choice must not be written back under the retired key",
            preferences().contains(KEY_DELIVERY_MODE),
        )
    }

    /** An unreadable stored value falls back to paste rather than to sending. */
    @Test
    fun anUnrecognizedStoredValueFallsBackToPaste() {
        preferences().edit().putString(KEY_SILENCE_DELIVERY_MODE, "SomethingElse").commit()

        assertEquals(
            ConversationDictationDeliveryMode.PasteIntoDraft,
            ConversationDictationPreferences(context, preferences()).current().silenceDeliveryMode,
        )
    }

    /** An install that never chose a delivery mode is unaffected. */
    @Test
    fun aFreshInstallIsUnaffected() {
        val state = ConversationDictationPreferences(context, preferences()).current()

        assertNull(state.finishAfterSilenceMillis)
        assertEquals(ConversationDictationDeliveryMode.PasteIntoDraft, state.silenceDeliveryMode)
        assertFalse(preferences().contains(KEY_DELIVERY_MODE))
    }

    private fun preferences(): SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "whitenoise.composer_dictation"
        const val KEY_DELIVERY_MODE = "deliveryMode"
        const val KEY_SILENCE_DELIVERY_MODE = "silenceDeliveryMode"
    }
}
