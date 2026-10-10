package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.NoteToSelfOpening
import dev.ipf.whitenoise.android.state.isNoteToSelfRoster
import dev.ipf.whitenoise.android.ui.chats.newchat.isNoteToSelfIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production opening coordinator: native lookup/retry and captured navigation lifetime. */
class NoteToSelfFlowTest {
    @Test fun canonicalIdentityUsesDecoderAndDoesNotCombineAccounts() {
        val decode: (String) -> String? = { if (it == "npub1self" || it == "nostr:npub1self") "ABCD" else null }
        assertTrue(isNoteToSelfIdentifier(" npub1self ", "abcd", decode))
        assertTrue(isNoteToSelfIdentifier("nostr:npub1self", "abcd", decode))
        assertFalse(isNoteToSelfIdentifier("npub1self", "other", decode))
        assertFalse(isNoteToSelfIdentifier("invalid", "abcd", decode))
        assertFalse(isNoteToSelfIdentifier("npub1self", null, decode))
    }

    @Test fun aSharedOrEmptyGroupIsNotPrivateNotes() {
        assertTrue(isNoteToSelfRoster(listOf("SELF"), "self"))
        assertFalse(isNoteToSelfRoster(emptyList(), "self"))
        assertFalse(isNoteToSelfRoster(listOf("self", "peer"), "self"))
    }

    @Test fun otherAccountsDoNotDiscardUnresolvedCanonicalRecovery() =
        runTest {
            val flow = NoteToSelfOpening()
            try {
                flow.open<String>("first:1", {}, { null }, { _, _ ->
                    throw MarmotKitException.CreatedGroupProjectionUnavailable("first-id")
                }, { it })
            } catch (_: MarmotKitException.CreatedGroupProjectionUnavailable) {
                // Visit another account before retrying the first.
            }
            val other = flow.open("second:1", {}, { null }, { _, _ -> "second-id" to "second-row" }, { it })
            assertEquals("second-row", other)
            assertEquals("first-id", flow.open("first:1", {}, { null }, { _, _ -> error("duplicate") }, { it }))
        }

    @Test fun canonicalCallbackSurvivesCancelledIoReturn() =
        runTest {
            val flow = NoteToSelfOpening()
            try {
                flow.open<String>("first:1", {}, { null }, { started, created ->
                    started()
                    created("canonical")
                    throw CancellationException("response discarded after commit")
                }, { it })
            } catch (_: CancellationException) {
                // Native response identity was retained before switching back to the cancelled UI job.
            }
            assertEquals("canonical", flow.open("first:1", {}, { null }, { _, _ -> error("duplicate") }, { it }))
        }

    @Test fun cancellationBeforeWriteDoesNotPoisonRetry() =
        runTest {
            val flow = NoteToSelfOpening()
            try {
                flow.open<String>(
                    "first:1",
                    {},
                    { null },
                    { _, _ -> throw CancellationException("not started") },
                    { it },
                )
            } catch (_: CancellationException) {
                // The write marker was never reached.
            }
            assertEquals("row", flow.open("first:1", {}, { null }, { _, _ -> "id" to "row" }, { it }))
        }

    @Test fun rapidEntryPointsReuseOneNativeGroup() =
        runTest {
            val flow = NoteToSelfOpening()
            val gate = CompletableDeferred<Unit>()
            var nativeId: String? = null
            var creates = 0

            suspend fun open() =
                flow.open("account:1", {}, { nativeId }, { started, _ ->
                    started()
                    creates++
                    gate.await()
                    nativeId = "canonical"
                    "canonical" to "new row"
                }, { "existing $it" })
            val first = async { open() }
            val second = async { open() }
            gate.complete(Unit)
            assertEquals("new row", first.await())
            assertEquals("existing canonical", second.await())
            assertEquals(1, creates)
        }

    @Test fun projectionFailureRetainsCanonicalIdEvenWithoutBroadRow() =
        runTest {
            val flow = NoteToSelfOpening()
            var creates = 0
            try {
                flow.open<String>("account:1", {}, { null }, { started, _ ->
                    started()
                    creates++
                    throw MarmotKitException.CreatedGroupProjectionUnavailable("canonical")
                }, { "opened $it" })
                error("Expected failure")
            } catch (_: MarmotKitException.CreatedGroupProjectionUnavailable) {
                // The targeted retry must not depend on the broad chat-list subscription.
            }
            assertEquals(
                "opened canonical",
                flow.open("account:1", {}, { null }, { _, _ ->
                    error("must not create again")
                }, { "opened $it" }),
            )
            assertEquals(1, creates)
        }

    @Test fun lookupFailureBeforeCreateCanRetryNormally() =
        runTest {
            val flow = NoteToSelfOpening()
            try {
                flow.open("account:1", {}, { error("read failed") }, { _, _ -> error("must not create") }, { it })
            } catch (_: IllegalStateException) {
                // No mutation began.
            }
            assertEquals("row", flow.open("account:1", {}, { null }, { _, _ -> "id" to "row" }, { it }))
        }

    @Test fun uncertainWriteRetriesOnlyNativeReconciliation() =
        runTest {
            val flow = NoteToSelfOpening()
            var calls = 0

            suspend fun attempt(existing: String?) =
                flow.open<String>("account:1", {}, { existing }, { started, _ ->
                    started()
                    calls++
                    throw IllegalStateException("unknown outcome")
                }, { "opened $it" })
            repeat(2) {
                try {
                    attempt(null)
                } catch (_: IllegalStateException) {
                    // Safe inline failure.
                }
            }
            assertEquals(1, calls)
            assertEquals("opened native-id", attempt("native-id"))
        }

    @Test fun staleCompletionNeverOpensAndPreservesCreatedRetryIdentity() =
        runTest {
            val flow = NoteToSelfOpening()
            var current = true
            val ensure = { if (!current) throw CancellationException("screen replaced") }
            try {
                flow.open("account:1", ensure, { null }, { started, _ ->
                    started()
                    current = false
                    "canonical" to "row"
                }, { it })
                error("stale result escaped")
            } catch (_: CancellationException) {
                // A subsequent screen for this same native owner can reopen the canonical group.
            }
            assertEquals("canonical", flow.open("account:1", {}, { null }, { _, _ -> error("duplicate") }, { it }))
            val other = flow.open("other-account:2", {}, { null }, { _, _ -> "other-id" to "other-row" }, { it })
            assertEquals("other-row", other)
        }
}
