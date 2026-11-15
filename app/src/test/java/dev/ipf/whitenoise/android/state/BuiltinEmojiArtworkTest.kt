package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.notificationBuiltinEmojiBitmap
import dev.ipf.whitenoise.android.ui.EmojiData
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Canonical iOS PNG identity survives the normal bundled-artwork send reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuiltinEmojiArtworkTest {
    private val expected =
        linkedMapOf(
            ":marmot:" to "e787c7deee497eb439c4515607cc8e04043a3f5ac6dc32a6d667a3d3dcfd0c82",
            ":wn:" to "af8ffecb318167d4f5ebfc020ce9aba86cb7222f60a64067750e86ec61d539a2",
            ":marmot_classic:" to "2eb14069ef98a307e8cde88e85f73c34aeaa191e8a033a125f41909c9368991e",
            ":marmot_wave:" to "733a03c928f7fa1040a8d25306c00cdc41532eea3b1446adaa514cd9fd603284",
            ":marmot_yes:" to "e91d12edd023ed6af2ec0df44a8526d8d2c1f28ab2d34d7af43728a013200bdf",
            ":marmot_laugh:" to "ea7b4f760778396a63b0935653fe6ee37a5a31243b959553a90879adaac9dc61",
            ":marmot_love:" to "096ee61fd705e6b6616cf5ef2f2b94f45252e91e8f94aca597b19e4d6a4daeff",
            ":marmot_party:" to "9eb8e1bfc64dd96a1a873df837e77f77e4c8ea33a0c0c14e1f2e5dfec1c2c0bd",
            ":marmot_fire:" to "00047e800a0a6386b628d0043e9b66cf88cbe0cf597f70e409f02e8caa6f53a1",
            ":marmot_wow:" to "906c4ad53f92691fea2c06efb509adad9b48caac74a217d91761bcb6ef961bdb",
            ":marmot_smirk:" to "9573a1d7a97f13c68f47294db74ef6f29a65f401d485c6f3359d40f61ab1778e",
            ":marmot_cry:" to "51e57e230b8510a9b548b852ed1871dab1d88995a496d9924e08010b87c5a27d",
            ":marmot_angry:" to "93b03b1590585c11b0d5036150483d92dece1012c1382a17bdeabaae0c6762a1",
        )

    /** All thirteen upload payloads retain the upstream bytes and filename. */
    @Test
    fun bundledSendBytesMatchIos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(expected.keys.toList(), EmojiShortcodes.builtins)
        val artwork = readBuiltinEmojiArtwork(context, EmojiShortcodes.builtins + ":unknown:")
        assertEquals(expected.size, artwork.size)
        artwork.forEach { entry ->
            assertEquals(expected.getValue(entry.shortcode), entry.sha256)
            assertEquals("image/png", entry.mediaType)
            assertEquals("${entry.shortcode.trim(':')}.png", entry.fileName)
        }
    }

    /** A fresh account's normal composer lookup includes builtin bytes without querying an installed set. */
    @Test
    fun composerResolvesAllBuiltins() =
        runBlocking {
            MediaDownloadIntegrationFixture().use { fixture ->
                val artwork =
                    fixture.state.emojiArtworkIn(
                        MediaDownloadIntegrationFixture.ACCOUNT,
                        EmojiShortcodes.builtins.joinToString(" "),
                    )
                assertEquals(expected, artwork.associate { it.shortcode to it.sha256 })
            }
        }

    /** The picker, text-only labels and notification rasterizer recognize every builtin. */
    @Test
    fun everyBuiltinCanBeFoundSpokenAndDrawn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = EmojiData.load(context)
        expected.keys.forEach { code ->
            assertNotNull(EmojiShortcodes.builtinDrawable(code))
            val name = context.getString(requireNotNull(EmojiShortcodes.builtinName(code)))
            assertTrue(name.isNotBlank())
            assertTrue(EmojiData.search(entries, code, 100).any { it.emoji == code })
            assertTrue(EmojiData.search(entries, name, 100).any { it.emoji == code })
            val bitmap = requireNotNull(notificationBuiltinEmojiBitmap(context, code))
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }

    /** Spoken names and picker search follow every shipped locale after an in-app language change. */
    @Test
    fun localizedNamesRemainDistinctAndSearchable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("en", "de", "es", "fr", "it", "pt", "ru", "tr", "zh-Hans", "zh-Hant").forEach { language ->
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(language))
            val localized = context.createConfigurationContext(configuration)
            val names = expected.keys.map { localized.getString(requireNotNull(EmojiShortcodes.builtinName(it))) }
            assertEquals(expected.size, names.toSet().size)
            assertTrue(names.all { it.isNotBlank() })
            assertEquals("Marmot", names[0])
            assertEquals("White Noise", names[1])
            val entries = EmojiData.load(localized)
            expected.keys.zip(names).forEach { (code, name) ->
                assertTrue(EmojiData.search(entries, name, 100).any { it.emoji == code })
            }
        }
    }
}
