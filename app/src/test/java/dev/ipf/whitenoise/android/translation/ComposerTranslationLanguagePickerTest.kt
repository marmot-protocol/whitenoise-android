package dev.ipf.whitenoise.android.translation

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared searchable language picker sheet used by the composer and the translation settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerTranslationLanguagePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val names =
        mapOf("de" to "German", "es" to "Español", "fr" to "French", "pt" to "Portuguese", "pl" to "Polish")
    private val choices = TranslationLanguageChoices(names.keys.toList()) { names.getValue(it) }
    private val picked = mutableListOf<String>()
    private var dismissed = 0

    private fun show(selected: String? = "fr") {
        composeRule.setContent {
            WhiteNoiseTheme {
                TranslationLanguagePickerSheet(
                    title = "Target language",
                    choices = choices,
                    selected = selected,
                    onDismiss = { dismissed++ },
                    onSelect = { picked += it },
                )
            }
        }
    }

    private fun type(text: String) {
        composeRule.onNodeWithTag("translation.language.search").performTextReplacement(text)
    }

    private fun assertRows(vararg codes: String) {
        names.keys.forEach { code ->
            val node = composeRule.onNodeWithTag("translation.language.$code")
            if (code in codes) node.assertExists() else node.assertDoesNotExist()
        }
    }

    /** Every language is listed first and the current target is marked selected. */
    @Test
    fun listsAllLanguagesWithCurrentSelected() {
        show()
        assertRows("de", "es", "fr", "pt", "pl")
        composeRule.onNodeWithTag("translation.language.fr").assertIsSelected()
    }

    /** Typing letter by letter narrows the list on each keystroke. */
    @Test
    fun typingNarrowsTheList() {
        show()
        val search = composeRule.onNodeWithTag("translation.language.search")
        search.performTextInput("p")
        assertRows("pt", "pl", "es") // "Español" contains p
        search.performTextInput("o")
        assertRows("pt", "pl")
        search.performTextInput("r")
        assertRows("pt")
    }

    /** Matching is accent- and case-insensitive and also reads the language code. */
    @Test
    fun matchesAccentsAndCodes() {
        show()
        type("ESPANOL")
        assertRows("es")
        type("de")
        assertRows("de")
        type("fr")
        assertRows("fr")
    }

    /** A query with no match shows the empty state and no rows. */
    @Test
    fun noMatchShowsEmptyState() {
        show()
        type("zzz")
        composeRule.onNodeWithTag("translation.language.empty").assertExists()
        assertRows()
    }

    /** Tapping a row reports the code. */
    @Test
    fun selectingReportsTheCode() {
        show()
        type("ger")
        composeRule.onNodeWithTag("translation.language.de").performClick()
        assertEquals(listOf("de"), picked)
    }

    /** The folding helper treats accents and case as equal. */
    @Test
    fun foldingIgnoresAccentsAndCase() {
        assertEquals("espanol", foldForLanguageSearch("Español"))
        assertTrue(filterLanguageCodes(listOf("de"), { "Deutsch" }, "  ").size == 1)
    }

    /** Closing the composer's picker while no target exists turns translate mode off again. */
    @Test
    fun dismissWithoutTargetClosesTranslateMode() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val state =
            ComposerTranslationState(
                scope = scope,
                engine = { error("unused") },
                ownerCurrent = { true },
                initialTarget = null,
                settleMillis = 0L,
            ).also { it.toggle() }
        composeRule.setContent { WhiteNoiseTheme { ComposerTranslationPreview(state, "Hello") } }
        composeRule.onNodeWithTag("translation.language.search").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertFalse(state.active) }
        scope.cancel()
    }

    /** The settings picker opens the same sheet and saves the chosen language. */
    @Test
    fun settingsPickerOpensTheSheetAndSaves() {
        var saved: String? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                TranslationLanguagePicker(title = "Target language", selected = "en", onSelected = { saved = it })
            }
        }
        composeRule.onNodeWithText("Target language", substring = true).performClick()
        composeRule.onNodeWithTag("translation.language.search").assertExists()
        composeRule.onNodeWithTag("translation.language.de").performClick()
        composeRule.runOnIdle { assertEquals("de", saved) }
        composeRule.onNodeWithTag("translation.language.search").assertDoesNotExist()
    }
}
