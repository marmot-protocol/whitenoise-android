package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsSearchCatalogTest {
    private val root =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    private val catalog = settingsSearchCatalog()
    private val inventory = JSONObject(File(root, "docs/settings-search-coverage.json").readText())

    @Test fun independentDestinationAndSourceControlInventoryHasNoUnreviewedGaps() {
        val ids = catalog.map { it.id }.toSet()
        val destinations = inventory.getJSONObject("destinations")
        assertEquals(SettingsDetail.entries.map { it.name }.toSet(), destinations.keys().asSequence().toSet())
        destinations.keys().forEach { name ->
            assertTrue("$name", destinations.getJSONObject(name).getString("catalog_id") in ids)
        }
        val sources = inventory.getJSONObject("sources")
        sources.keys().forEach { path ->
            val source = File(root, path).readText()
            val declarations = Regex("""\brow\(([^\n)]+)\)""").findAll(source).map { it.groupValues[1] }.toSet()
            val controls = sources.getJSONObject(path).getJSONArray("controls")
            val reviewed = (0 until controls.length()).map { controls.getJSONObject(it) }
            assertEquals(path, declarations, reviewed.map { it.getString("declaration") }.toSet())
            reviewed.forEach { control ->
                if (control.has("represented_by")) {
                    assertTrue("$path $control", control.getString("represented_by") in ids)
                } else {
                    assertTrue("$path $control", control.getString("excluded").length >= 20)
                }
            }
        }
    }

    @Test fun stableIdsAndActualAnchorsAreValidAndBounded() {
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertTrue(catalog.size in 50..256)
        val sourceRoot = File(root, "app/src/main/java/dev/ipf/whitenoise/android/ui/settings")
        val sources =
            sourceRoot
                .listFiles()
                .orEmpty()
                .filter { it.extension == "kt" }
                .associate { it.name to it.readText() }
        val all = sources.values.joinToString("\n")
        catalog.forEach { entry ->
            entry.itemKey?.let { key ->
                if (key.startsWith("search.")) {
                    assertTrue("${entry.id}: $key", all.contains("item(key = \"$key\")"))
                } else {
                    assertTrue(key, SettingsHomeSection.entries.any { it.name == key })
                }
            }
            entry.rowKey?.let { row ->
                val literal = all.contains("row(\"$row\")") || all.contains("SettingsSearchRowReveal(\"$row\"")
                val generated =
                    entry.id.startsWith("Data.download.") ||
                        entry.id.startsWith("Gestures.") ||
                        SettingsHomeRow.entries.any { it.name == row }
                assertTrue("${entry.id}: invalid row $row", literal || generated)
            }
        }
    }

    @Test fun prerequisiteVariantsOmitHiddenControlsWithoutHidingVisibleDisabledControls() {
        fun ids(eligibility: SettingsSearchEligibility) = catalog.filter(eligibility::allows).map { it.id }.toSet()
        val public = ids(SettingsSearchEligibility(false, false, true, false))
        assertFalse("Account.private_key" in public)
        assertFalse("Developer.quarantined_groups" in public)
        assertFalse("Notifications.push" in public)
        assertFalse("Appearance.action_color" in public)
        assertTrue("Notifications.local" in public)
        assertTrue("DevicePrivacy.device_authentication" in public)
        val eligible = SettingsSearchEligibility(true, true, false, true)
        assertTrue(ids(eligible).containsAll(listOf("Account.private_key", "Developer.quarantined_groups", "Notifications.push", "Appearance.action_color")))
        val withdrawn = eligible.copy(hiddenIds = setOf("Privacy.auto_lock", "TextToSpeech.voice", "Home.update_controls"))
        assertFalse(ids(withdrawn).contains("Privacy.auto_lock"))
        assertFalse(ids(withdrawn).contains("TextToSpeech.voice"))
        assertFalse(ids(withdrawn).contains("Home.update_controls"))
    }
}
