package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.ui.EmojiArt
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A pill's artwork follows its own reaction and the download setting, never showing another reaction's image. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionArtworkStateTest {
    @get:Rule val composeRule = createComposeRule()

    private val red = art()
    private val blueLoad = CompletableDeferred<EmojiArt?>()
    private val networkAllowed = mutableStateOf(true)
    private val reaction = mutableStateOf(RED)
    private var shown: EmojiArt? = null

    /** A new reaction in the same pill shows nothing until its own artwork loads, never the previous reaction's. */
    @Test
    fun aNewReactionInTheSamePillNeverShowsTheOldArtwork() {
        render()
        composeRule.waitForIdle()
        assertSame(red, shown)

        composeRule.runOnIdle { reaction.value = BLUE }
        composeRule.waitForIdle()

        assertNull(shown)
    }

    /** Turning automatic downloads on retries artwork that was not on the device while the pill stayed on screen. */
    @Test
    fun enablingDownloadsRetriesMissingArtwork() {
        networkAllowed.value = false
        render()
        composeRule.waitForIdle()
        assertNull(shown)

        composeRule.runOnIdle { networkAllowed.value = true }
        composeRule.waitForIdle()

        assertSame(red, shown)
    }

    /** Shows one pill's artwork for [reaction] from a source that holds RED only when downloads are allowed. */
    private fun render() {
        val source =
            object : ReactionArtworkSource {
                override fun allowNetwork() = networkAllowed.value

                override suspend fun load(
                    reactionMessageIdHex: String,
                    allowNetwork: Boolean,
                ): EmojiArt? =
                    when {
                        reactionMessageIdHex == BLUE -> blueLoad.await()
                        allowNetwork -> red
                        else -> null
                    }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalReactionArtworkSource provides source) {
                shown = rememberReactionArtwork(":party:", reaction.value)
            }
        }
    }

    /** A small decoded image standing in for reaction artwork. */
    private fun art(): EmojiArt = EmojiArt.Decoded(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap())

    private companion object {
        const val RED = "red-reaction"
        const val BLUE = "blue-reaction"
    }
}
