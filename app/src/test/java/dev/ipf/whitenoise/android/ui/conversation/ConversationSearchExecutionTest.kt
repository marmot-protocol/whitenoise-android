package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearchExecutionTest {
    @Test
    fun replacementQueryCancelsTheOldScanBeforeItCanPublish() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val holdOld = CompletableDeferred<Unit>()
            var oldCancelled = false
            val published = mutableListOf<List<String>>()

            val oldRequest =
                launch {
                    runConversationSearchRequest(
                        rawQuery = "old",
                        debounceMillis = 0,
                        search = {
                            oldStarted.complete(Unit)
                            try {
                                holdOld.await()
                                listOf("stale")
                            } finally {
                                oldCancelled = true
                            }
                        },
                        publish = published::add,
                    )
                }
            runCurrent()
            oldStarted.await()

            oldRequest.cancelAndJoin()
            runConversationSearchRequest(
                rawQuery = "new",
                debounceMillis = 0,
                search = { listOf("fresh") },
                publish = published::add,
            )

            assertTrue("superseded scan must be cancelled", oldCancelled)
            assertEquals(listOf(listOf("fresh")), published)
        }

    @Test
    fun completedNativeCallDoesNotPublishAfterStateMovesToAnotherQuery() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var currentQuery = "first"
            val published = mutableListOf<List<String>>()

            val request =
                launch {
                    runConversationSearchRequest(
                        rawQuery = "first",
                        debounceMillis = 0,
                        search = {
                            release.await()
                            listOf("stale")
                        },
                        isCurrent = { currentQuery == "first" },
                        publish = published::add,
                    )
                }

            runCurrent()
            currentQuery = "second"
            release.complete(Unit)
            request.join()

            assertTrue(published.isEmpty())
        }

    @Test
    fun olderResultIsLoadedBeforeTheUiCentersIt() =
        runTest {
            val events = mutableListOf<String>()

            val centered =
                loadAndCenterConversationSearchMatch(
                    target = "old-match",
                    load = {
                        events += "load:$it"
                        true
                    },
                    center = { events += "center:$it" },
                )

            assertTrue(centered)
            assertEquals(listOf("load:old-match", "center:old-match"), events)
        }

    @Test
    fun unavailableResultIsNeverCentered() =
        runTest {
            var centered = false

            val loaded =
                loadAndCenterConversationSearchMatch(
                    target = "missing",
                    load = { false },
                    center = { centered = true },
                )

            assertFalse(loaded)
            assertFalse(centered)
        }
}
