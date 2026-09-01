package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConversationDictationPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Clears the dedicated preference file so persisted values cannot leak between cases. */
    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    /** Verifies new installs default to explicit completion and non-sending draft insertion. */
    @Test
    fun defaultsToManualFinishAndPasteIntoDraft() {
        val state = ConversationDictationPreferences(context, preferences()).current()

        assertNull(state.finishAfterSilenceMillis)
        assertEquals(ConversationDictationDeliveryMode.PasteIntoDraft, state.deliveryMode)
    }

    /** Verifies only supported endpointing values persist and send mode requires explicit selection. */
    @Test
    fun persistsOnlySupportedSilenceThresholdsAndExplicitSendMode() {
        val original = ConversationDictationPreferences(context, preferences())
        original.setFinishAfterSilenceMillis(5_000L)
        original.setDeliveryMode(ConversationDictationDeliveryMode.SendOnFinish)

        val restored = ConversationDictationPreferences(context, preferences())
        assertEquals(5_000L, restored.current().finishAfterSilenceMillis)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, restored.current().deliveryMode)

        restored.setFinishAfterSilenceMillis(2_000L)
        assertNull(restored.current().finishAfterSilenceMillis)
    }

    /** Returns the isolated backing store used by this preference contract test. */
    private fun preferences() = context.getSharedPreferences("dictation-test", Context.MODE_PRIVATE)
}
