package dev.ipf.whitenoise.android

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.state.APP_LANGUAGE_PREFERENCES_NAME
import dev.ipf.whitenoise.android.state.APP_LANGUAGE_TAG_KEY
import dev.ipf.whitenoise.android.state.applyApplicationLanguageTag
import dev.ipf.whitenoise.android.state.persistedApplicationLanguageTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 32)
class AppLanguageTest {
    @Before
    fun resetApplicationLocale() {
        clearPersistedApplicationLanguageTag()
        setApplicationLanguageTag("")
    }

    @After
    fun restoreSystemLocale() {
        clearPersistedApplicationLanguageTag()
        setApplicationLanguageTag("")
    }

    @Test
    fun selectingGermanRecreatesMainActivityWithGermanResources() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            setApplicationLanguageTag("de")

            scenario.onActivity { activity ->
                assertEquals("Darstellung", activity.getString(R.string.appearance))
                assertEquals("Sprache", activity.getString(R.string.language))
            }
        }
    }

    @Test
    fun persistedGermanIsAppliedBeforeMainActivityCreation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        check(
            targetContext
                .getSharedPreferences(APP_LANGUAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(APP_LANGUAGE_TAG_KEY, "de")
                .commit(),
        )
        setApplicationLanguageTag(persistedApplicationLanguageTag(targetContext))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Darstellung", activity.getString(R.string.appearance))
                assertEquals("Sprache", activity.getString(R.string.language))
            }
        }
    }

    private fun setApplicationLanguageTag(tag: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            applyApplicationLanguageTag(tag)
        }
        instrumentation.waitForIdleSync()
    }

    private fun clearPersistedApplicationLanguageTag() {
        check(
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .getSharedPreferences(APP_LANGUAGE_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(APP_LANGUAGE_TAG_KEY)
                .commit(),
        )
    }
}
