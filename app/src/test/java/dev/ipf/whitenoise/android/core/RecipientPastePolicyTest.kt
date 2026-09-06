package dev.ipf.whitenoise.android.core

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import dev.ipf.whitenoise.android.ui.chats.newchat.replaceSelectionForRecipientPaste
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientPastePolicyTest {
    @Test
    fun extractsOneChecksumValidNpubFromProseAndCanonicalizesIt() {
        assertEquals(
            RecipientPasteDecision.Accept(ALICE_NPUB),
            RecipientPastePolicy.evaluate(listOf("Alice shared ($ALICE_NPUB).")),
        )
        assertEquals(
            RecipientPasteDecision.Accept(ALICE_NPUB),
            RecipientPastePolicy.evaluate(listOf("nostr:${ALICE_NPUB.uppercase()}")),
        )
    }

    @Test
    fun preservesSupportedWholeFieldInputs() {
        val upperHex = "AB".repeat(32)

        assertEquals(
            RecipientPasteDecision.Accept(ALICE_NPUB),
            RecipientPastePolicy.evaluate(listOf("marmot://profile/$ALICE_NPUB?from=qr")),
        )
        assertEquals(
            RecipientPasteDecision.Accept(upperHex.lowercase()),
            RecipientPastePolicy.evaluate(listOf(upperHex)),
        )
        assertEquals(
            RecipientPasteDecision.Accept("alice@example.com"),
            RecipientPastePolicy.evaluate(listOf(" alice@example.com ")),
        )
    }

    @Test
    fun acceptsBareAndNostrNpubAcrossAsciiAndUnicodePunctuation() {
        listOf(
            ALICE_NPUB,
            "nostr:$ALICE_NPUB",
            "($ALICE_NPUB),",
            "—$ALICE_NPUB—",
            "“$ALICE_NPUB”",
        ).forEach { input ->
            assertEquals(
                input,
                RecipientPasteDecision.Accept(ALICE_NPUB),
                RecipientPastePolicy.evaluate(listOf(input)),
            )
        }
    }

    @Test
    fun leavesOrdinaryNamesForNativePasteHandling() {
        assertEquals(
            RecipientPasteDecision.PassThrough("Alice Example"),
            RecipientPastePolicy.evaluate(listOf("Alice Example")),
        )
    }

    @Test
    fun rejectsChecksumLengthCaseAndOtherNip19Failures() {
        val badChecksum = ALICE_NPUB.dropLast(1) + if (ALICE_NPUB.last() == 'q') 'p' else 'q'
        val mixedCase = ALICE_NPUB.replaceFirstChar(Char::uppercaseChar)
        val invalidInputs =
            listOf(
                badChecksum,
                ALICE_NPUB.dropLast(1),
                mixedCase,
                "nprofile1qqspyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3qy88s9",
                "note1not-a-recipient",
                "nevent1not-a-recipient",
                "naddr1not-a-recipient",
                "nsec1not-a-recipient",
            )

        invalidInputs.forEach { input ->
            assertEquals(
                RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
                RecipientPastePolicy.evaluate(listOf(input)),
            )
        }
    }

    @Test
    fun rejectsIdentifierBoundToUnicodeWordCharacters() {
        listOf(
            "x$ALICE_NPUB",
            "β$ALICE_NPUB",
            "_$ALICE_NPUB",
            "-$ALICE_NPUB",
            "${ALICE_NPUB}x",
            "${ALICE_NPUB}β",
            "${ALICE_NPUB}_",
            "${ALICE_NPUB}-",
        ).forEach { input ->
            assertEquals(
                RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
                RecipientPastePolicy.evaluate(listOf(input)),
            )
        }
    }

    @Test
    fun acceptsPunctuationBoundariesAndDeduplicatesTheSameIdentity() {
        assertEquals(
            RecipientPasteDecision.Accept(ALICE_NPUB),
            RecipientPastePolicy.evaluate(listOf("[$ALICE_NPUB]", "again: <$ALICE_NPUB>")),
        )
    }

    @Test
    fun rejectsMultipleDistinctIdentitiesAcrossClipboardItems() {
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(listOf(ALICE_NPUB, BOB_NPUB)),
        )
    }

    @Test
    fun rejectsMixedAndDistinctExactIdentityItemsAtomically() {
        val hex = "ab".repeat(32)
        listOf(
            listOf("alice@example.com", "bob@example.com"),
            listOf(hex, "cd".repeat(32)),
            listOf(ALICE_NPUB, "alice@example.com"),
            listOf(ALICE_NPUB, hex),
        ).forEach { items ->
            assertEquals(
                items.joinToString(),
                RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
                RecipientPastePolicy.evaluate(items),
            )
        }
    }

    @Test
    fun rejectsCrossTypeIdentityAmbiguityInsideOneClipboardItem() {
        val hex = "ab".repeat(32)
        listOf(
            "$ALICE_NPUB or bob@example.com",
            "$ALICE_NPUB / $hex",
        ).forEach { item ->
            assertEquals(
                item,
                RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
                RecipientPastePolicy.evaluate(listOf(item)),
            )
        }
    }

    @Test
    fun appliesTheSameIdentityPolicyToUnicodeNip05Tokens() {
        assertEquals(
            RecipientPasteDecision.Accept(UNICODE_NIP05),
            RecipientPastePolicy.evaluate(listOf(UNICODE_NIP05)),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(listOf("Contact $UNICODE_NIP05")),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(listOf("$ALICE_NPUB or $UNICODE_NIP05")),
        )
    }

    @Test
    fun acceptsExactNip05LocalPartsThatOnlyResembleNip19Identifiers() {
        listOf(
            "npub1alice@example.com",
            "note1alice@example.com",
        ).forEach { input ->
            assertEquals(
                input,
                RecipientPasteDecision.Accept(input),
                RecipientPastePolicy.evaluate(listOf(input)),
            )
        }
    }

    @Test
    fun rejectsExactNip05ValuesThatEmbedChecksumValidNip19Identifiers() {
        listOf(
            "$ALICE_NPUB@example.com",
            "$VALID_NEVENT@example.com",
            "${ALICE_NPUB.replaceFirstChar(Char::uppercaseChar)}@example.com",
            "${VALID_NSEC.replaceFirstChar(Char::uppercaseChar)}@example.com",
        ).forEach { input ->
            assertEquals(
                input,
                RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
                RecipientPastePolicy.evaluate(listOf(input)),
            )
        }
    }

    @Test
    fun rejectsAProfileLinkContainingASecondIdentity() {
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(listOf("marmot://profile/$ALICE_NPUB?other=$BOB_NPUB")),
        )
    }

    @Test
    fun rejectsMalformedIdentityEvenBesideAValidOne() {
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(listOf("$ALICE_NPUB and npub1broken")),
        )
    }

    @Test
    fun enforcesCumulativeUtf8LimitWithoutTruncating() {
        val asciiAtLimit = "a".repeat(RecipientPastePolicy.MAX_UTF8_BYTES)
        val multibyteAtLimit = "é".repeat(RecipientPastePolicy.MAX_UTF8_BYTES / 2)

        assertEquals(
            RecipientPasteDecision.PassThrough(asciiAtLimit),
            RecipientPastePolicy.evaluate(listOf(asciiAtLimit)),
        )
        assertEquals(
            RecipientPasteDecision.PassThrough(multibyteAtLimit),
            RecipientPastePolicy.evaluate(listOf(multibyteAtLimit)),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(listOf("a".repeat(RecipientPastePolicy.MAX_UTF8_BYTES + 1))),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(listOf("é".repeat(RecipientPastePolicy.MAX_UTF8_BYTES / 2) + "a")),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(listOf("a".repeat(8_192), "b".repeat(8_193))),
        )
        assertEquals(
            RecipientPasteDecision.PassThrough("${"a".repeat(RecipientPastePolicy.MAX_UTF8_BYTES - 2)}\nb"),
            RecipientPastePolicy.evaluate(
                listOf("a".repeat(RecipientPastePolicy.MAX_UTF8_BYTES - 2), "b"),
            ),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(
                listOf("a".repeat(RecipientPastePolicy.MAX_UTF8_BYTES - 1), "b"),
            ),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(listOf(ALICE_NPUB + "x".repeat(RecipientPastePolicy.MAX_UTF8_BYTES))),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge),
            RecipientPastePolicy.evaluate(listOf("x".repeat(RecipientPastePolicy.MAX_UTF8_BYTES), ALICE_NPUB)),
        )
    }

    @Test
    fun rejectsEmptyAndUnboundedItemCounts() {
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(emptyList()),
        )
        assertEquals(
            RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous),
            RecipientPastePolicy.evaluate(List(RecipientPastePolicy.MAX_ITEMS + 1) { "" }),
        )
    }

    @Test
    fun acceptedPasteReplacesSelectionAndMovesCaretAfterCanonicalValue() {
        val state = TextFieldState("send placeholder later", TextRange(5, 16))

        state.replaceSelectionForRecipientPaste(ALICE_NPUB)

        assertEquals("send $ALICE_NPUB later", state.text.toString())
        assertEquals(TextRange(5 + ALICE_NPUB.length), state.selection)
    }

    @Test
    fun rejectedPasteRequiresNoTextFieldMutation() {
        val state = TextFieldState("keep me", TextRange(2, 6))
        val decision = RecipientPastePolicy.evaluate(listOf("npub1broken"))

        assertEquals(RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous), decision)
        assertEquals("keep me", state.text.toString())
        assertEquals(TextRange(2, 6), state.selection)
    }

    private companion object {
        const val ALICE_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        const val BOB_NPUB = "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygse4sl3h"
        const val VALID_NEVENT = "nevent1qqsqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqradspk"
        const val VALID_NSEC = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"
        const val UNICODE_NIP05 = "δοκιμή@παράδειγμα.δοκιμή"
    }
}
