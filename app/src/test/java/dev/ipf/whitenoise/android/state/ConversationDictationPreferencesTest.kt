package dev.ipf.whitenoise.android.state

import android.content.ComponentName
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
        assertNull(state.recognitionServiceOverride)
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

    /** Keeps an explicit provider choice local and clears it for the System default option. */
    @Test
    fun persistsAndClearsRecognitionServiceOverride() {
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = "org.offline"
                versionCode = 7
                applicationInfo =
                    android.content.pm
                        .ApplicationInfo()
                        .apply { packageName = "org.offline" }
            },
        )
        val selected = ComponentName("org.offline", "org.offline.Recognition")
        val original = ConversationDictationPreferences(context, preferences())

        original.setRecognitionServiceOverride(selected)

        assertEquals(
            selected,
            ConversationDictationPreferences(context, preferences()).current().recognitionServiceOverride,
        )

        original.setRecognitionServiceOverride(null)

        assertNull(ConversationDictationPreferences(context, preferences()).current().recognitionServiceOverride)
    }

    @Test
    fun rejectsVersionlessAndMalformedProviderRecords() {
        preferences().edit().putString("providerSelection", "{\"package\":\"org.offline\"}").commit()
        assertNull(ConversationDictationPreferences(context, preferences()).current().providerSelection)
        preferences().edit().putString("providerSelection", "broken").commit()
        assertNull(ConversationDictationPreferences(context, preferences()).current().providerSelection)
    }

    @Test
    fun exactActivitySelectionRoundTripsWithVersionAndSurfaces() {
        val choice =
            dev.ipf.whitenoise.android.audio.ConversationDictationProviderChoice(
                packageName = "org.offline",
                versionCode = 42,
                appName = "Offline",
                engineName = "Window",
                activity = ComponentName("org.offline", "org.offline.Window"),
            )
        val original = ConversationDictationPreferences(context, preferences())
        original.setProviderSelection(choice)
        assertEquals(choice, ConversationDictationPreferences(context, preferences()).current().providerSelection)
        original.setProviderSelection(null)
        assertNull(ConversationDictationPreferences(context, preferences()).current().providerSelection)
    }

    /** Returns the isolated backing store used by this preference contract test. */
    private fun preferences() = context.getSharedPreferences("dictation-test", Context.MODE_PRIVATE)
}
