package dev.ipf.whitenoise.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CustomEmojiStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun image(
        format: Bitmap.CompressFormat,
        width: Int = 4,
        height: Int = 4,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
    }

    @Test
    fun wideArtworkIsSampledByItsLongerEdge() {
        // A short edge under the bound must not stop sampling of the long edge.
        val decoded = decodeEmojiImage(image(Bitmap.CompressFormat.PNG, width = 4096, height = 100))!!

        assertTrue("width ${decoded.width}", decoded.width < 256)
        assertTrue("height ${decoded.height}", decoded.height in 1..100)
    }

    @Test
    fun codesKeepTheShortcodeAlphabet() {
        // Like the Linux client: `-` becomes `_`, anything else outside the alphabet is dropped.
        assertEquals("party_parrot2", sanitizeEmojiCode("Party-Parrot 2!"))
        assertEquals("a".repeat(64), sanitizeEmojiCode("A".repeat(70)))
        assertEquals("", sanitizeEmojiCode("🎉.:/"))
        assertEquals("party_parrotv2", emojiCodeForFileName("Party-Parrot.v2.PNG"))
        assertEquals("", emojiCodeForFileName(".gif"))
    }

    /** An uppercase extension is sendable like the sender reads it, and a legacy type is listed but not sendable. */
    @Test
    fun scanMatchesExtensionsCaseInsensitively() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            directory.resolve("party.PNG").writeBytes(image(Bitmap.CompressFormat.PNG))
            directory.resolve("legacy.img").writeBytes(image(Bitmap.CompressFormat.PNG))
            val store = CustomEmojiStore(directory)
            store.load()
            assertEquals(listOf(":legacy:", ":party:"), store.emoji.entries.map { it.shortcode })
            assertEquals(listOf(":party:"), store.emoji.sendable.map { it.shortcode })
        }

    /** A valid legacy `.img` file stays loaded, renders, is not sendable, and can be removed. */
    @Test
    fun legacyImageFileStaysVisibleAndRemovable() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            directory.resolve("old.img").writeBytes(image(Bitmap.CompressFormat.PNG))
            val store = CustomEmojiStore(directory).apply { load() }

            val legacy = store.emoji[":old:"]!!
            assertFalse(legacy.sendable)
            assertTrue(legacy.image.width > 0)
            assertTrue(EmojiShortcodes.art(":old:", store.emoji, emptyMap()) is EmojiArt.Decoded)
            assertTrue(store.emoji.sendable.isEmpty())

            store.remove(":old:")
            assertTrue(store.emoji.entries.isEmpty())
            assertEquals(emptyList<String>(), directory.list()!!.toList())
        }

    /** A legacy file never shadows a sendable one of the same code, and removing the code deletes both. */
    @Test
    fun legacyFileDoesNotShadowTheSendableOneAndRemoveDeletesBoth() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            directory.resolve("party.img").writeBytes(image(Bitmap.CompressFormat.PNG))
            directory.resolve("party.png").writeBytes(image(Bitmap.CompressFormat.PNG))
            val store = CustomEmojiStore(directory).apply { load() }
            assertEquals(listOf("party.png"), store.emoji.entries.map { it.file.name })
            assertTrue(store.emoji[":party:"]!!.sendable)

            store.remove(":party:")
            assertTrue(store.emoji.entries.isEmpty())
            assertEquals(emptyList<String>(), directory.list()!!.toList())
        }

    /** Saving over a legacy code replaces the legacy file with a sendable one. */
    @Test
    fun savingOverALegacyCodeReplacesIt() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            directory.resolve("old.img").writeBytes(image(Bitmap.CompressFormat.PNG))
            val store = CustomEmojiStore(directory).apply { load() }
            assertEquals(CustomEmojiSaveResult.Saved, store.save("old", image(Bitmap.CompressFormat.PNG)))
            assertEquals(listOf("old.png"), directory.list()!!.toList())
            assertTrue(store.emoji[":old:"]!!.sendable)
        }

    @Test
    fun saveOverwritesTheSameCodeAndSurvivesReload() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            val store = CustomEmojiStore(directory)
            assertEquals(CustomEmojiSaveResult.Saved, store.save("party", image(Bitmap.CompressFormat.PNG)))
            assertEquals(CustomEmojiSaveResult.Saved, store.save("party", image(Bitmap.CompressFormat.JPEG)))
            assertEquals(CustomEmojiSaveResult.Saved, store.save("cat", image(Bitmap.CompressFormat.WEBP_LOSSLESS)))
            assertEquals(listOf("cat.webp", "party.jpg"), directory.list()!!.sorted())
            val reloaded = CustomEmojiStore(directory).apply { load() }
            assertEquals(listOf(":cat:", ":party:"), reloaded.emoji.entries.map { it.shortcode })

            reloaded.remove(":party:")
            assertEquals(listOf(":cat:"), reloaded.emoji.entries.map { it.shortcode })
            assertEquals(listOf("cat.webp"), directory.list()!!.toList())
        }

    @Test
    fun rejectsNamesOutsideTheAlphabetOversizedFilesAndNonImages() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            val store = CustomEmojiStore(directory)
            val png = image(Bitmap.CompressFormat.PNG)
            assertEquals(CustomEmojiSaveResult.InvalidName, store.save("", png))
            assertEquals(CustomEmojiSaveResult.InvalidName, store.save("Party", png))
            assertEquals(CustomEmojiSaveResult.InvalidName, store.save("../x", png))
            assertEquals(
                CustomEmojiSaveResult.TooLarge,
                store.save("big", png + ByteArray(CustomEmojiStore.MAX_BYTES + 1 - png.size)),
            )
            assertEquals(CustomEmojiSaveResult.NotAnImage, store.save("text", "hello".toByteArray()))
            assertEquals(emptyList<String>(), directory.list()!!.toList())
        }

    @Test
    fun undecodableAndMisnamedFilesAreSkippedOnLoad() =
        runBlocking {
            val directory = folder.newFolder("emoji")
            directory.resolve("broken.png").writeBytes("nope".toByteArray())
            directory.resolve("Upper.png").writeBytes(image(Bitmap.CompressFormat.PNG))
            directory.resolve("ok.png").writeBytes(image(Bitmap.CompressFormat.PNG))
            val store = CustomEmojiStore(directory).apply { load() }
            assertEquals(listOf(":ok:"), store.emoji.entries.map { it.shortcode })
            assertNull(store.emoji[":broken:"])
        }
}
