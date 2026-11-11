package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class SettingsSearchRankingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun documents(context: Context = this.context) =
        prepareSettingsSearch(
            settingsSearchCatalog(),
            SettingsSearchEligibility(true, true, false, true),
            context::getString,
        )

    @Test fun productionQueriesFindTheirControlsImmediately() {
        val documents = documents()
        val expected =
            mapOf(
                "font" to "Appearance.font_family",
                "text size" to "Appearance.font_size",
                "bubble color" to "Appearance.chat_bubble_colors",
                "notification sound" to "Notifications.sound",
                "block" to "DevicePrivacy.blocked_users",
                "read aloud" to "home.ReadAloud",
                "speaker shortcut" to "TextToSpeech.composer_shortcut",
                "gestures" to "home.Gestures",
            )
        expected.forEach { (query, id) ->
            assertTrue(
                "$query",
                searchSettings(documents, query).take(3).any {
                    it.entry.id ==
                        id
                },
            )
        }
        assertEquals("Appearance.font_size", searchSettings(documents, "font size").first().entry.id)
        assertEquals("Appearance.font_size", searchSettings(documents, "text size").first().entry.id)
    }

    @Test fun motionFixtureUsesTheActualCrossFieldCatalogMatches() {
        val documents = documents()
        assertEquals(
            listOf(
                "Appearance.font_family",
                "Appearance.font_size",
            ),
            searchSettings(documents, "app font").map {
                it.entry.id
            },
        )
        assertEquals(listOf("Appearance.font_size"), searchSettings(documents, "font size").map { it.entry.id })
        val appearance = searchSettings(documents, "appearance").map { it.entry.id }
        assertEquals("home.Appearance", appearance.first())
        assertTrue(appearance.indexOf("Appearance.font_family") > 0)
        assertTrue(appearance.indexOf("Appearance.font_size") > appearance.indexOf("Appearance.font_family"))
        assertEquals(
            listOf(
                "Appearance.font_family",
                "Appearance.font_size",
            ),
            searchSettings(documents, "appearance font").map {
                it.entry.id
            },
        )
    }

    @Test fun normalizationInfixDeletionAndEveryTokenAreRequired() {
        val documents = documents()
        assertEquals(searchSettings(documents, "text size"), searchSettings(documents, "  TEXT   SÍZE  "))
        assertTrue(searchSettings(documents, "ont").any { it.entry.id == "Appearance.font_size" })
        val broad = searchSettings(documents, "font").map { it.entry.id }.toSet()
        val narrow = searchSettings(documents, "font size").map { it.entry.id }.toSet()
        assertTrue(broad.containsAll(narrow))
        assertTrue(searchSettings(documents, "font impossibleword").isEmpty())
        assertTrue(searchSettings(documents, " ").isEmpty())
        assertEquals(broad, searchSettings(documents, "font").map { it.entry.id }.toSet())
        assertEquals(searchSettings(documents, "font"), searchSettings(documents, "font"))
    }

    @Test fun equallyRankedMatchesKeepCatalogOrderInsteadOfSortingIds() {
        val entry = settingsSearchCatalog().first()
        val first = SettingsSearchDocument(entry.copy(id = "z-first"), "Font", "Appearance", "", "")
        val second = SettingsSearchDocument(entry.copy(id = "a-second"), "Font", "Appearance", "", "")
        assertEquals(listOf(first, second), searchSettings(listOf(first, second), "font"))
        assertEquals(listOf(second, first), searchSettings(listOf(second, first), "font"))
    }

    @Test fun lateSummaryTermsAreNotTruncatedByTheQueryLengthLimit() {
        val entry = settingsSearchCatalog().first()
        val document = SettingsSearchDocument(entry, "Title", "Category", "prefix ".repeat(40) + "lateword", "")
        assertEquals(listOf(document), searchSettings(listOf(document), "lateword"))
    }

    @Test fun spanishTitlesAndCuratedAliasesUseActualResources() {
        val configuration =
            Configuration(
                context.resources.configuration,
            ).apply { setLocale(Locale.forLanguageTag("es")) }
        val documents = documents(context.createConfigurationContext(configuration))
        val entry = searchSettings(documents, "tamano de texto").first()
        assertEquals("Appearance.font_size", entry.entry.id)
        assertEquals("Tamaño de fuente", entry.title)
        assertEquals("Appearance.font_size", searchSettings(documents, "TAMAÑO DE FUENTE").first().entry.id)
        assertTrue(searchSettings(documents, "sonido de notificacion").any { it.entry.id == "Notifications.sound" })
    }

    /** Search ranks the whole catalog per keystroke, so its size stays bounded. */
    @Test fun productionCatalogSizeIsBounded() {
        assertTrue(documents().size in 50..256)
    }
}
