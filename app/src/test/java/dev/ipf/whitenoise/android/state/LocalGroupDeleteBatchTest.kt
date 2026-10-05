package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalGroupDeleteBatchTest {
    @Test
    fun everySelectedChatIsAttemptedOnceForLargeSuccessfulBatches() =
        runTest {
            for (size in listOf(0, 1, 49, 50, 51, 100, 101, 201, 500)) {
                val ids = (0 until size).map(Int::toString)
                val calls = mutableListOf<String>()
                val result =
                    deleteLocalChatsBatch(ids, { true }) {
                        calls += it
                        true
                    }
                assertEquals(ids, calls)
                assertEquals(LocalChatDeleteBatchResult(size, size, size), result)
            }
        }

    @Test
    fun firstUnconfirmedDeleteStopsWithoutReplayingItOrDeletingRemainingChats() =
        runTest {
            val calls = mutableListOf<String>()
            val result =
                deleteLocalChatsBatch(listOf("one", "unknown", "three"), { true }) {
                    calls += it
                    it != "unknown"
                }
            assertEquals(listOf("one", "unknown"), calls)
            assertEquals(LocalChatDeleteBatchResult(3, 2, 1), result)
        }

    @Test
    fun accountOrRuntimeReplacementDuringFirstDeletePreventsSecondDelete() =
        runTest {
            var current = true
            val calls = mutableListOf<String>()
            val result =
                deleteLocalChatsBatch(listOf("one", "two"), { current }) {
                    calls += it
                    current = false
                    true
                }
            assertEquals(listOf("one"), calls)
            assertEquals(LocalChatDeleteBatchResult(2, 1, 1), result)
        }

    @Test
    fun staleConfirmationDoesNothingAndDuplicateIdsNeverRepeatDeletion() =
        runTest {
            assertEquals(
                LocalChatDeleteBatchResult(1, 0, 0),
                deleteLocalChatsBatch(listOf("Aa", "aa"), { false }) { error("stale confirmation") },
            )
            var deletes = 0
            val result =
                deleteLocalChatsBatch(listOf("Aa", "aa"), { true }) {
                    deletes++
                    true
                }
            assertEquals(1, deletes)
            assertEquals(LocalChatDeleteBatchResult(1, 1, 1), result)
        }
}
