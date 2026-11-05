package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
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

    @Test fun explicitNonRowAnchorsReferToProductionWidgets() {
        val sources = inventory.getJSONObject("sources")
        sources.keys().forEach { path ->
            val controls = sources.getJSONObject(path).optJSONArray("non_row_controls")
            if (controls != null) {
                val source = File(root, path).readText()
                (0 until controls.length()).forEach { index ->
                    val control = controls.getJSONObject(index)
                    if (control.has("anchor")) assertTrue(path, source.contains(control.getString("anchor")))
                    assertTrue(path, catalog.any { it.id == control.getString("represented_by") })
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
        val translationSource =
            File(
                root,
                "app/src/main/java/dev/ipf/whitenoise/android/translation/TranslationSettings.kt",
            ).readText()
        val all = (sources.values + translationSource).joinToString("\n")
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

    @Test fun independentStableActionLabelsResolveToTheirRealControls() {
        // Expected labels come from the production widget inventory, not from catalog iteration.
        val expected =
            listOf(
                Triple(R.string.scan_qr_code, SettingsDetail.ShareConnect, "scan_qr_code"),
                Triple(R.string.share_profile, SettingsDetail.ShareConnect, "share_profile"),
                Triple(R.string.copy_public_key, SettingsDetail.ShareConnect, "copy_public_key"),
                Triple(R.string.report_bug_open_github, SettingsDetail.BugReport, "open_github"),
                Triple(R.string.refresh, SettingsDetail.KeyPackages, "refresh"),
                Triple(R.string.republish, SettingsDetail.KeyPackages, "republish"),
                Triple(R.string.developer_publish_new, SettingsDetail.KeyPackages, "publish_new"),
            )
        expected.forEach { (label, destination, anchor) ->
            val entry = catalog.single { it.titleRes == label && it.destination == destination }
            assertEquals(anchor, entry.rowKey)
            assertFalse(entry.developerRequired)
            assertEquals(destination != SettingsDetail.BugReport, entry.accountRequired)
        }
        val support = catalog.single { it.titleRes == R.string.start_chat && it.destination == SettingsDetail.Support }
        assertEquals("search.SupportScreen.5", support.itemKey)
        assertTrue(support.accountRequired)
        assertTrue(catalog.any { it.titleRes == R.string.settings_donate && it.destination == SettingsDetail.Donate })
        assertTrue(catalog.any { it.titleRes == R.string.restore_default_relays })
        assertTrue(catalog.any { it.titleRes == R.string.folder_new_title })
    }

    @Test fun localizedShareMenuLabelsResolveToOneExistingShareTrigger() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documents =
            prepareSettingsSearch(catalog, SettingsSearchEligibility(true, false, false, true)) {
                context.getString(it)
            }
        listOf(R.string.share_profile_url, R.string.share_profile_picture, R.string.share_connect_save_to_gallery)
            .forEach { label ->
                val matches =
                    searchSettings(documents, context.getString(label))
                        .filter { it.entry.destination == SettingsDetail.ShareConnect }
                assertEquals(listOf("ShareConnect.share"), matches.map { it.entry.id })
                assertEquals("share_profile", matches.single().entry.rowKey)
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
        assertTrue(
            ids(
                eligible,
            ).containsAll(
                listOf(
                    "Account.private_key",
                    "Developer.quarantined_groups",
                    "Notifications.push",
                    "Appearance.action_color",
                ),
            ),
        )
        val withdrawn =
            eligible.copy(
                hiddenIds = setOf("Privacy.auto_lock", "TextToSpeech.voice", "Home.update_controls"),
            )
        assertFalse(ids(withdrawn).contains("Privacy.auto_lock"))
        assertFalse(ids(withdrawn).contains("TextToSpeech.voice"))
        assertFalse(ids(withdrawn).contains("Home.update_controls"))
    }
}
