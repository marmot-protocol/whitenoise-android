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
    // log lines and code comments are out of scope — this inspects the
    // textContent of every user-visible resource (<string> plus the <item>
    // children of <plurals> and <string-array>), not their keys (so the
    // `nip_05` / `nip_65` resource *keys* are unaffected). If you need to
    // reference a NIP for power users, keep it in a developer-facing log or
    // comment, not in a user-visible string.
    // See https://github.com/marmot-protocol/darkmatter-android/issues/381
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
                userVisibleValues(file)
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

    // Extracts every *user-visible* localized resource value: plain <string>
    // entries plus the <item> children of <plurals> and <string-array>. Unlike
    // stringValues (used for the key-parity contract, which intentionally keys
    // only on <string> names), this powers the NIP guard so a raw NIP token
    // cannot slip in through a quantity string or array item. translatable=
    // "false" strings are included here because they are still rendered to the
    // user; only their absence from locale files is exempt from parity, not
    // their content from the NIP audit. Keys are made unique per source:
    // "<name>" for strings, "<name>[<quantity>]" for plurals items, and
    // "<name>[<index>]" for string-array items.
    private fun userVisibleValues(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        return buildMap {
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val item = strings.item(index)
                put(item.attributes.getNamedItem("name").nodeValue, item.textContent)
            }

            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                val node = plurals.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                val items = node.childNodes
                for (i in 0 until items.length) {
                    val child = items.item(i)
                    if (child.nodeName == "item") {
                        val quantity =
                            child.attributes?.getNamedItem("quantity")?.nodeValue ?: "unknown"
                        put("$name[$quantity]", child.textContent)
                    }
                }
            }

            val arrays = document.getElementsByTagName("string-array")
            for (index in 0 until arrays.length) {
                val node = arrays.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                val items = node.childNodes
                var seen = 0
                for (i in 0 until items.length) {
                    val child = items.item(i)
                    if (child.nodeName == "item") {
                        put("$name[$seen]", child.textContent)
                        seen++
                    }
                }
            }
        }
    }

    private companion object {
        // Matches raw NIP specification identifiers in user-visible copy:
        // "NIP-05", "NIP_44", "NIP 65", "NIP - 65", "NIP65", etc., plus the
        // deprecated "NIP-EE" naming. Case-insensitive. The leading `\b` plus
        // the requirement that "NIP" be followed by a separator/whitespace or
        // the number avoids false positives inside larger words such as
        // "SNIP-65" or "NIPSTER 65", while still tolerating the hyphen /
        // underscore / space (and spaced "NIP - 65") separators a translator
        // might introduce.
        val forbiddenNipPattern =
            Regex("""\bNIP(?:\s*[-_]\s*|\s+)?(?:\d+|EE)\b""", RegexOption.IGNORE_CASE)

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
                // Shared-media tab labels that are loan-words / spelled the same
                // in some locales ("Images" in French, "Videos" in German).
                "shared_media_tab_images",
                "shared_media_tab_videos",
                // Issue #410 explicitly allows placeholder English until the
                // update UX copy gets real translations.
                "app_updates",
                "app_update_settings_title",
                "app_update_settings_unknown",
                "app_update_settings_current",
                "app_update_settings_available",
                "app_update_settings_available_with_count",
                "app_update_available_title",
                "app_update_persistent_title",
                "app_update_available_description",
                "app_update_now",
                "toast_zapstore_unavailable",
                "notification_channel_app_updates",
                "notification_channel_app_updates_description",
            )
    }
}
