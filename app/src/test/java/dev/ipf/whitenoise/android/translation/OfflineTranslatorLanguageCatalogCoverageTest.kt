package dev.ipf.whitenoise.android.translation

import dev.ipf.whitenoise.android.functionBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Invariant gate: the Offline Translator language catalog matches every reviewed engine release's bundled
 * `index_v6.json`, pinned offline in `translation/offline-translator-catalogs.json`, and every picker, saved
 * choice and request check reads that one catalog instead of a hand-picked list or a tag-shaped regex.
 */
class OfflineTranslatorLanguageCatalogCoverageTest {
    private val fixture = UpstreamFixture.load()

    /** The fixture pins exactly the releases discovery accepts, each to a tag, commit and file blob. */
    @Test
    fun fixtureProvenanceCoversExactlyTheReviewedReleases() {
        assertEquals("https://github.com/DavidVentura/offline-translator", fixture.upstream)
        assertEquals("app/src/main/assets/index_v6.json", fixture.path)
        assertEquals(OfflineTranslatorLanguageCatalog.engineVersions, fixture.releases.map { it.version })
        for (release in fixture.releases) {
            assertEquals("v${release.version}", release.tag)
            assertTrue(release.commit.matches(GIT_OBJECT))
            assertTrue(release.blob.matches(GIT_OBJECT))
        }
    }

    /** Each release lists the same codes, in the same directions, as the catalog serves for that release. */
    @Test
    fun catalogMatchesEveryReviewedUpstreamBundle() {
        for (release in fixture.releases) {
            val expected = release.capabilities()
            val available = OfflineTranslatorLanguageCatalog.availableIn(release.version)
            assertEquals("codes in ${release.tag}", expected.keys, available)
            val actual =
                OfflineTranslatorLanguageCatalog.languages
                    .filter { it.code in available }
                    .associate { it.code to (it.source to it.target) }
            assertEquals("directions in ${release.tag}", expected, actual)
        }
        val everyRelease = fixture.releases.flatMap { it.capabilities().keys }.toSet()
        assertEquals(everyRelease, OfflineTranslatorLanguageCatalog.languages.map { it.code }.toSet())
    }

    /** Version differences are explicit: Georgian is new in 0.8.4, and Slovak exists in every release. */
    @Test
    fun versionDifferencesFollowTheReleaseThatListsTheCode() {
        assertFalse("ka" in OfflineTranslatorLanguageCatalog.availableIn("0.8.3"))
        assertTrue("ka" in OfflineTranslatorLanguageCatalog.availableIn("0.8.4"))
        for (version in OfflineTranslatorLanguageCatalog.engineVersions) {
            assertTrue("sk" in OfflineTranslatorLanguageCatalog.availableIn(version))
        }
        assertTrue(OfflineTranslatorLanguageCatalog.availableIn("0.8.2").isEmpty())
        assertTrue(OfflineTranslatorLanguageCatalog.availableIn("0.8.6").isEmpty())
    }

    /** Engine codes stay separate from display tags, and every label in English is distinct. */
    @Test
    fun engineCodesStaySeparateFromDisplayTags() {
        val catalog = OfflineTranslatorLanguageCatalog
        for (language in catalog.languages) {
            assertEquals(language.displayTag, Locale.forLanguageTag(language.displayTag).toLanguageTag())
        }
        assertEquals("zh-Hant", catalog.languages.single { it.code == "zh_hant" }.displayTag)
        assertFalse(catalog.supportsTarget("zh-Hant"))
        val labels = catalog.languages.map { catalog.label(it.code, Locale.ENGLISH) }
        assertEquals(labels.size, labels.toSet().size)
        assertNotEquals(catalog.label("zh", Locale.ENGLISH), catalog.label("zh_hant", Locale.ENGLISH))
        val norwegian = listOf("no", "nb", "nn").map { catalog.label(it, Locale.ENGLISH) }
        assertEquals(3, norwegian.toSet().size)
        assertEquals("Slovak", catalog.label("sk", Locale.ENGLISH))
    }

    /** Pickers, preferences and both request checks read the catalog; no file keeps its own language list. */
    @Test
    fun pickersAndValidationShareTheCatalog() {
        val settings = source("TranslationSettings.kt")
        val choices = settings.functionBody("rememberTranslationLanguageChoices")
        assertTrue("catalog.sources" in choices && "catalog.targets" in choices)
        assertTrue("rememberTranslationLanguageChoices(" in settings.functionBody("TranslationLanguagePicker"))
        val form = source("MessageTranslationForm.kt").functionBody("TranslationLanguageRow")
        assertTrue("rememberTranslationLanguageChoices(" in form && "choices.codes" in form)
        assertTrue(
            "OfflineTranslatorLanguageCatalog.requestFailure(" in
                source("BoundTranslatorRequest.kt").functionBody("translateOnBoundTranslator"),
        )
        assertTrue(
            "OfflineTranslatorLanguageCatalog.supportsRequest(" in
                source("TranslationEngine.kt").functionBody("validateTranslationInput"),
        )
        val controls = source("MessageTranslationControls.kt")
        val dialog = controls.functionBody("MessageTranslationDialog")
        assertTrue("OfflineTranslatorLanguageCatalog.supportsRequest(" in dialog)
        val automatic = controls.functionBody("rememberMessageTranslationSource")
        assertTrue("OfflineTranslatorLanguageCatalog.supportsTarget(" in automatic)
        val preferences = source("TranslationPreferences.kt")
        for (writer in listOf("setGlobal", "setTargetOverride")) {
            assertTrue(writer, "OfflineTranslatorLanguageCatalog.supportsTarget(" in preferences.functionBody(writer))
        }
        val reader = preferences.substringAfter("private fun usableTarget(", missingDelimiterValue = "")
        assertTrue("OfflineTranslatorLanguageCatalog::supportsTarget" in reader)
        val discovery = source("TranslationEngineDiscovery.kt").functionBody("validateTranslatorPackage")
        assertTrue("OfflineTranslatorLanguageCatalog.engineVersions" in discovery)
        assertNoPrivateLanguageLists()
    }

    /** Only the catalog may spell out language codes or versions, and no tag-shaped validator may return. */
    private fun assertNoPrivateLanguageLists() {
        val codeList = Regex("""(?:listOf|setOf|arrayOf)\(\s*"[a-z]{2}"\s*,""")
        val version = Regex(""""0\.8\.\d"""")
        for (file in translationSources().filter { it.name != "OfflineTranslatorLanguageCatalog.kt" }) {
            val text = file.readText()
            assertFalse("${file.name} keeps its own language list", codeList.containsMatchIn(text))
            assertFalse("${file.name} validates codes by shape", "Regex(\"[a-z]" in text)
            val allowsZapstoreRecord = file.name == "OfflineTranslatorAcquisition.kt"
            assertTrue("${file.name} names an engine version", allowsZapstoreRecord || !version.containsMatchIn(text))
        }
    }

    /** Reads a production translation source from either Gradle's module directory or the repository root. */
    private fun source(name: String): String = translationSources().single { it.name == name }.readText()

    /** Lists the production translation adapter sources. */
    private fun translationSources(): List<File> =
        listOf(File(SOURCE_DIRECTORY), File("app/$SOURCE_DIRECTORY"))
            .first { it.isDirectory }
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .toList()

    /** The pinned upstream catalogs, parsed with the same derivation the fixture describes. */
    private class UpstreamFixture(
        val upstream: String,
        val path: String,
        val releases: List<Release>,
    ) {
        class Release(
            val version: String,
            val tag: String,
            val commit: String,
            val blob: String,
            val models: Map<String, Set<String>>,
        ) {
            /** Source and target capability per code: English pivots, so `x-en` and `en-x` models decide them. */
            fun capabilities(): Map<String, Pair<Boolean, Boolean>> =
                models.mapValues { (code, ids) ->
                    if (code == "en") {
                        ids.any { it.startsWith("translate-en-") } to ids.any { it.endsWith("-en") }
                    } else {
                        ("translate-$code-en" in ids) to ("translate-en-$code" in ids)
                    }
                }
        }

        companion object {
            /** Parses the checked-in fixture, so the gate never needs the network. */
            fun load(): UpstreamFixture {
                val stream =
                    checkNotNull(UpstreamFixture::class.java.getResourceAsStream(FIXTURE)) { "Missing $FIXTURE" }
                val json = JSONObject(stream.bufferedReader().use { it.readText() })
                val versions = json.getJSONArray("versions")
                val releases =
                    (0 until versions.length()).map { index ->
                        val release = versions.getJSONObject(index)
                        val languages = release.getJSONObject("languages")
                        Release(
                            version = release.getString("version"),
                            tag = release.getString("tag"),
                            commit = release.getString("commit"),
                            blob = release.getString("blob"),
                            models =
                                languages.keys().asSequence().associateWith { code ->
                                    val ids = languages.getJSONArray(code)
                                    (0 until ids.length()).map(ids::getString).toSet()
                                },
                        )
                    }
                return UpstreamFixture(json.getString("upstream"), json.getString("path"), releases)
            }
        }
    }

    private companion object {
        const val FIXTURE = "/translation/offline-translator-catalogs.json"
        const val SOURCE_DIRECTORY = "src/main/java/dev/ipf/whitenoise/android/translation"
        val GIT_OBJECT = Regex("[0-9a-f]{40}")
    }
}
