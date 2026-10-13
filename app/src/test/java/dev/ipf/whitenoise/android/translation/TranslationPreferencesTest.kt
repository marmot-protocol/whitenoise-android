package dev.ipf.whitenoise.android.translation

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test fun defaultsAreOffAndInherit() {
        val preferences = TranslationPreferences(context)
        assertFalse(preferences.global().enabled)
        assertEquals(TranslationOverride.INHERIT, preferences.override("account", "group"))
        assertNull(preferences.targetOverride("account", "group"))
        preferences.close()
    }

    @Test fun overridesNeverLeakAcrossAccountsOrChatsAndInheritFollowsGlobalChanges() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(true, "de")
        preferences.setOverride("a", "group", TranslationOverride.OFF, "fr")
        assertFalse(preferences.effective("a", "group").enabled)
        assertEquals("fr", preferences.effective("a", "group").target)
        assertEquals(TranslationChoice(true, "de"), preferences.effective("b", "group"))
        assertEquals(TranslationChoice(true, "de"), preferences.effective("a", "other"))
        preferences.setGlobal(false, "es")
        assertEquals(TranslationChoice(false, "es"), preferences.effective("b", "group"))
        preferences.setOverride("a", "group", TranslationOverride.INHERIT, null)
        assertEquals(TranslationChoice(false, "es"), preferences.effective("a", "group"))
        preferences.close()
    }

    @Test fun corruptGlobalAndOverrideValuesFailClosed() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("global", "not-a-boolean")
            .putString("target", "invalid target")
            .putString("chat:1:a:group", "unknown")
            .commit()
        val preferences = TranslationPreferences(context)
        assertFalse(preferences.global().enabled)
        assertEquals("en", preferences.global().target)
        assertEquals(TranslationOverride.INHERIT, preferences.override("a", "group"))
        preferences.close()
    }

    @Test fun storedChoicesContainNoRenditionOrRequestData() {
        val preferences = TranslationPreferences(context)
        preferences.setOverride("account", "group", TranslationOverride.ON, "de")
        val stored = context.getSharedPreferences("translation_choices", 0).all
        assertEquals(
            setOf(
                "privacy_notice_version",
                "chat:7:account:group",
                "chat:7:account:group:target",
            ),
            stored.keys,
        )
        assertTrue(preferences.effective("account", "group").enabled)
        preferences.close()
    }

    @Test fun legacyAutomaticOptInsResetOnceWhileLanguageAndOtherChoicesSurvive() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putBoolean("global", true)
            .putString("target", "es")
            .putString("chat:1:a:group", "ON")
            .putString("chat:1:a:group:target", "fr")
            .putString("chat:1:b:group", "OFF")
            .putString("chat:1:b:group:target", "de")
            .commit()
        val first = TranslationPreferences(context)
        assertEquals(TranslationChoice(false, "es"), first.global())
        assertEquals(TranslationChoice(false, "fr"), first.effective("a", "group"))
        assertEquals(TranslationChoice(false, "de"), first.effective("b", "group"))
        first.setGlobal(true, "es")
        first.setOverride("a", "group", TranslationOverride.ON, "fr")
        first.close()
        val reopened = TranslationPreferences(context)
        assertEquals(TranslationChoice(true, "es"), reopened.global())
        assertEquals(TranslationChoice(true, "fr"), reopened.effective("a", "group"))
        assertEquals(TranslationChoice(false, "de"), reopened.effective("b", "group"))
        reopened.close()
    }
}
