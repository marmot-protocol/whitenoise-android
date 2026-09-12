package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.state.ChatListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises ownership around the actual canonical lookup/create/read state machine. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMessageSessionTest {
    /** A stale lookup cannot fall through to creation under the newly active account. */
    @Test fun accountSwitchDuringLookupNeverCreates() =
        runTest {
            var current = true
            val owner = NewMessageSession { current }
            val release = CompletableDeferred<Unit>()
            var creates = 0
            val attempt =
                async {
                    ownedAttempt(owner, lookup = {
                        release.await()
                        NewMessageDirectChatResolution(null, true)
                    }, create = {
                        creates++
                        "created"
                    })
                }
            try {
                runCurrent()
                current = false
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, creates)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Accepted native creation remains accepted, but a dismissed screen cannot start its projection read. */
    @Test fun disposalAfterCreateRejectsLateCanonicalResult() =
        runTest {
            val owner = NewMessageSession { true }
            val release = CompletableDeferred<Unit>()
            var creates = 0
            var reads = 0
            val attempt =
                async {
                    ownedAttempt(owner, create = {
                        creates++
                        release.await()
                        "created"
                    }, read = {
                        reads++
                        item()
                    })
                }
            try {
                runCurrent()
                owner.dispose()
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(1, creates)
                assertEquals(0, reads)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Late authoritative reads cannot produce an Open result after runtime teardown. */
    @Test fun teardownDuringProjectionCannotOpen() =
        runTest {
            var current = true
            val owner = NewMessageSession { current }
            val release = CompletableDeferred<Unit>()
            val attempt =
                async {
                    ownedAttempt(owner, read = {
                        release.await()
                        item()
                    })
                }
            try {
                runCurrent()
                current = false
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Existing authoritative DM reuse does not reach create or projection callbacks. */
    @Test fun knownChatReusesNativeLookupResult() =
        runTest {
            val existing = item()
            val result =
                ownedAttempt(
                    NewMessageSession { true },
                    lookup = { NewMessageDirectChatResolution(existing, false) },
                    create = { error("existing DM must not create") },
                    read = { error("existing DM must not reload newly created projection") },
                ) as StartChatAttemptResult.Open
            assertEquals(existing, result.item)
            assertFalse(result.newlyCreated)
        }

    /** A projection retry preserves canonical ID and cannot create another group. */
    @Test fun canonicalProjectionRetrySkipsLookupAndCreate() =
        runTest {
            val ids = mutableListOf<String>()
            val result =
                ownedAttempt(
                    NewMessageSession { true },
                    retryId = "canonical",
                    lookup = { error("retry must use retained canonical ID") },
                    create = { error("retry must not create again") },
                    read = {
                        ids += it
                        item()
                    },
                )
            assertTrue(result is StartChatAttemptResult.Open)
            assertEquals(listOf("canonical"), ids)
        }

    /** A captured callback rejected before launch cannot invoke even the lookup owner. */
    @Test fun disposedBeforeAttemptDoesNoNativeWork() =
        runTest {
            val owner = NewMessageSession { true }
            owner.dispose()
            val result = runCatching { ownedAttempt(owner, lookup = { error("must not lookup") }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }

    /** Compose uses these same wrappers around existing lookup, native create and authoritative read. */
    private suspend fun ownedAttempt(
        owner: NewMessageSession,
        retryId: String? = null,
        lookup: suspend () -> NewMessageDirectChatResolution = { NewMessageDirectChatResolution(null, true) },
        create: suspend (String) -> String = { "canonical" },
        read: suspend (String) -> ChatListItem = { item() },
    ): StartChatAttemptResult =
        attemptOpenOrStartProfileChat(
            npub = "npub1target",
            progressHex = "b".repeat(64),
            recipientName = "Recipient",
            retryGroupIdHex = retryId,
            resolveDirectChat = { owner.currentValue(lookup) },
            createGroup = { target -> owner.currentValue { create(target) } },
            loadCreatedChatListItem = { id -> owner.currentValue { read(id) } },
            displayName = { it },
        )

    /** Reuses the existing native-projection fixture used by recipient-resolution regression tests. */
    private fun item() = dmChatItem("canonical", "a".repeat(64), null)
}
