package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.amber.ActivityResultOutcome
import dev.ipf.whitenoise.android.amber.ContentRowOutcome
import dev.ipf.whitenoise.android.amber.Nip55Pure
import dev.ipf.whitenoise.android.amber.SignerOp
import dev.ipf.whitenoise.android.amber.parseActivityResult
import dev.ipf.whitenoise.android.amber.parseContentRow
import dev.ipf.whitenoise.android.amber.signedEventPubkey
import dev.ipf.whitenoise.android.amber.signedEventPubkeyMismatchReason
import dev.ipf.whitenoise.android.amber.signerPackageEchoMismatchReason
import dev.ipf.whitenoise.android.amber.trustedSignerPackageFailureReason
import org.junit.jupiter.api.Tag

@Tag("fuzz-nip55")
class Nip55SignerProtocolFuzzTest {
    @DictionaryEntries(
        "get_public_key",
        "sign_event",
        "nip04_encrypt",
        "result",
        "event",
        "package",
        "rejected",
    )
    @DictionaryFile(resourcePath = "/fuzz-grammar.dict")
    @FuzzTest
    fun fuzzNip55SignerProtocol(data: FuzzedDataProvider) {
        when (Nip55Subtarget.fromId(data.consumeSubtarget(Nip55Subtarget.COUNT))) {
            Nip55Subtarget.ParseContentRow -> fuzzParseContentRow(data)
            Nip55Subtarget.ParseActivityResult -> fuzzParseActivityResult(data)
            Nip55Subtarget.SignedEventPubkeyHelpers -> fuzzSignedEventPubkeyHelpers(data)
            Nip55Subtarget.IntentFallbackBudget -> fuzzIntentFallbackBudget(data)
        }
    }

    private fun fuzzParseContentRow(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsBytes()
        val direct = raw.decodeToString(throwOnInvalidSequence = false)
        val parts = direct.split('|', limit = 4)
        val directOp = parts.firstOrNull()?.let { type -> SignerOp.entries.firstOrNull { it.intentType == type } }
        if (parts.size == 4 && directOp != null) {
            val rejected = parts[1] == "1"
            val resultColumn = parts[2].ifBlank { null }
            val eventColumn = parts[3].ifBlank { null }
            val outcome =
                parseContentRow(
                    op = directOp,
                    rejected = rejected,
                    resultColumn = resultColumn,
                    eventColumn = eventColumn,
                )
            assertContentRowProperties(directOp, rejected, resultColumn, eventColumn, outcome)
            return
        }

        val structured = ByteArrayFuzzedDataProvider(raw)
        val op = SignerOp.entries[structured.consumeInt(0, SignerOp.entries.size - 1)]
        val rejected = structured.consumeBoolean()
        val resultColumn = consumeOptionalFramedString(structured)
        val eventColumn = consumeOptionalFramedString(structured)

        val outcome =
            parseContentRow(
                op = op,
                rejected = rejected,
                resultColumn = resultColumn,
                eventColumn = eventColumn,
            )
        assertContentRowProperties(op, rejected, resultColumn, eventColumn, outcome)
    }

    private fun fuzzParseActivityResult(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsBytes()
        val direct = raw.decodeToString(throwOnInvalidSequence = false)
        val parts = direct.split('|', limit = 6)
        val directOp = parts.firstOrNull()?.let { type -> SignerOp.entries.firstOrNull { it.intentType == type } }
        if (parts.size == 6 && directOp != null) {
            val resultOk = parts[1] == "1"
            val rejected = parts[2] == "1"
            val resultExtra = parts[3].ifBlank { null }
            val eventExtra = parts[4].ifBlank { null }
            val packageExtra = parts[5].ifBlank { null }
            val outcome =
                parseActivityResult(
                    op = directOp,
                    resultOk = resultOk,
                    rejected = rejected,
                    resultExtra = resultExtra,
                    eventExtra = eventExtra,
                    packageExtra = packageExtra,
                )
            assertActivityResultProperties(
                directOp,
                resultOk,
                rejected,
                resultExtra,
                eventExtra,
                packageExtra,
                outcome,
            )
            return
        }

        val structured = ByteArrayFuzzedDataProvider(raw)
        val op = SignerOp.entries[structured.consumeInt(0, SignerOp.entries.size - 1)]
        val resultOk = structured.consumeBoolean()
        val rejected = structured.consumeBoolean()
        val resultExtra = consumeOptionalFramedString(structured)
        val eventExtra = consumeOptionalFramedString(structured)
        val packageExtra = consumeOptionalFramedString(structured)

        val outcome =
            parseActivityResult(
                op = op,
                resultOk = resultOk,
                rejected = rejected,
                resultExtra = resultExtra,
                eventExtra = eventExtra,
                packageExtra = packageExtra,
            )
        assertActivityResultProperties(
            op,
            resultOk,
            rejected,
            resultExtra,
            eventExtra,
            packageExtra,
            outcome,
        )
    }

    private fun fuzzSignedEventPubkeyHelpers(data: FuzzedDataProvider) {
        val eventJson =
            if (data.remainingBytes() == 0) {
                data.consumeBoundedUtf8()
            } else {
                data.consumeFramedString().value
            }
        val expectedPubkey =
            if (data.remainingBytes() == 0) {
                ""
            } else {
                data.consumeFramedString().value
            }
        val handledPackage = consumeOptionalFramedString(data)
        val echoedPackage = consumeOptionalFramedString(data)

        signedEventPubkey(eventJson)
        signedEventPubkeyMismatchReason(eventJson, expectedPubkey)
        signerPackageEchoMismatchReason(echoedPackage, handledPackage ?: "com.example.signer")
        trustedSignerPackageFailureReason(handledPackage, echoedPackage)

        val mismatch = signedEventPubkeyMismatchReason(eventJson, expectedPubkey)
        val pubkey = signedEventPubkey(eventJson)
        if (pubkey != null && expectedPubkey.isNotBlank()) {
            if (pubkey.equals(expectedPubkey, ignoreCase = true)) {
                FuzzAssertions.assertNull("matching pubkey must not report mismatch", mismatch)
            } else {
                FuzzAssertions.assertTrue(
                    "pubkey mismatch reason must be fixed",
                    mismatch == "signed event pubkey mismatch",
                )
            }
        }
        if (pubkey == null && expectedPubkey.isNotBlank()) {
            FuzzAssertions.assertTrue(
                "missing pubkey must report mismatch reason",
                mismatch == "signed event missing pubkey",
            )
        }

        if (!echoedPackage.isNullOrBlank() && !handledPackage.isNullOrBlank()) {
            val echoMismatch = signerPackageEchoMismatchReason(echoedPackage, handledPackage)
            if (echoedPackage == handledPackage) {
                FuzzAssertions.assertNull("matching signer package must not report mismatch", echoMismatch)
            } else {
                FuzzAssertions.assertTrue(
                    "signer package mismatch reason must be fixed",
                    echoMismatch == "signer package mismatch",
                )
            }
        }
        if (handledPackage.isNullOrBlank()) {
            FuzzAssertions.assertTrue(
                "missing handled signer package must fail trusted validation",
                trustedSignerPackageFailureReason(handledPackage, echoedPackage) == "missing handled signer package",
            )
        }
    }

    private fun fuzzIntentFallbackBudget(data: FuzzedDataProvider) {
        val direct = data.consumeParserInput()
        val content =
            if (direct.isNotEmpty()) {
                direct
            } else {
                data.consumeBoundedUtf8(FuzzBounds.MAX_STRING_BYTES)
            }
        val fits = Nip55Pure.contentFitsIntentFallbackBudget(content)
        val byteSize = content.toByteArray(Charsets.UTF_8).size
        if (byteSize <= Nip55Pure.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES) {
            FuzzAssertions.assertTrue("content within budget must fit intent fallback", fits)
        } else {
            FuzzAssertions.assertFalse("content over budget must not fit intent fallback", fits)
        }
    }

    private fun consumeOptionalFramedString(data: FuzzedDataProvider): String? {
        if (data.remainingBytes() == 0) return null
        return data.consumeFramedString().value.ifBlank { null }
    }

    private fun assertContentRowProperties(
        op: SignerOp,
        rejected: Boolean,
        resultColumn: String?,
        eventColumn: String?,
        outcome: ContentRowOutcome,
    ) {
        if (rejected) {
            FuzzAssertions.assertTrue(
                "rejected rows must map to Rejected",
                outcome == ContentRowOutcome.Rejected,
            )
            return
        }
        val resultValue = resultColumn?.takeIf { it.isNotBlank() }
        val eventValue = eventColumn?.takeIf { it.isNotBlank() }
        when (op) {
            SignerOp.SignEvent -> {
                if (eventValue != null) {
                    FuzzAssertions.assertEquals(
                        "sign_event content row must read event column",
                        ContentRowOutcome.Value(eventValue),
                        outcome,
                    )
                } else {
                    FuzzAssertions.assertTrue(
                        "sign_event without event column must be unavailable",
                        outcome == ContentRowOutcome.Unavailable,
                    )
                }
                if (resultValue != null && eventValue == null) {
                    FuzzAssertions.assertTrue(
                        "sign_event must not accept result column alone",
                        outcome == ContentRowOutcome.Unavailable,
                    )
                }
            }
            else -> {
                if (resultValue != null) {
                    FuzzAssertions.assertEquals(
                        "content row must read result column",
                        ContentRowOutcome.Value(resultValue),
                        outcome,
                    )
                } else {
                    FuzzAssertions.assertTrue(
                        "blank result column must be unavailable",
                        outcome == ContentRowOutcome.Unavailable,
                    )
                }
                if (eventValue != null && resultValue == null) {
                    FuzzAssertions.assertTrue(
                        "non-sign_event must not accept event column alone",
                        outcome == ContentRowOutcome.Unavailable,
                    )
                }
            }
        }
        if (outcome is ContentRowOutcome.Value) {
            FuzzAssertions.assertTrue("content row value must not be blank", outcome.value.isNotBlank())
        }
    }

    private fun assertActivityResultProperties(
        op: SignerOp,
        resultOk: Boolean,
        rejected: Boolean,
        resultExtra: String?,
        eventExtra: String?,
        packageExtra: String?,
        outcome: ActivityResultOutcome,
    ) {
        if (!resultOk || rejected) {
            FuzzAssertions.assertTrue(
                "failed activity results must map to Rejected",
                outcome == ActivityResultOutcome.Rejected,
            )
            return
        }
        val resultValue = resultExtra?.takeIf { it.isNotBlank() }
        val eventValue = eventExtra?.takeIf { it.isNotBlank() }
        when (op) {
            SignerOp.GetPublicKey -> {
                if (resultValue != null) {
                    FuzzAssertions.assertEquals(
                        "get_public_key must read result extra",
                        ActivityResultOutcome.PublicKey(resultValue, packageExtra?.takeIf { it.isNotBlank() }),
                        outcome,
                    )
                } else {
                    FuzzAssertions.assertTrue(
                        "missing public key must be malformed",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
                if (eventValue != null && resultValue == null) {
                    FuzzAssertions.assertTrue(
                        "get_public_key must not accept event extra alone",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
            }
            SignerOp.SignEvent -> {
                if (eventValue != null) {
                    FuzzAssertions.assertEquals(
                        "sign_event must read event extra",
                        ActivityResultOutcome.Value(eventValue, packageExtra?.takeIf { it.isNotBlank() }),
                        outcome,
                    )
                } else {
                    FuzzAssertions.assertTrue(
                        "missing signed event must be malformed",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
                if (resultValue != null && eventValue == null) {
                    FuzzAssertions.assertTrue(
                        "sign_event must not accept result extra alone",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
            }
            else -> {
                if (resultValue != null) {
                    FuzzAssertions.assertEquals(
                        "crypto activity result must read result extra",
                        ActivityResultOutcome.Value(resultValue, packageExtra?.takeIf { it.isNotBlank() }),
                        outcome,
                    )
                } else {
                    FuzzAssertions.assertTrue(
                        "missing crypto result must be malformed",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
                if (eventValue != null && resultValue == null) {
                    FuzzAssertions.assertTrue(
                        "crypto ops must not accept event extra alone",
                        outcome is ActivityResultOutcome.Malformed,
                    )
                }
            }
        }
        when (outcome) {
            is ActivityResultOutcome.PublicKey ->
                FuzzAssertions.assertTrue("public key outcome must not be blank", outcome.pubkey.isNotBlank())
            is ActivityResultOutcome.Value ->
                FuzzAssertions.assertTrue("activity result value must not be blank", outcome.value.isNotBlank())
            is ActivityResultOutcome.Malformed ->
                FuzzAssertions.assertTrue("malformed outcome reason must not be blank", outcome.reason.isNotBlank())
            ActivityResultOutcome.Rejected -> Unit
        }
    }
}
