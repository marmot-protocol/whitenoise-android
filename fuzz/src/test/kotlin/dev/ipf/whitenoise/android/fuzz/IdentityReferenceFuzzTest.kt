package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.RecipientReference
import org.junit.jupiter.api.Tag

@Tag("fuzz-identity")
class IdentityReferenceFuzzTest {
    private val sampleNpub = "npub1" + "a".repeat(58)

    @DictionaryEntries(
        "nostr:",
        "marmot://",
        "whitenoise://",
        "profile/",
        "npub1",
        ",",
    )
    @DictionaryFile(resourcePath = "/fuzz-grammar.dict")
    @FuzzTest
    fun fuzzIdentityReference(data: FuzzedDataProvider) {
        when (IdentitySubtarget.fromId(data.consumeSubtarget(IdentitySubtarget.COUNT))) {
            IdentitySubtarget.ProfileLink -> fuzzProfileLinkParse(data)
            IdentitySubtarget.RecipientNormalize -> fuzzRecipientNormalize(data)
            IdentitySubtarget.RecipientTokenize -> fuzzRecipientTokenize(data)
            IdentitySubtarget.PlausibleClipboard -> fuzzPlausibleClipboardInput(data)
        }
    }

    private fun fuzzProfileLinkParse(data: FuzzedDataProvider) {
        val raw = data.consumeParserInput()
        ProfileLink.parse(if (raw.isNotEmpty()) raw else buildProfileInput(data))
    }

    private fun fuzzRecipientNormalize(data: FuzzedDataProvider) {
        val raw = data.consumeParserInput()
        val input = if (raw.isNotEmpty()) raw else buildProfileInput(data)
        val normalized = RecipientReference.normalize(input)

        if (normalized != null) {
            FuzzAssertions.assertTrue("normalized reference failed validation", isValidNormalizedReference(normalized))
            FuzzAssertions.assertEquals(
                "normalized reference must be lowercase",
                normalized,
                normalized.lowercase(),
            )
            FuzzAssertions.assertEquals(
                "normalize is not idempotent",
                normalized,
                RecipientReference.normalize(normalized),
            )
        }
    }

    private fun fuzzRecipientTokenize(data: FuzzedDataProvider) {
        val raw = data.consumeParserInput()
        if (raw.isNotEmpty()) {
            RecipientReference.tokenize(raw)
            return
        }

        val tokenCount = data.consumeBoundedElementCount()
        val tokens =
            (0 until tokenCount).map {
                buildProfileInput(data)
            }
        val joined =
            tokens.joinToString(
                separator = data.pickFrom(listOf(",", " ", "\n", "\t", "  ")),
            )
        RecipientReference.tokenize(joined)
    }

    private fun fuzzPlausibleClipboardInput(data: FuzzedDataProvider) {
        if (data.remainingBytes() == 0) {
            val allowHex = data.consumeBoolean()
            val raw =
                if (data.consumeBoolean()) {
                    null
                } else {
                    buildProfileInput(data)
                }
            exercisePlausibleClipboard(raw, allowHex)
            return
        }

        val rawField = data.consumeFramedString()
        if (rawField.consumedAllRemaining) {
            exercisePlausibleClipboard(rawField.value.ifBlank { null }, allowHexPublicKey = false)
            return
        }

        val allowHex = data.consumeBoolean()
        val raw =
            if (data.consumeBoolean()) {
                null
            } else {
                rawField.value.ifBlank { null }
            }
        exercisePlausibleClipboard(raw, allowHex)
    }

    private fun exercisePlausibleClipboard(
        raw: String?,
        allowHexPublicKey: Boolean,
    ) {
        val plausible = RecipientReference.plausibleClipboardInput(raw, allowHexPublicKey = allowHexPublicKey)
        if (plausible != null) {
            val trimmed = raw?.trim().orEmpty()
            val singleNormalized = RecipientReference.normalize(trimmed)
            if (singleNormalized != null) {
                FuzzAssertions.assertTrue(
                    "plausible clipboard must honor the single-reference hex constraint",
                    allowHexPublicKey || !isHexKey(singleNormalized),
                )
                FuzzAssertions.assertEquals(
                    "plausible clipboard must match single normalized reference",
                    singleNormalized,
                    plausible,
                )
            } else {
                val tokens = RecipientReference.tokenize(trimmed)
                FuzzAssertions.assertTrue("tokenized clipboard input must not be empty", tokens.isNotEmpty())
                tokens.forEach { token ->
                    FuzzAssertions.assertTrue(
                        "clipboard token must normalize",
                        RecipientReference.normalize(token) != null,
                    )
                }
                if (!allowHexPublicKey) {
                    FuzzAssertions.assertFalse(
                        "clipboard must reject hex keys when allowHex is false",
                        tokens.any { token -> isHexKey(RecipientReference.normalize(token).orEmpty()) },
                    )
                }
                FuzzAssertions.assertEquals(
                    "plausible clipboard must preserve trimmed multi-token input",
                    trimmed,
                    plausible,
                )
            }
        } else if (!allowHexPublicKey) {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isNotEmpty()) {
                val singleNormalized = RecipientReference.normalize(trimmed)
                if (singleNormalized != null && isHexKey(singleNormalized)) {
                    FuzzAssertions.assertNull(
                        "hex-only clipboard must be rejected when allowHex is false",
                        plausible,
                    )
                } else {
                    val tokens = RecipientReference.tokenize(trimmed)
                    if (tokens.size > 1 &&
                        tokens.any { token ->
                            val normalized = RecipientReference.normalize(token)
                            normalized != null && isHexKey(normalized)
                        }
                    ) {
                        FuzzAssertions.assertNull(
                            "multi-token clipboard with hex must be rejected when allowHex is false",
                            plausible,
                        )
                    }
                }
            }
        }
    }

    private fun buildProfileInput(data: FuzzedDataProvider): String {
        if (data.consumeBoolean()) {
            return data.consumeBoundedUtf8()
        }
        val scheme = data.pickFrom(FuzzGrammar.uriSchemes)
        val host = data.pickFrom(FuzzGrammar.profileHosts + listOf("profile", sampleNpub, "example.com"))
        val npub = if (data.consumeBoolean()) sampleNpub else "npub1" + randomBech32Body(data)
        val separator = data.pickFrom(listOf("://", ":", "%3A%2F%2F", "%2F"))
        val pathPrefix = data.pickFrom(listOf("profile/", "profile%2F", ""))
        return when (scheme) {
            "nostr" -> "nostr:$npub"
            "http", "https" -> "$scheme://$host/$pathPrefix$npub"
            else -> "$scheme$separator$pathPrefix$npub"
        }
    }

    private fun randomBech32Body(data: FuzzedDataProvider): String {
        val length = data.consumeInt(1, 80)
        return buildString {
            repeat(length) {
                append(FuzzGrammar.bech32BodyChars[data.consumeInt(0, FuzzGrammar.bech32BodyChars.length - 1)])
            }
        }
    }

    private fun isValidNormalizedReference(value: String): Boolean {
        val validNpub =
            value.length == 63 &&
                value.startsWith("npub1") &&
                value.substring(5).all { it in FuzzGrammar.bech32BodyChars }
        return validNpub || isHexKey(value)
    }

    private fun isHexKey(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
