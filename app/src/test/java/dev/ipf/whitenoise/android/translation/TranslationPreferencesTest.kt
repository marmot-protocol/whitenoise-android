package dev.ipf.whitenoise.android.translation

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** Chat modes and targets are scoped to one account and group, and inheriting follows global changes. */
    @Test fun overridesNeverLeakAcrossAccountsOrChatsAndInheritFollowsGlobalChanges() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(true, "de")
        preferences.setOverride("a", "group", TranslationOverride.OFF)
        preferences.setTargetOverride("a", "group", "fr")
        assertFalse(preferences.effective("a", "group").enabled)
        assertEquals("fr", preferences.effective("a", "group").target)
        assertEquals(TranslationChoice(true, "de"), preferences.effective("b", "group"))
        assertEquals(TranslationChoice(true, "de"), preferences.effective("a", "other"))
        preferences.setGlobal(false, "es")
        assertEquals(TranslationChoice(false, "es"), preferences.effective("b", "group"))
        preferences.setOverride("a", "group", TranslationOverride.INHERIT)
        assertEquals("fr", preferences.targetOverride("a", "group"))
        preferences.setTargetOverride("a", "group", null)
        assertEquals(TranslationChoice(false, "es"), preferences.effective("a", "group"))
        preferences.close()
    }

    /** Corrupt switches fail closed, and a corrupt saved target asks for a choice instead of becoming English. */
    @Test fun corruptGlobalAndOverrideValuesFailClosed() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("global", "not-a-boolean")
            .putString("target", "invalid target")
            .putString("chat:1:a:group", "unknown")
            .putInt("chat:1:b:group:target", 7)
            .commit()
        val preferences = TranslationPreferences(context)
        assertFalse(preferences.global().enabled)
        assertNull(preferences.global().target)
        assertEquals(TranslationOverride.INHERIT, preferences.override("a", "group"))
        assertEquals("", preferences.targetOverride("b", "group"))
        assertNull(preferences.effective("b", "group").target)
        preferences.close()
    }

    /** Slovak and engine-only variant identifiers are stored and read back exactly as the engine spells them. */
    @Test fun slovakAndVariantEngineCodesRoundTripUnchanged() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(true, "sk")
        preferences.setTargetOverride("a", "group", "zh_hant")
        preferences.setTargetOverride("b", "group", "nb")
        val stored = context.getSharedPreferences("translation_choices", 0)
        assertEquals("sk", stored.getString("target", null))
        assertEquals("zh_hant", stored.getString("chat:1:a:group:target", null))
        preferences.close()
        val reopened = TranslationPreferences(context)
        assertEquals(TranslationChoice(true, "sk"), reopened.global())
        assertEquals(TranslationChoice(true, "zh_hant"), reopened.effective("a", "group"))
        assertEquals(TranslationChoice(true, "nb"), reopened.effective("b", "group"))
        assertEquals("zh_hant", reopened.targetOverride("a", "group"))
        reopened.close()
    }

    /** Every target the previous 26-language picker could save survives the catalog upgrade unchanged. */
    @Test fun everyLegacyPickerChoiceSurvivesTheCatalogUpgrade() {
        // The previous hand-picked list, which every earlier picker and validator used.
        val legacy = "ar cs da de el en es fi fr he hi hu id it ja ko nl no pl pt ro ru sv tr uk zh".split(" ")
        for (code in legacy) {
            reset()
            context
                .getSharedPreferences("translation_choices", 0)
                .edit()
                .putString("target", code)
                .putString("chat:1:a:group:target", code)
                .commit()
            val preferences = TranslationPreferences(context)
            assertEquals(code, preferences.global().target)
            assertEquals(code, preferences.effective("a", "group").target)
            preferences.close()
        }
    }

    /** An unsupported saved target pauses with an explicit recovery path and is replaced only by a new choice. */
    @Test fun unsupportedSavedTargetsNeedAnExplicitChoiceInsteadOfASubstitute() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("target", "xx")
            .putString("chat:1:a:group:target", "zh-Hant")
            .commit()
        val preferences = TranslationPreferences(context)
        assertNull(preferences.global().target)
        preferences.setGlobal(true, null)
        assertEquals(TranslationChoice(true, null), preferences.global())
        assertEquals("xx", context.getSharedPreferences("translation_choices", 0).getString("target", null))
        preferences.setGlobal(true, "de")
        assertEquals("zh-Hant", preferences.targetOverride("a", "group"))
        assertEquals(TranslationChoice(true, null), preferences.effective("a", "group"))
        assertEquals(TranslationChoice(true, "de"), preferences.effective("b", "group"))
        preferences.setTargetOverride("a", "group", "sk")
        assertEquals(TranslationChoice(true, "sk"), preferences.effective("a", "group"))
        preferences.close()
    }

    /** Writes accept only catalog targets: display tags, unknown codes and into-English-only languages fail. */
    @Test fun writesRejectCodesOutsideTheCatalogTargets() {
        val preferences = TranslationPreferences(context)
        for (code in listOf("zh-Hant", "xx", "be", "")) {
            assertThrows(IllegalArgumentException::class.java) { preferences.setGlobal(true, code) }
            assertThrows(IllegalArgumentException::class.java) { preferences.setTargetOverride("a", "group", code) }
        }
        assertEquals(TranslationChoice(false, "en"), preferences.global())
        assertNull(preferences.targetOverride("a", "group"))
        preferences.close()
    }

    /** Only the chat mode and target are stored, never message text, renditions or request identity. */
    @Test fun storedChoicesContainNoRenditionOrRequestData() {
        val preferences = TranslationPreferences(context)
        preferences.setOverride("account", "group", TranslationOverride.ON)
        preferences.setTargetOverride("account", "group", "de")
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

    /** Pre-notice automatic opt-ins reset once, while their languages and Off choices survive. */
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
        first.setOverride("a", "group", TranslationOverride.ON)
        first.close()
        val reopened = TranslationPreferences(context)
        assertEquals(TranslationChoice(true, "es"), reopened.global())
        assertEquals(TranslationChoice(true, "fr"), reopened.effective("a", "group"))
        assertEquals(TranslationChoice(false, "de"), reopened.effective("b", "group"))
        reopened.close()
    }
}
