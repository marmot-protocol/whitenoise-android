package dev.ipf.whitenoise.android.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEmojiRecentsOwnerTest {
    @Test
    fun onEmojiUsedMovesPickedEmojiToFrontSynchronously() =
        runTest {
            val owner =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = { emptyList() },
                    saveToDisk = {},
                )

            owner.onEmojiUsed("🎉")
            owner.onEmojiUsed("👍")

            assertEquals(listOf("👍", "🎉"), owner.recents)
        }

    @Test
    fun rapidPicksPreserveNewestFirstOrderWithoutLoss() =
        runTest {
            val saved = mutableListOf<List<String>>()
            val owner =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = { emptyList() },
                    saveToDisk = { saved.add(it) },
                )

            owner.onEmojiUsed("👍")
            owner.onEmojiUsed("😂")
            owner.onEmojiUsed("🎉")
            advanceUntilIdle()

            assertEquals(listOf("🎉", "😂", "👍"), owner.recents)
            assertEquals(listOf("🎉", "😂", "👍"), saved.last())
        }

    @Test
    fun hydrateFromDiskDoesNotOverwriteInMemoryPicksWhenStateAlreadyFilled() =
        runTest {
            val owner =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = { listOf("👍", "😂") },
                    saveToDisk = {},
                )

            owner.onEmojiUsed("🔥")
            owner.hydrateFromDiskIfEmpty()

            assertEquals(listOf("🔥"), owner.recents)
        }

    @Test
    fun hydrateFromDiskDoesNotOverwritePickDuringSuspendedLoad() =
        runTest {
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val owner =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = {
                        loadStarted.complete(Unit)
                        releaseLoad.await()
                        listOf("👍", "😂")
                    },
                    saveToDisk = {},
                )

            val hydration = async { owner.hydrateFromDiskIfEmpty() }
            loadStarted.await()
            owner.onEmojiUsed("🔥")
            releaseLoad.complete(Unit)
            hydration.await()

            assertEquals(listOf("🔥"), owner.recents)
        }

    @Test
    fun newOwnerHydratesPersistedRecentsFromDisk() =
        runTest {
            val store = mutableListOf<String>()
            val owner =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = { store.toList() },
                    saveToDisk = {
                        store.clear()
                        store.addAll(it)
                    },
                )

            owner.onEmojiUsed("🔥")
            advanceUntilIdle()

            val reloaded =
                RecentEmojiRecentsOwner(
                    scope = this,
                    loadFromDisk = { store.toList() },
                    saveToDisk = {},
                )
            reloaded.hydrateFromDiskIfEmpty()

            assertEquals(listOf("🔥"), reloaded.recents)
        }
}
