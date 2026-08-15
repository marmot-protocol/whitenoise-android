package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsControllerMemberPageFallbackTest {
    @Test
    fun failedPageUsesEightReadCeilingAndOneReadPerGroupPerGeneration() =
        runTest {
            val groupIds = (0 until 12).map { "group-$it" }
            val attempts = mutableMapOf<String, Int>()
            val release = CompletableDeferred<Unit>()
            var active = 0
            var maxActive = 0

            val pending =
                async {
                    loadFirstFrameMemberFallback(
                        groupIds = groupIds + groupIds.take(3),
                        cutoffMillis = 500L,
                        maxConcurrent = 8,
                        nowMillis = { currentTime },
                    ) { groupIdHex ->
                        attempts[groupIdHex] = attempts.getOrDefault(groupIdHex, 0) + 1
                        active += 1
                        maxActive = maxOf(maxActive, active)
                        try {
                            release.await()
                            groupIdHex
                        } finally {
                            active -= 1
                        }
                    }
                }

            runCurrent()
            assertEquals(8, active)
            assertEquals(8, maxActive)
            release.complete(Unit)
            advanceUntilIdle()

            val batch = pending.await()
            assertEquals(groupIds.toSet(), batch.firstFrameResults.map { it.groupIdHex }.toSet())
            assertEquals(0, batch.remainingCount)
            assertEquals(groupIds.associateWith { 1 }, attempts)
        }

    @Test
    fun firstFrameCutsOffAt500MsAndKeepsUnresolvedFolderPending() =
        runTest {
            val returned = CompletableDeferred<FirstFrameMemberFallbackBatch<String>>()
            val lifecycle =
                launch {
                    returned.complete(
                        loadFirstFrameMemberFallback(
                            groupIds = listOf("fast-a", "slow", "fast-b"),
                            cutoffMillis = 500L,
                            maxConcurrent = 8,
                            nowMillis = { currentTime },
                        ) { groupIdHex ->
                            delay(if (groupIdHex == "slow") 800L else 100L)
                            groupIdHex
                        },
                    )
                    // Keep the owning lifecycle alive after the helper returns;
                    // otherwise structured concurrency waits for its late
                    // child and can hide an over-budget first-frame result.
                    awaitCancellation()
                }

            runCurrent()
            advanceTimeBy(499L)
            runCurrent()
            assertFalse(returned.isCompleted)
            advanceTimeBy(1L)
            runCurrent()

            val firstFrame = returned.await()
            assertEquals(500L, currentTime)
            assertEquals(setOf("fast-a", "fast-b"), firstFrame.firstFrameResults.map { it.groupIdHex }.toSet())
            assertEquals(1, firstFrame.remainingCount)

            advanceTimeBy(300L)
            runCurrent()
            assertEquals("slow", firstFrame.remainingResults.receive().groupIdHex)
            lifecycle.cancelAndJoin()
        }

    @Test
    fun unfinishedFallbackReadsStopWithTheirLifecycle() =
        runTest {
            val returned = CompletableDeferred<FirstFrameMemberFallbackBatch<String>>()
            var cancelledReads = 0
            val lifecycle =
                launch {
                    returned.complete(
                        loadFirstFrameMemberFallback(
                            groupIds = listOf("slow-a", "slow-b"),
                            cutoffMillis = 500L,
                            maxConcurrent = 8,
                            nowMillis = { currentTime },
                        ) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelledReads += 1
                            }
                        },
                    )
                    awaitCancellation()
                }

            runCurrent()
            advanceTimeBy(500L)
            runCurrent()
            assertEquals(2, returned.await().remainingCount)

            lifecycle.cancelAndJoin()
            assertEquals(2, cancelledReads)
        }

    @Test
    fun cutoffBoundaryNeverLosesACompletedResult() =
        runTest {
            val returned = CompletableDeferred<FirstFrameMemberFallbackBatch<String>>()
            val lifecycle =
                launch {
                    returned.complete(
                        loadFirstFrameMemberFallback(
                            groupIds = listOf("boundary"),
                            cutoffMillis = 500L,
                            maxConcurrent = 8,
                            nowMillis = { currentTime },
                        ) {
                            delay(500L)
                            "boundary"
                        },
                    )
                    awaitCancellation()
                }

            advanceTimeBy(500L)
            runCurrent()
            val batch = returned.await()
            assertEquals(1, batch.firstFrameResults.size + batch.remainingCount)
            if (batch.remainingCount == 1) {
                assertEquals("boundary", batch.remainingResults.receive().groupIdHex)
            }
            lifecycle.cancelAndJoin()
        }

    @Test
    fun lateFallbackPublishesOnlyForCurrentAccountAndGeneration() {
        val expectedAccount = "account-a"

        assertTrue(
            initialMemberFallbackGenerationIsCurrent(
                expectedAccount = expectedAccount,
                expectedBindEpoch = 4L,
                expectedCacheEpoch = 9L,
                currentAccount = expectedAccount,
                currentBindEpoch = 4L,
                currentCacheEpoch = 9L,
                lifecycleActive = true,
            ),
        )
        assertFalse(
            initialMemberFallbackGenerationIsCurrent(
                expectedAccount = expectedAccount,
                expectedBindEpoch = 4L,
                expectedCacheEpoch = 9L,
                currentAccount = "account-b",
                currentBindEpoch = 4L,
                currentCacheEpoch = 9L,
                lifecycleActive = true,
            ),
        )
        assertFalse(
            initialMemberFallbackGenerationIsCurrent(
                expectedAccount = expectedAccount,
                expectedBindEpoch = 4L,
                expectedCacheEpoch = 9L,
                currentAccount = expectedAccount,
                currentBindEpoch = 5L,
                currentCacheEpoch = 9L,
                lifecycleActive = true,
            ),
        )
        assertFalse(
            initialMemberFallbackGenerationIsCurrent(
                expectedAccount = expectedAccount,
                expectedBindEpoch = 4L,
                expectedCacheEpoch = 9L,
                currentAccount = expectedAccount,
                currentBindEpoch = 4L,
                currentCacheEpoch = 10L,
                lifecycleActive = true,
            ),
        )
        assertFalse(
            initialMemberFallbackGenerationIsCurrent(
                expectedAccount = expectedAccount,
                expectedBindEpoch = 4L,
                expectedCacheEpoch = 9L,
                currentAccount = expectedAccount,
                currentBindEpoch = 4L,
                currentCacheEpoch = 9L,
                lifecycleActive = false,
            ),
        )
    }
}
