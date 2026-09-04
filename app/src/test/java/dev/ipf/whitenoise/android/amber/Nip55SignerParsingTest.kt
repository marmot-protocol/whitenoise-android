package dev.ipf.whitenoise.android.amber

import dev.ipf.whitenoise.android.fuzz.FuzzSyntheticCorpusReplay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun replaysSyntheticFuzzCorpus() {
        FuzzSyntheticCorpusReplay.replaySuite(FuzzSyntheticCorpusReplay.Suite.Nip55SignerParsing)
    }

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
    fun typedLoginPermissionsCoverEverySignedSurfaceExactlyOnce() {
        assertEquals(
            listOf(
                SignerPermission(SignerOp.SignEvent, 450),
                SignerPermission(SignerOp.SignEvent, 30443),
                SignerPermission(SignerOp.SignEvent, 443),
                SignerPermission(SignerOp.SignEvent, 444),
                SignerPermission(SignerOp.SignEvent, 445),
                SignerPermission(SignerOp.SignEvent, 1059),
                SignerPermission(SignerOp.SignEvent, 10002),
                SignerPermission(SignerOp.SignEvent, 10050),
                SignerPermission(SignerOp.SignEvent, 10051),
                SignerPermission(SignerOp.Nip44Encrypt),
                SignerPermission(SignerOp.Nip44Decrypt),
            ),
            Nip55.LOGIN_PERMISSIONS,
        )
        assertEquals(Nip55.LOGIN_PERMISSIONS.size, Nip55.LOGIN_PERMISSIONS.toSet().size)
    }

    @Test
    fun loginPermissionPayloadRemainsByteExact() {
        val expected =
            """[{"kind":450,"type":"sign_event"},{"kind":30443,"type":"sign_event"},""" +
                """{"kind":443,"type":"sign_event"},{"kind":444,"type":"sign_event"},""" +
                """{"kind":445,"type":"sign_event"},{"kind":1059,"type":"sign_event"},""" +
                """{"kind":10002,"type":"sign_event"},{"kind":10050,"type":"sign_event"},""" +
                """{"kind":10051,"type":"sign_event"},{"type":"nip44_encrypt"},""" +
                """{"type":"nip44_decrypt"}]"""
        assertEquals(
            expected,
            Nip55.loginPermissionsJson(),
        )
    }

    @Test
    fun typedPermissionRejectsInvalidOperationKindPairs() {
        assertThrows(IllegalArgumentException::class.java) {
            SignerPermission(SignerOp.SignEvent)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignerPermission(SignerOp.Nip44Encrypt, kind = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignerPermission(SignerOp.GetPublicKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignerPermission(SignerOp.SignEvent, kind = -1)
        }
    }

    @Test
    fun aggregateResultsPreserveOrderIndependentIdsAndMixedDecisions() {
        val json =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "request-b")
                        .put("rejected", true),
                ).put(
                    JSONObject()
                        .put("id", "request-a")
                        .put("result", "approved-value"),
                ).toString()

        val parsed = parseAmberAggregateResults(json) as AmberAggregateParseOutcome.Parsed

        assertEquals(listOf("request-b", "request-a"), parsed.entries.map { it.id })
        assertTrue(parsed.entries.first().rejected)
        assertEquals("approved-value", parsed.entries.last().result)
        assertTrue(parsed.duplicateIds.isEmpty())
        assertEquals(0, parsed.malformedEntryCount)
    }

    @Test
    fun aggregateResultsExcludeDuplicateIdsWithoutDroppingOtherEntries() {
        val json =
            """
            [
                {"id":"duplicate","result":"first"},
                {"id":"safe","result":"safe-value"},
                {"id":"duplicate","result":"second"}
            ]
            """.trimIndent()

        val parsed = parseAmberAggregateResults(json) as AmberAggregateParseOutcome.Parsed

        assertEquals(listOf("safe"), parsed.entries.map { it.id })
        assertEquals(setOf("duplicate"), parsed.duplicateIds)
    }

    @Test
    fun aggregateResultsIgnoreMalformedEntriesAndKeepAddressableOnes() {
        val json =
            """
            [
                {"id":42,"result":"wrong-id-type"},
                {"id":"wrong-result-type","result":42},
                {"id":"known","result":"value"},
                "not-an-object"
            ]
            """.trimIndent()

        val parsed = parseAmberAggregateResults(json) as AmberAggregateParseOutcome.Parsed

        assertEquals(listOf("known"), parsed.entries.map { it.id })
        assertEquals(3, parsed.malformedEntryCount)
    }

    @Test
    fun aggregateResultsRejectMalformedOversizedAndOverCountEnvelopes() {
        assertEquals(AmberAggregateParseOutcome.Malformed, parseAmberAggregateResults("not-json"))
        assertEquals(
            AmberAggregateParseOutcome.Malformed,
            parseAmberAggregateResults(" ".repeat(Nip55.MAX_AGGREGATE_RESULTS_UTF8_BYTES + 1)),
        )
        val tooMany =
            JSONArray().apply {
                repeat(Nip55.MAX_GROUPED_APPROVALS + 1) { index ->
                    put(JSONObject().put("id", "request-$index").put("result", "value"))
                }
            }
        assertEquals(AmberAggregateParseOutcome.Malformed, parseAmberAggregateResults(tooMany.toString()))
    }

    @Test
    fun aggregateSignatureRebuildsOnlyAWellFormedSignedEvent() {
        val unsigned = """{"id":"event-id","pubkey":"abc","sig":""}"""
        val signature = "ab".repeat(64)

        val signed = checkNotNull(signedEventFromAggregate(unsigned, signature.uppercase()))

        assertEquals("event-id", JSONObject(signed).getString("id"))
        assertEquals(signature, JSONObject(signed).getString("sig"))
        assertEquals(null, signedEventFromAggregate(unsigned, "not-a-signature"))
        assertEquals(null, signedEventFromAggregate("not-json", signature))
    }

    @Test
    fun groupedApprovalCapabilityIsLimitedToAmber63AndNewer() {
        assertFalse(amberVersionSupportsGroupedApprovals(Nip55.AMBER_PACKAGE, "6.2.9"))
        assertTrue(amberVersionSupportsGroupedApprovals(Nip55.AMBER_PACKAGE, "6.3.0"))
        assertTrue(amberVersionSupportsGroupedApprovals(Nip55.AMBER_PACKAGE, "6.4.0-play"))
        assertTrue(amberVersionSupportsGroupedApprovals(Nip55.AMBER_DEBUG_PACKAGE, "7.0"))
        assertFalse(amberVersionSupportsGroupedApprovals(Nip55.AMBER_PACKAGE, null))
        assertFalse(amberVersionSupportsGroupedApprovals("com.example.signer", "99.0.0"))
    }
}
