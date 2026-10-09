package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.text.input.TextFieldState
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production FFI argument mapping and the fence used around native image/projection awaits. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewGroupSubmissionTest {
    /** Description, image, and retention cross one atomic encrypted group-founding boundary. */
    @Test fun nativeCreateReceivesDescriptionMembersImageAndRetentionOptions() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3)
            val draft = ImageUploadDraft(bytes, "image/png", "https://example.org/source", "12x12", "hash")
            val request =
                NewGroupSubmission(
                    "Team 😀",
                    "Private planning",
                    listOf("b".repeat(64)),
                    draft,
                    disappearingMessageSecs = 604_800L,
                )
            var creates = 0
            val result =
                request.createWith { name, members, options ->
                    creates++
                    assertEquals("Team 😀", name)
                    assertEquals(listOf("b".repeat(64)), members)
                    assertEquals("Private planning", options.description)
                    val image = options.initialImage
                    assertArrayEquals(bytes, image!!.plaintext)
                    assertNull(image.sourceUrl)
                    assertEquals("image/png", image.mediaType)
                    assertEquals(604_800uL, options.disappearingMessageSecs)
                    "canonical"
                }
            assertEquals("canonical", result)
            assertEquals(1, creates)
        }

    /** Two typed failures remove only the chosen people and recapture all authored options for each native attempt. */
    @Test fun failedTenPersonSubmissionCanContinueWithNineThenEight() =
        runTest {
            val image = ImageUploadDraft(byteArrayOf(1, 2), "image/png", null, null, null)
            val draft = NewGroupDraft(TextFieldState(" Team "), TextFieldState(" Plans "), retentionSecs = 300L)
            draft.imageDraft = image
            val selected = (1..10).map { RecipientSearch.Candidate("$it", "Same name", "npub$it") }.toMutableList()
            val owner = GroupCreationSession { true }
            val seenSizes = mutableListOf<Int>()
            for (attempt in 1..3) {
                val submitted = selected.toList()
                val request = captureNewGroupSubmission(draft, selected, draft.imageDraft, draft.retentionSecs)
                val result = runCatching {
                    request.createWith { name, members, options ->
                        seenSizes += members.size
                        assertEquals("Team", name)
                        assertEquals("Plans", options.description)
                        assertEquals(300uL, options.disappearingMessageSecs)
                        assertArrayEquals(byteArrayOf(1, 2), options.initialImage!!.plaintext)
                        when (attempt) {
                            1 -> throw MarmotKitException.MissingKeyPackage("3")
                            2 -> throw MarmotKitException.MissingMemberInboxRoute("7")
                            else -> "canonical"
                        }
                    }
                }
                result.exceptionOrNull()?.let { error ->
                    val failure = groupCreationRecovery(error, attempt, submitted)
                    assertEquals(submitted, selected)
                    val removed = failure.removableRecipient(attempt, selected, owner, null)!!
                    selected.removeAll { it.accountIdHex == removed.accountIdHex }
                    assertNull(failure.removableRecipient(attempt, selected, owner, null))
                }
                if (attempt == 3) assertEquals("canonical", result.getOrThrow())
            }
            assertEquals(listOf(10, 9, 8), seenSizes)
            assertEquals(listOf("1", "2", "4", "5", "6", "8", "9", "10"), selected.map { it.accountIdHex })
        }

    /** Empty membership stays a real solo group and a missing image remains absent at the native boundary. */
    @Test fun soloGroupDoesNotInventMemberOrPhoto() =
        runTest {
            NewGroupSubmission("Notes", null, emptyList(), null).createWith { _, members, options ->
                assertTrue(members.isEmpty())
                assertNull(options.description)
                assertNull(options.initialImage)
                assertEquals(0uL, options.disappearingMessageSecs)
                "solo"
            }
        }

    /** Switching accounts while the real media loader is suspended rejects its late bytes before draft mutation. */
    @Test fun latePreparedImageCannotMutateReplacementOwner() = lateValue(dispose = false)

    /** Back also fences the old media/projection callback before Compose disposes the screen. */
    @Test fun disposedScreenCannotAcceptLateValue() = lateValue(dispose = true)

    /** Coroutine cancellation remains cancellation even when a provider returns a value without checking it. */
    @Test fun cancelledCallerCannotAcceptSynchronousValue() =
        runTest {
            val owner = GroupCreationSession { true }
            val release = CompletableDeferred<Unit>()
            val attempt =
                async {
                    owner.currentValue {
                        release.await()
                        "ready"
                    }
                }
            try {
                runCurrent()
                attempt.cancel()
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Uses the production suspension fence instead of timing delays or a fake-ready presentation model. */
    private fun lateValue(dispose: Boolean) =
        runTest {
            var current = true
            val owner = GroupCreationSession { current }
            val release = CompletableDeferred<Unit>()
            var assigned = false
            val attempt =
                async {
                    owner.currentValue {
                        release.await()
                        byteArrayOf(1)
                    }
                    assigned = true
                }
            try {
                runCurrent()
                if (dispose) owner.dispose() else current = false
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(false, assigned)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }
}
