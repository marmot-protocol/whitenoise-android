package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarmotAttachmentAcquisitionPolicyTest {
    /** Automatic acquisition is disabled while every native storage limit remains unchanged. */
    @Test
    fun automaticPolicyIsDisabledWithoutChangingLimits() =
        runTest {
            val original = policy(automatic = true)
            val writes = mutableListOf<Pair<String, AttachmentDownloadPolicyFfi>>()

            enforceAppOwnedAttachmentAcquisitionPolicy(
                accountRefs = listOf("personal"),
                readPolicy = { original },
                writePolicy = { accountRef, updated -> writes += accountRef to updated },
            )

            val (accountRef, updated) = writes.single()
            assertEquals("personal", accountRef)
            assertEquals(original.copy(automatic = false), updated)
        }

    /** An already-disabled account is not rewritten during startup or account refresh. */
    @Test
    fun disabledPolicyDoesNotCauseAWrite() =
        runTest {
            val writes = mutableListOf<AttachmentDownloadPolicyFfi>()

            enforceAppOwnedAttachmentAcquisitionPolicy(
                accountRefs = listOf("personal"),
                readPolicy = { policy(automatic = false) },
                writePolicy = { _, updated -> writes += updated },
            )

            assertTrue(writes.isEmpty())
        }

    /** Duplicate account rows are collapsed before native policy I/O. */
    @Test
    fun duplicateAccountsAreProcessedOnce() =
        runTest {
            val reads = mutableListOf<String>()

            enforceAppOwnedAttachmentAcquisitionPolicy(
                accountRefs = listOf("personal", "work", "personal"),
                readPolicy = { accountRef ->
                    reads += accountRef
                    policy(automatic = false)
                },
                writePolicy = { _, _ -> error("disabled policy must not be rewritten") },
            )

            assertEquals(listOf("personal", "work"), reads)
        }

    /** Builds a policy whose non-automatic values make accidental resets visible. */
    private fun policy(automatic: Boolean) =
        AttachmentDownloadPolicyFfi(
            automatic = automatic,
            retainedBytes = 2_000uL,
            diskReserve = 300uL,
            transferLimit = 40uL,
        )
}
