package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseAppStatePreferencesTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun allowChatScreenshotsDefaultsOn() {
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    @Test
    fun allowChatScreenshotsPersistsRoundTrip() {
        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, true)
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)
        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    @Test
    fun allowChatScreenshotsContextReaderUsesAppPreferences() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)

        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(context))
    }

    @Test
    fun longMessageCollapseDefaultsOnPerGroup() {
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "group-a"))
    }

    @Test
    fun longMessageCollapsePersistsPerGroup() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "group-a", false)

        assertFalse(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "group-b"))
    }

    @Test
    fun longMessageCollapseReenabledReturnsToDefault() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "group-a", false)
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "group-a", true)

        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "group-a"))
    }
}
