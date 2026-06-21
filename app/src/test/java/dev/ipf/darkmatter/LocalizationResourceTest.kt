package dev.ipf.darkmatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceTest {
    @Test
    fun localizedStringFilesHaveTheSameKeysAsDefaultEnglish() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val defaultKeys = stringKeys(File(resDir, "values/strings.xml"))

        resDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
            .forEach { localized ->
                assertEquals(localized.path, defaultKeys, stringKeys(localized))
            }
    }

    @Test
    fun localizedStringFilesDoNotCopyEnglishUserVisibleText() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val englishValues = stringValues(File(resDir, "values/strings.xml"))

        resDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.exists() }
            .forEach { localized ->
                val copiedKeys =
                    stringValues(localized)
                        .filter { (key, value) ->
                            key !in identicalValueAllowedKeys &&
                                value.isNotBlank() &&
                                value == englishValues[key]
                        }.keys

                assertTrue("${localized.path} copies English for $copiedKeys", copiedKeys.isEmpty())
            }
    }

    @Test
    fun relayListLabelsDescribeUserVisibleBehavior() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }
        val englishValues = stringValues(File(resDir, "values/strings.xml"))

        assertEquals("Where I post", englishValues["nip_65"])
        assertEquals("Where I receive", englishValues["inbox"])
    }

    // Guards the umbrella sweep from #381: user-visible string values must not
    // expose raw NIP specification identifiers (e.g. "NIP-05", "NIP-65",
    // "NIP-44") or the deprecated "NIP-EE" naming. NIP numbers are protocol
    // implementation detail and mean nothing to a non-developer user; group
    // encryption is the "Marmot Protocol", not "NIP-EE". Code identifiers,
    // log lines and code comments are out of scope — this only inspects the
    // textContent of <string> resources, not their keys (so the `nip_05` /
    // `nip_65` resource *keys* are unaffected). If you need to reference a NIP
    // for power users, keep it in a developer-facing log or comment, not in a
    // user-visible string. See https://github.com/marmot-protocol/darkmatter-android/issues/381
    @Test
    fun userVisibleStringsDoNotExposeRawNipIdentifiers() {
        val resDir =
            listOf(File("src/main/res"), File("app/src/main/res"))
                .first { it.exists() }

        val resourceFiles =
            buildList {
                add(File(resDir, "values/strings.xml"))
                resDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.name.startsWith("values-") }
                    .map { File(it, "strings.xml") }
                    .filter { it.exists() }
                    .forEach { add(it) }
            }

        val offenders =
            resourceFiles.flatMap { file ->
                stringValues(file)
                    .filter { (_, value) -> forbiddenNipPattern.containsMatchIn(value) }
                    .map { (key, value) -> "${file.path}: $key=\"$value\"" }
            }

        assertTrue(
            "User-visible string values must not expose raw NIP identifiers " +
                "(NIP-<number> or the deprecated NIP-EE). Replace them with a " +
                "human-readable description (see #381). Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun stringKeys(file: File): Set<String> = stringValues(file).keys

    private fun stringValues(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until strings.length) {
                val item = strings.item(index)
                // translatable="false" strings (format-only or fixed keywords)
                // live in the default values only and must not be replicated to
                // locale files, so they are not part of the parity contract.
                if (item.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                put(item.attributes.getNamedItem("name").nodeValue, item.textContent)
            }
        }
    }

    private companion object {
        // Matches raw NIP specification identifiers in user-visible copy:
        // "NIP-05", "NIP_44", "NIP 65", etc., plus the deprecated "NIP-EE"
        // naming. Case-insensitive and tolerant of the hyphen/underscore/space
        // separators a translator might introduce.
        val forbiddenNipPattern = Regex("""NIP[-_ ]?(\d+|EE)""", RegexOption.IGNORE_CASE)

        val identicalValueAllowedKeys =
            setOf(
                // Pure positional-format string ("current/total"); no
                // translatable text, identical across every locale by design.
                "conversation_search_match_count",
                "admin",
                "app_name",
                "app_preferences",
                "actions",
                "bytes_count",
                "edit_history_original",
                "edit_history_version_label",
                "generic_message",
                "invitation",
                "key_packages",
                "language_chinese_simplified",
                "language_chinese_traditional",
                "language_english",
                "language_french",
                "language_german",
                "language_italian",
                "language_portuguese",
                "language_russian",
                "language_spanish",
                "language_turkish",
                "live",
                "message",
                "message_info_status",
                "mls",
                "nip_05",
                "no",
                "notification_channel_messages",
                "notification_sender_in_group",
                "notifications",
                "online",
                "ref",
                "relay_health",
                "new_chat_optional_section",
                "relays",
                "settings_version_label",
                "status",
                "theme_amoled",
                "theme_system",
                "total",
                // Some translations may legitimately match English for
                // some keys/locales (e.g. "Video" in many languages, "Foto"
                // in DE/ES/IT/PT). Keep just the brand-shared tokens here
                // — everything else gets a real translation enforced by
                // localizedStringFilesDoNotCopyEnglishUserVisibleText.
                "reply_media_photo",
                "reply_media_video",
                "reply_media_voice",
                "reply_media_document",
                "toast_couldnt_process_video",
                // Brand / loan-word tokens in the auto-download matrix (#407)
                // that are legitimately identical to English in several
                // locales: "Wi-Fi"/"Roaming" as borrowed terms and the media
                // type names ("Audio", "Video", and "Images"/"Documents" in
                // French) that share spelling with English.
                "network_wifi",
                "network_roaming",
                "media_type_images",
                "media_type_audio",
                "media_type_video",
                "media_type_documents",
            )
    }
}
