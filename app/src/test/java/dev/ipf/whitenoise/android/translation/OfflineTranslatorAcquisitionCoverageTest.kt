package dev.ipf.whitenoise.android.translation

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate: every Offline Translator install or recovery entry point uses one shared route, Zapstore is
 * preferred only for a reviewed release that discovery would accept, the Zapstore address pins the publisher's
 * app identity, and the compatible fallback is the newest reviewed publisher release.
 */
class OfflineTranslatorAcquisitionCoverageTest {
    /** The naddr names kind 32267 app `dev.davidv.translator` by the publisher key from upstream `zapstore.yaml`. */
    @Test
    fun zapstoreAddressPinsThePublisherAndApp() {
        val prefix = "https://zapstore.dev/apps/"
        assertTrue(OFFLINE_TRANSLATOR_ZAPSTORE_URL.startsWith(prefix))
        val fields = naddrFields(OFFLINE_TRANSLATOR_ZAPSTORE_URL.removePrefix(prefix))
        assertEquals("dev.davidv.translator", String(fields.getValue(TLV_IDENTIFIER)))
        assertEquals("wss://relay.zapstore.dev", String(fields.getValue(TLV_RELAY)))
        assertEquals(PUBLISHER, fields.getValue(TLV_AUTHOR).joinToString("") { "%02x".format(it) })
        val kind = fields.getValue(TLV_KIND).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
        assertEquals(ZAPSTORE_APP_KIND, kind)
    }

    /** The fallback opens the publisher's release for the newest reviewed engine, never another store. */
    @Test
    fun fallbackIsTheNewestReviewedPublisherRelease() {
        val newest = OfflineTranslatorLanguageCatalog.engineVersions.last()
        assertEquals(
            "https://github.com/DavidVentura/offline-translator/releases/tag/v$newest",
            offlineTranslatorReleaseUrl,
        )
        for (file in translationSources()) assertFalse(file.name, "f-droid.org" in file.readText())
    }

    /** Zapstore wins exactly when its reviewed release carries the pinned signer and a reviewed version. */
    @Test
    fun zapstoreIsPreferredOnlyForAReleaseDiscoveryAccepts() {
        val reviewed = OfflineTranslatorLanguageCatalog.engineVersions
        for (version in listOf("0.8.1", "0.8.2") + reviewed + "0.8.6") {
            for (signer in listOf(OFFLINE_TRANSLATOR_SIGNER, "ab".repeat(32))) {
                val accepted = version in reviewed && signer == OFFLINE_TRANSLATOR_SIGNER
                val expected = if (accepted) OfflineTranslatorRoute.ZAPSTORE else OfflineTranslatorRoute.GITHUB_RELEASE
                val route = offlineTranslatorRoute(ReviewedStoreRelease(version, signer))
                assertEquals("$version/$signer", expected, route)
            }
        }
        assertEquals(OFFLINE_TRANSLATOR_SIGNER, offlineTranslatorZapstoreRelease.signer)
    }

    /** Settings, chat settings and message recovery all hand off through the shared action and helper. */
    @Test
    fun everyEntryPointUsesTheSharedAcquisitionRoute() {
        val settings = source("TranslationSettings.kt")
        for (entry in listOf("TranslationGlobalSettingsContent", "TranslationChatSettingsDialog")) {
            val body = settings.functionBody(entry)
            val call = "OfflineTranslatorEngineAction(onOpen = { openOfflineTranslator(context) })"
            assertTrue(entry, body.mentions(call))
        }
        val form = source("MessageTranslationForm.kt").functionBody("MessageTranslationForm")
        assertTrue(form.mentions("OfflineTranslatorEngineAction(onOpen = onOpenEngine)"))
        val dialog = source("MessageTranslationControls.kt").functionBody("MessageTranslationDialog")
        assertTrue(dialog.mentions("onOpenEngine = { openOfflineTranslator(context) }"))
        for (file in translationSources().filter { it.name != "OfflineTranslatorAcquisition.kt" }) {
            val text = file.readText()
            assertFalse("${file.name} opens its own route", "ACTION_VIEW" in text || "startActivity(" in text)
            assertFalse("${file.name} names a store page", Regex("""https://(?!github\.com/)""").containsMatchIn(text))
        }
    }

    /** Whether this source contains [call], ignoring whitespace so a reformat cannot fail the gate. */
    private fun String.mentions(call: String): Boolean {
        val compact = filterNot(Char::isWhitespace)
        return call.filterNot(Char::isWhitespace) in compact
    }

    /** Decodes a NIP-19 `naddr` into its type-length-value fields, verifying the bech32 checksum. */
    private fun naddrFields(naddr: String): Map<Int, ByteArray> {
        val separator = naddr.lastIndexOf('1')
        val hrp = naddr.substring(0, separator)
        assertEquals("naddr", hrp)
        val values = naddr.substring(separator + 1).map { BECH32.indexOf(it).also { value -> assertTrue(value >= 0) } }
        val expanded = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }
        assertEquals(1, bech32Polymod(expanded + values))
        var accumulator = 0
        var bits = 0
        val bytes = mutableListOf<Byte>()
        for (value in values.dropLast(6)) {
            accumulator = (accumulator shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes += ((accumulator shr bits) and 0xff).toByte()
            }
        }
        val fields = mutableMapOf<Int, ByteArray>()
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index + 1].toInt() and 0xff
            fields[bytes[index].toInt()] = bytes.subList(index + 2, index + 2 + length).toByteArray()
            index += 2 + length
        }
        return fields
    }

    /** The BIP-173 checksum polynomial; a valid bech32 string folds to 1. */
    private fun bech32Polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (bit in 0 until 5) if ((top shr bit) and 1 == 1) checksum = checksum xor generators[bit]
        }
        return checksum
    }

    /** Reads one production translation source. */
    private fun source(name: String): String = translationSources().single { it.name == name }.readText()

    /** Lists the production translation adapter sources from either Gradle's module or the repository root. */
    private fun translationSources(): List<File> =
        listOf(File(SOURCE_DIRECTORY), File("app/$SOURCE_DIRECTORY"))
            .first { it.isDirectory }
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .toList()

    private companion object {
        const val SOURCE_DIRECTORY = "src/main/java/dev/ipf/whitenoise/android/translation"
        const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        const val PUBLISHER = "8eba32d745cf422b8470d44ea644c2b1213f6e6bf11f383c502c815adb9a588c"
        const val ZAPSTORE_APP_KIND = 32267
        const val TLV_IDENTIFIER = 0
        const val TLV_RELAY = 1
        const val TLV_AUTHOR = 2
        const val TLV_KIND = 3
    }
}
