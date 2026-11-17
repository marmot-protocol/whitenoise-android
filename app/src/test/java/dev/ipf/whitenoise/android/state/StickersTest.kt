package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.media.PresentationMemoryTrim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/** Sticker image caching and the pack library's loading, discovery and sync rules. */
class StickersTest {
    @Test
    fun concurrentRequestsShareOneDownloadAndTheResultIsCached() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val cache =
                StickerAssetCache(backgroundScope, load = { key ->
                    loads++
                    gate.await()
                    LoadedSticker("image", sticker(key.shortcode))
                }, maxBytes = 1_000, sizeOf = { 1 })

            val first = async { cache.image(KEY) }
            val second = async { cache.image(KEY) }
            runCurrent()
            gate.complete(Unit)

            assertSame(first.await(), second.await())
            assertEquals("image", cache.cached(KEY)?.image)
            assertSame(first.await(), cache.image(KEY))
            assertEquals(1, loads)
        }

    @Test
    fun aFailedStickerStaysFailedUntilAnExplicitRetry() =
        runTest {
            var loads = 0
            var fail = true
            val cache =
                StickerAssetCache(backgroundScope, load = { key ->
                    loads++
                    if (fail) error("offline") else LoadedSticker("image", sticker(key.shortcode))
                }, maxBytes = 1_000, sizeOf = { 1 })

            assertNull(cache.image(KEY))
            assertTrue(cache.hasFailed(KEY))
            assertNull(cache.image(KEY))
            assertEquals(1, loads)

            fail = false
            assertEquals("image", cache.retry(KEY)?.image)
            assertFalse(cache.hasFailed(KEY))
            assertEquals(2, loads)
        }

    @Test
    fun aDownloadWhoseCallerLeftStillLandsInTheCache() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val cache =
                StickerAssetCache(backgroundScope, load = { key ->
                    gate.await()
                    LoadedSticker("image", sticker(key.shortcode))
                }, maxBytes = 1_000, sizeOf = { 1 })

            val caller = async { cache.image(KEY) }
            runCurrent()
            caller.cancel()
            gate.complete(Unit)
            runCurrent()

            assertEquals("image", cache.cached(KEY)?.image)
        }

    @Test
    fun aDownloadCancelledWithItsAccountLeavesNoStaleEntry() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val accountScope = CoroutineScope(coroutineContext + Job())
            var loads = 0
            val cache =
                StickerAssetCache(accountScope, load = { key ->
                    loads++
                    gate.await()
                    LoadedSticker("image", sticker(key.shortcode))
                }, maxBytes = 1_000, sizeOf = { 1 })

            val caller = async { runCatching { cache.image(KEY) } }
            runCurrent()
            accountScope.cancel()
            runCurrent()
            assertTrue(caller.await().exceptionOrNull() is CancellationException)
            assertEquals(0, cache.downloadsInFlight)
            assertEquals(1, loads)
        }

    @Test
    fun memoryTrimDropsDecodedStickers() =
        runTest {
            val cache =
                StickerAssetCache(backgroundScope, load = { key ->
                    LoadedSticker("image", sticker(key.shortcode))
                }, maxBytes = 1_000, sizeOf = { 1 })

            cache.image(KEY)
            cache.trimMemory(PresentationMemoryTrim.EMPTY)

            assertNull(cache.cached(KEY))
        }

    @Test
    fun aFailedInstalledReadStillSettlesTheLibrary() =
        runTest {
            val library =
                StickerLibrary(
                    packs = { _, _ -> error("offline") },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { error("unused") },
                )

            assertFalse(library.hasTriedInstalled)
            library.loadInstalledIfNeeded()
            assertTrue(library.hasTriedInstalled)
            assertFalse(library.hasLoadedInstalled)
        }

    /** A failed installed read stays reported until a later read succeeds. */
    @Test
    fun aFailedInstalledReadIsReportedUntilARetrySucceeds() =
        runTest {
            var fail = true
            val library =
                StickerLibrary(
                    packs = { _, _ -> if (fail) error("offline") else listOf(pack("a", installed = true)) },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { error("unused") },
                )

            library.loadInstalledIfNeeded()
            assertTrue(library.installedReadFailed)

            fail = false
            library.reloadInstalled()
            assertFalse(library.installedReadFailed)
            assertTrue(library.hasLoadedInstalled)
            assertTrue(library.isInstalled(coordinate("a")))
        }

    /** Uninstalling drops the pack even when the reread after it fails. */
    @Test
    fun anUninstalledPackIsDroppedEvenWhenTheRereadFails() =
        runTest {
            var fail = false
            val library =
                StickerLibrary(
                    packs = { _, _ -> if (fail) error("offline") else listOf(pack("a", installed = true)) },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { fail = true },
                )

            library.loadInstalledIfNeeded()
            assertTrue(library.isInstalled(coordinate("a")))

            library.uninstall(pack("a"))
            assertFalse(library.isInstalled(coordinate("a")))
            assertTrue(library.installedReadFailed)
        }

    /** A read that started before an uninstall cannot put the removed pack back. */
    @Test
    fun aReadThatStartedBeforeAnUninstallCannotRestoreThePack() =
        runTest {
            val staleRead = CompletableDeferred<Unit>()
            var reads = 0
            val library =
                StickerLibrary(
                    packs = { _, _ ->
                        reads++
                        if (reads == 2) {
                            staleRead.await()
                            listOf(pack("a", installed = true))
                        } else if (reads == 1) {
                            listOf(pack("a", installed = true))
                        } else {
                            emptyList()
                        }
                    },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = {},
                )

            library.loadInstalledIfNeeded()
            val stale = async { library.reloadInstalled() }
            runCurrent()
            library.uninstall(pack("a"))
            assertFalse(library.isInstalled(coordinate("a")))

            staleRead.complete(Unit)
            stale.await()
            assertFalse(library.isInstalled(coordinate("a")))
        }

    /** A read that started before an uninstall and then failed is not reported as a failure. */
    @Test
    fun aFailureFromAReadThatStartedBeforeAnUninstallIsNotReported() =
        runTest {
            val staleRead = CompletableDeferred<Unit>()
            var reads = 0
            val library =
                StickerLibrary(
                    packs = { _, _ ->
                        reads++
                        when (reads) {
                            1 -> listOf(pack("a", installed = true))
                            2 -> {
                                staleRead.await()
                                error("relay timeout")
                            }
                            else -> emptyList()
                        }
                    },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = {},
                )

            library.loadInstalledIfNeeded()
            val stale = async { library.reloadInstalled() }
            runCurrent()
            library.uninstall(pack("a"))
            assertFalse(library.installedReadFailed)

            staleRead.complete(Unit)
            stale.await()
            assertFalse(library.installedReadFailed)
            assertFalse(library.isInstalled(coordinate("a")))
        }

    /** A failed uninstall leaves the pack installed. */
    @Test
    fun aFailedUninstallKeepsThePackInstalled() =
        runTest {
            val library =
                StickerLibrary(
                    packs = { _, _ -> listOf(pack("a", installed = true)) },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { error("storage") },
                )

            library.loadInstalledIfNeeded()
            assertTrue(runCatching { library.uninstall(pack("a")) }.isFailure)
            assertTrue(library.isInstalled(coordinate("a")))
        }

    @Test
    fun discoveryHidesInstalledAndEmptyPacksAndInstallRefreshesTheList() =
        runTest {
            val installed = mutableListOf(pack("a", installed = true))
            val cached = listOf(pack("a"), pack("b"), pack("c", stickers = emptyList()))
            val library =
                StickerLibrary(
                    packs = { installedOnly, _ -> if (installedOnly) installed.toList() else cached },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { input ->
                        pack(input.substringAfterLast(':'), installed = true).also { installed += it }
                    },
                    uninstallPack = { input -> installed.removeAll { it.coordinate == input } },
                )

            library.loadInstalledIfNeeded()
            assertTrue(library.isInstalled(coordinate("a")))
            assertEquals(listOf(coordinate("b")), library.discover("").map { it.coordinate })

            library.install(pack("b"))
            assertTrue(library.isInstalled(coordinate("b")))
            assertEquals(emptyList<String>(), library.discover("").map { it.coordinate })

            library.uninstall(pack("a"))
            assertFalse(library.isInstalled(coordinate("a")))
        }

    @Test
    fun relaySyncRunsOncePerLibraryUnlessForced() =
        runTest {
            var syncs = 0
            val library =
                StickerLibrary(
                    packs = { _, _ -> emptyList() },
                    syncPacks = { syncs++ },
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { error("unused") },
                )

            library.sync()
            library.sync()
            assertEquals(1, syncs)
            library.sync(force = true)
            assertEquals(2, syncs)
        }

    /** A pack opened from a relay-hinted link installs through that link; any other pack by its coordinate. */
    @Test
    fun installAsksTheRelayHintsThePackWasOpenedFrom() =
        runTest {
            val link = "https://sonarprivacy.xyz/stickers?a=${coordinate("linked")}&relay=wss%3A%2F%2Frelay.example"
            val inputs = mutableListOf<String>()
            val library =
                StickerLibrary(
                    packs = { _, _ -> emptyList() },
                    syncPacks = {},
                    fetchPack = { pack("linked") },
                    installPack = { input ->
                        inputs += input
                        pack(input.substringAfter("a=").substringBefore('&').substringAfterLast(':'), installed = true)
                    },
                    uninstallPack = { error("unused") },
                )

            library.install(library.lookUp(link))
            library.install(pack("browsed"))
            assertEquals(listOf(link, coordinate("browsed")), inputs)
        }

    /** Sharing asks MDK for a link naming the pack's relays, and reports none when MDK has none. */
    @Test
    fun shareLinkComesFromMdkForThePackCoordinate() =
        runTest {
            fun library(link: String?) =
                StickerLibrary(
                    packs = { _, _ -> emptyList() },
                    syncPacks = {},
                    fetchPack = { error("unused") },
                    installPack = { error("unused") },
                    uninstallPack = { error("unused") },
                    packShareLink = { coordinate -> link?.let { "$it?a=$coordinate" } },
                )

            assertEquals("https://share?a=${coordinate("a")}", library("https://share").shareLink(pack("a")))
            assertEquals(null, library(null).shareLink(pack("a")))
        }

    /** White Noise's packs are fetched through links naming its relays, and only an Add installs one, via that link. */
    @Test
    fun whiteNoisePacksAreFetchedThroughItsRelaysAndInstallOnlyOnAdd() =
        runTest {
            val lookups = mutableListOf<String>()
            val installs = mutableListOf<String>()
            val library =
                StickerLibrary(
                    packs = { _, _ -> emptyList() },
                    syncPacks = {},
                    fetchPack = { input -> packAt(linkedCoordinate(input)).also { lookups += input } },
                    installPack = { input ->
                        installs += input
                        packAt(linkedCoordinate(input), installed = true)
                    },
                    uninstallPack = { error("unused") },
                )

            val offered = library.whiteNoisePacks()
            val links = WhiteNoiseStickerPacks.coordinates.map(WhiteNoiseStickerPacks::link)
            assertEquals(WhiteNoiseStickerPacks.coordinates, offered.map { it.coordinate })
            assertEquals(links, lookups)
            assertEquals(emptyList<String>(), installs)

            library.install(offered.first())
            assertEquals(links.take(1), installs)
        }

    /** An installed or unreachable White Noise pack is left out of the offer. */
    @Test
    fun installedOrUnreachableWhiteNoisePacksAreLeftOut() =
        runTest {
            val (originals, marmot) = WhiteNoiseStickerPacks.coordinates
            val library =
                StickerLibrary(
                    packs = { _, _ -> listOf(packAt(originals, installed = true)) },
                    syncPacks = {},
                    fetchPack = { input ->
                        check(linkedCoordinate(input) != marmot) { "relay unreachable" }
                        packAt(linkedCoordinate(input))
                    },
                    installPack = { error("unused") },
                    uninstallPack = { error("unused") },
                )
            library.loadInstalledIfNeeded()

            assertEquals(emptyList<StickerPackFfi>(), library.whiteNoisePacks())
        }

    /** Each White Noise link names the pack and every White Noise bootstrap relay. */
    @Test
    fun whiteNoiseLinksNameEveryBootstrapRelay() {
        for (coordinate in WhiteNoiseStickerPacks.coordinates) {
            val link = URI(WhiteNoiseStickerPacks.link(coordinate))
            val parameters =
                link.rawQuery.split('&').map { part ->
                    val (name, value) = part.split('=', limit = 2)
                    name to URLDecoder.decode(value, "UTF-8")
                }
            assertEquals("https", link.scheme)
            assertEquals(listOf(coordinate), parameters.filter { it.first == "a" }.map { it.second })
            assertEquals(MarmotClient.bootstrapRelays, parameters.filter { it.first == "relay" }.map { it.second })
        }
    }

    /** The pack coordinate a Sonar link names. */
    private fun linkedCoordinate(link: String): String {
        val encoded = link.substringAfter("a=").substringBefore('&')
        return URLDecoder.decode(encoded, "UTF-8")
    }

    private companion object {
        val KEY = StickerKey(coordinate("pack"), "wave", "ab".repeat(32))

        fun coordinate(id: String) = "30031:${"cd".repeat(32)}:$id"

        fun sticker(shortcode: String) =
            StickerFfi(
                coordinate("pack"),
                shortcode,
                "https://example.invalid/$shortcode.webp",
                "ab".repeat(32),
                "image/webp",
                null,
                null,
                null,
                null,
            )

        /** A pack at an arbitrary [coordinate], such as one of White Noise's defaults. */
        fun packAt(
            coordinate: String,
            installed: Boolean = false,
        ) = pack(coordinate.substringAfterLast(':'), installed).copy(coordinate = coordinate)

        fun pack(
            id: String,
            installed: Boolean = false,
            stickers: List<StickerFfi> = listOf(sticker("wave")),
        ) = StickerPackFfi(
            coordinate(id),
            "cd".repeat(32),
            id,
            "ef".repeat(32),
            1uL,
            id,
            null,
            null,
            stickers,
            null,
            installed,
        )
    }
}
