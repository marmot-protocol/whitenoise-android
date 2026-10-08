package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A staged GIF that content admission refuses ends on a stable filename card, never a spinner that cannot
 * finish, and stays removable from the send through its include checkbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerRefusedPreviewTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Writes [bytes] into the cache under [name] and returns its file Uri. */
    private fun stage(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(context.cacheDir, name).also { it.writeBytes(bytes) })

    /** Renders the preview pager for one staged image at [uri], honestly labelled a GIF. */
    private fun renderPager(
        uri: Uri,
        onIncludedChange: (String, Boolean) -> Unit = { _, _ -> },
    ) {
        val item = StagedPreviewItem.Media(PendingMediaSlot("refused-slot", uri))
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    MediaPreviewPager(
                        items = listOf(item),
                        pagerState = rememberPagerState { 1 },
                        metadata =
                            mapOf(uri to LocalPreviewMetadata(isVideo = false, displayName = null, isGif = true)),
                        prepared = emptyMap(),
                        excluded = emptySet(),
                        onIncludedChange = onIncludedChange,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    /** Waits for the off-thread decode to settle on the filename card for [label]. */
    private fun awaitFilenameCard(label: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Matches a progress indicator, which a refused source must never leave on screen. */
    private val progressIndicator = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    /** Every progress indicator currently in the tree. */
    private fun progressNodes() = composeRule.onAllNodes(progressIndicator)

    /** A GIF declaring a canvas past the admission limit shows its filename and no progress indicator. */
    @Test
    fun refusedGifShowsTheFilenameCardInsteadOfASpinner() {
        renderPager(stage("oversized-canvas.gif", gif(width = 5000, height = 5000)))
        awaitFilenameCard("oversized-canvas.gif")

        composeRule.onNodeWithText("oversized-canvas.gif").assertExists()
        progressNodes().assertCountEquals(0)
    }

    /** A truncated GIF is refused the same way and its include checkbox still works. */
    @Test
    fun refusedGifStaysRemovableFromTheSend() {
        val whole = gif()
        val changes = mutableListOf<Pair<String, Boolean>>()
        renderPager(
            stage("truncated.gif", whole.copyOf(whole.size - 6)),
            onIncludedChange = { key, included -> changes += key to included },
        )
        awaitFilenameCard("truncated.gif")

        composeRule.onNodeWithTag("conversation.media.inclusion.target").performClick()

        assertEquals(listOf("media:refused-slot" to false), changes)
        progressNodes().assertCountEquals(0)
    }

    /** An unreadable source also ends on the filename card, not a permanent spinner. */
    @Test
    fun missingSourceShowsTheFilenameCardInsteadOfASpinner() {
        renderPager(Uri.fromFile(File(context.cacheDir, "gone.gif").also { it.delete() }))
        awaitFilenameCard("gone.gif")

        composeRule.onNodeWithText("gone.gif").assertExists()
        progressNodes().assertCountEquals(0)
    }
}
