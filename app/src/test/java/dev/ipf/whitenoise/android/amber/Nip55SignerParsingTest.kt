package dev.ipf.whitenoise.android.amber

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the side-effect-free NIP-55 wire contract: the `onActivityResult`
 * parser, the ContentResolver-row parser, and the default permissions payload.
 * These are the parts that must stay faithful to the reference Flutter plugin,
 * and they carry no Android dependencies so they run on the plain JVM.
 */
class Nip55SignerParsingTest {
    @Test
    fun getPublicKeyResultReadsPubkeyAndPackage() {
        val outcome =
            parseActivityResult(
                SignerOp.GetPublicKey,
                resultOk = true,
                rejected = false,
                resultExtra = "npub1abc",
                eventExtra = null,
                packageExtra = "com.example.signer",
            )
        assertEquals(ActivityResultOutcome.PublicKey("npub1abc", "com.example.signer"), outcome)
    }

    @Test
    fun getPublicKeyResultToleratesMissingPackage() {
        val outcome =
            parseActivityResult(
                SignerOp.GetPublicKey,
                resultOk = true,
                rejected = false,
                resultExtra = "npub1abc",
                eventExtra = null,
                packageExtra = null,
            )
        assertEquals(ActivityResultOutcome.PublicKey("npub1abc", null), outcome)
    }

    @Test
    fun getPublicKeyResultWithBlankPubkeyIsMalformed() {
        val outcome =
            parseActivityResult(
                SignerOp.GetPublicKey,
                resultOk = true,
                rejected = false,
                resultExtra = "  ",
                eventExtra = null,
                packageExtra = "com.example.signer",
            )
        assertTrue(outcome is ActivityResultOutcome.Malformed)
    }

    @Test
    fun signEventResultReturnsSignedEventFromEventExtra() {
        val signed = """{"id":"deadbeef","sig":"abc"}"""
        val outcome =
            parseActivityResult(
                SignerOp.SignEvent,
                resultOk = true,
                rejected = false,
                resultExtra = "abc", // the signature, NOT what we return
                eventExtra = signed,
                packageExtra = null,
            )
        assertEquals(ActivityResultOutcome.Value(signed, null), outcome)
    }

    @Test
    fun signEventResultCarriesPackageEchoForValidation() {
        val signed = """{"pubkey":"abc","id":"deadbeef","sig":"abc"}"""
        val outcome =
            parseActivityResult(
                SignerOp.SignEvent,
                resultOk = true,
                rejected = false,
                resultExtra = "abc",
                eventExtra = signed,
                packageExtra = "com.example.signer",
            )
        assertEquals(ActivityResultOutcome.Value(signed, "com.example.signer"), outcome)
    }

    @Test
    fun signEventResultWithoutEventExtraIsMalformed() {
        val outcome =
            parseActivityResult(
                SignerOp.SignEvent,
                resultOk = true,
                rejected = false,
                resultExtra = "abc",
                eventExtra = null,
                packageExtra = null,
            )
        assertTrue(outcome is ActivityResultOutcome.Malformed)
    }

    @Test
    fun cryptoResultReadsResultExtra() {
        val outcome =
            parseActivityResult(
                SignerOp.Nip44Decrypt,
                resultOk = true,
                rejected = false,
                resultExtra = "plaintext",
                eventExtra = null,
                packageExtra = null,
            )
        assertEquals(ActivityResultOutcome.Value("plaintext", null), outcome)
    }

    @Test
    fun signedEventPubkeyReadsPubkeyOnlyFromValidEventJson() {
        assertEquals("abc", signedEventPubkey("""{"pubkey":"abc","content":"hello"}"""))
        assertEquals(null, signedEventPubkey("""{"content":"hello"}"""))
        assertEquals(null, signedEventPubkey("not-json"))
    }

    @Test
    fun signedEventPubkeyValidationRejectsMissingOrMismatchedPubkey() {
        assertEquals(null, signedEventPubkeyMismatchReason("""{"pubkey":"ABC"}""", "abc"))
        assertEquals("signed event missing pubkey", signedEventPubkeyMismatchReason("""{"content":"hello"}""", "abc"))
        assertEquals("signed event pubkey mismatch", signedEventPubkeyMismatchReason("""{"pubkey":"def"}""", "abc"))
    }

    @Test
    fun packageEchoValidationRejectsMismatchedSignerPackage() {
        assertEquals(null, signerPackageEchoMismatchReason(null, "com.example.signer"))
        assertEquals(null, signerPackageEchoMismatchReason("com.example.signer", "com.example.signer"))
        assertEquals("signer package mismatch", signerPackageEchoMismatchReason("com.evil.signer", "com.example.signer"))
    }

    @Test
    fun trustedSignerPackageRequiresTheHandledPackageAndValidatesTheEcho() {
        assertEquals("missing handled signer package", trustedSignerPackageFailureReason(null, "com.example.signer"))
        assertEquals(null, trustedSignerPackageFailureReason("com.example.signer", null))
        assertEquals(null, trustedSignerPackageFailureReason("com.example.signer", "com.example.signer"))
        assertEquals(
            "signer package mismatch",
            trustedSignerPackageFailureReason("com.example.signer", "com.evil.signer"),
        )
    }

    @Test
    fun nonOkResultIsRejectedForEveryOp() {
        SignerOp.entries.forEach { op ->
            val outcome =
                parseActivityResult(
                    op,
                    resultOk = false,
                    rejected = false,
                    resultExtra = "something",
                    eventExtra = "something",
                    packageExtra = "com.example.signer",
                )
            assertEquals("op=$op", ActivityResultOutcome.Rejected, outcome)
        }
    }

    @Test
    fun resultOkWithRejectedFlagIsRejectedForEveryOp() {
        SignerOp.entries.forEach { op ->
            val outcome =
                parseActivityResult(
                    op,
                    resultOk = true,
                    rejected = true,
                    resultExtra = null,
                    eventExtra = null,
                    packageExtra = null,
                )
            assertEquals("op=$op", ActivityResultOutcome.Rejected, outcome)
        }
    }

    @Test
    fun resultOkWithRejectedFlagTakesPrecedenceOverValueExtras() {
        val outcome =
            parseActivityResult(
                SignerOp.SignEvent,
                resultOk = true,
                rejected = true,
                resultExtra = "abc",
                eventExtra = """{"id":"deadbeef","sig":"abc"}""",
                packageExtra = "com.example.signer",
            )
        assertEquals(ActivityResultOutcome.Rejected, outcome)
    }

    @Test
    fun contentRowRejectedIsTerminal() {
        val outcome =
            parseContentRow(
                SignerOp.Nip44Encrypt,
                rejected = true,
                resultColumn = "ignored-because-rejected",
                eventColumn = null,
            )
        assertEquals(ContentRowOutcome.Rejected, outcome)
    }

    @Test
    fun contentRowSignEventReadsEventColumn() {
        val signed = """{"id":"beef","sig":"z"}"""
        val outcome =
            parseContentRow(
                SignerOp.SignEvent,
                rejected = false,
                resultColumn = "signature-only",
                eventColumn = signed,
            )
        assertEquals(ContentRowOutcome.Value(signed), outcome)
    }

    @Test
    fun contentRowSignEventWithoutEventColumnFallsBackToIntent() {
        val outcome =
            parseContentRow(
                SignerOp.SignEvent,
                rejected = false,
                resultColumn = "signature-only",
                eventColumn = null,
            )
        assertEquals(ContentRowOutcome.Unavailable, outcome)
    }

    @Test
    fun contentRowCryptoReadsResultColumn() {
        val outcome =
            parseContentRow(
                SignerOp.Nip04Decrypt,
                rejected = false,
                resultColumn = "plaintext",
                eventColumn = null,
            )
        assertEquals(ContentRowOutcome.Value("plaintext"), outcome)
    }

    @Test
    fun contentRowBlankValueFallsBackToIntent() {
        val outcome =
            parseContentRow(
                SignerOp.Nip44Encrypt,
                rejected = false,
                resultColumn = "   ",
                eventColumn = null,
            )
        assertEquals(ContentRowOutcome.Unavailable, outcome)
    }

    @Test
    fun contentWithinIntentFallbackBudgetPasses() {
        assertTrue(Nip55.contentFitsIntentFallbackBudget("a".repeat(1_000)))
    }

    @Test
    fun contentAtIntentFallbackBudgetLimitPasses() {
        val content = ByteArray(Nip55.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES) { 'a'.code.toByte() }
        assertTrue(Nip55.contentFitsIntentFallbackBudget(String(content, Charsets.UTF_8)))
    }

    @Test
    fun contentExceedingIntentFallbackBudgetFails() {
        val content = ByteArray(Nip55.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES + 1) { 'a'.code.toByte() }
        assertFalse(Nip55.contentFitsIntentFallbackBudget(String(content, Charsets.UTF_8)))
    }

    @Test
    fun multiByteUtf8ContentMeasuredByBytesNotChars() {
        val content = "漢".repeat(90_000)

        assertTrue(content.length < Nip55.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES)
        assertTrue(content.toByteArray(Charsets.UTF_8).size > Nip55.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES)
        assertFalse(Nip55.contentFitsIntentFallbackBudget(content))
    }

    @Test
    fun defaultPermissionsMirrorTheFlutterReferenceList() {
        val permissions = JSONArray(Nip55.defaultPermissionsJson())

        val signEventKinds = mutableListOf<Int>()
        val bareTypes = mutableListOf<String>()
        for (index in 0 until permissions.length()) {
            val entry = permissions.getJSONObject(index)
            when (val type = entry.getString("type")) {
                "sign_event" -> signEventKinds += entry.getInt("kind")
                else -> bareTypes += type
            }
        }

        assertEquals(
            listOf(30443, 443, 444, 445, 1059, 10002, 10050, 10051),
            signEventKinds,
        )
        assertEquals(listOf("nip44_encrypt", "nip44_decrypt"), bareTypes)
    }
}
