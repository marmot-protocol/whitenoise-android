package dev.ipf.whitenoise.android.ui.conversation.stickers

import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.state.StickerLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the sticker browser keeps or clears its discovery results when a read fails. */
class DiscoveryResultsTest {
    /** A failed read of the same query keeps the earlier results and reports the failure. */
    @Test
    fun aFailedReadOfTheSameQueryKeepsItsResults() =
        runTest {
            val relay = FakeRelay(cached = listOf(pack("a"), pack("b")))
            val discovery = DiscoveryResults()

            discovery.read(relay.library, "")
            relay.offline = true
            discovery.read(relay.library, "")

            assertTrue(discovery.failed)
            assertEquals(listOf(coordinate("a"), coordinate("b")), discovery.packs.map { it.coordinate })
        }

    /** A failed read of a new query never shows the previous query's results. */
    @Test
    fun aFailedReadOfANewQueryClearsTheResults() =
        runTest {
            val relay = FakeRelay(cached = listOf(pack("a")))
            val discovery = DiscoveryResults()

            discovery.read(relay.library, "")
            relay.offline = true
            discovery.read(relay.library, "cats")

            assertTrue(discovery.failed)
            assertEquals(emptyList<StickerPackFfi>(), discovery.packs)
        }

    /** A pack installed since the last good read leaves the kept results. */
    @Test
    fun aFailedRereadDropsAPackInstalledSince() =
        runTest {
            val relay = FakeRelay(cached = listOf(pack("a"), pack("b")))
            val discovery = DiscoveryResults()

            discovery.read(relay.library, "")
            relay.library.install(pack("a"))
            relay.offline = true
            discovery.read(relay.library, "")

            assertTrue(discovery.failed)
            assertEquals(listOf(coordinate("b")), discovery.packs.map { it.coordinate })
        }

    /** A successful read after a failure clears the failure and shows the new results. */
    @Test
    fun aSuccessfulReadClearsTheFailure() =
        runTest {
            val relay = FakeRelay(cached = listOf(pack("a")))
            val discovery = DiscoveryResults()

            relay.offline = true
            discovery.read(relay.library, "")
            assertTrue(discovery.failed)

            relay.offline = false
            discovery.read(relay.library, "")
            assertFalse(discovery.failed)
            assertEquals(listOf(coordinate("a")), discovery.packs.map { it.coordinate })
        }

    /** A relay cache whose discovery reads fail while [offline]; installed reads keep working. */
    private class FakeRelay(
        private val cached: List<StickerPackFfi>,
    ) {
        var offline = false
        private val installed = mutableListOf<StickerPackFfi>()

        val library =
            StickerLibrary(
                packs = { installedOnly, _ ->
                    when {
                        installedOnly -> installed.toList()
                        offline -> error("relay timeout")
                        else -> cached
                    }
                },
                syncPacks = {},
                fetchPack = { error("unused") },
                installPack = { input ->
                    pack(input.substringAfterLast(':'), installed = true).also { installed += it }
                },
                uninstallPack = { error("unused") },
            )
    }

    private companion object {
        fun coordinate(id: String) = "30031:${"cd".repeat(32)}:$id"

        fun pack(
            id: String,
            installed: Boolean = false,
        ) = StickerPackFfi(
            coordinate(id),
            "cd".repeat(32),
            id,
            "ef".repeat(32),
            1uL,
            id,
            null,
            null,
            listOf(
                StickerFfi(
                    coordinate(id),
                    "wave",
                    "https://example.invalid/wave.webp",
                    "ab".repeat(32),
                    "image/webp",
                    null,
                    null,
                    null,
                    null,
                ),
            ),
            null,
            installed,
        )
    }
}
