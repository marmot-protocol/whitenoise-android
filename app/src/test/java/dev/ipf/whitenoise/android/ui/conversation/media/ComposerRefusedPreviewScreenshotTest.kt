package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The staged preview of a GIF that content admission refuses: a filename card with a working include checkbox. */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ComposerRefusedPreviewScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Renders the preview pager for a GIF declaring an over-limit canvas, then captures it once the card shows. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val directory = File(context.cacheDir, name).also { it.mkdirs() }
        val file = File(directory, "animation.gif").also { it.writeBytes(gif(width = 5000, height = 5000)) }
        val uri = Uri.fromFile(file)
        val item = StagedPreviewItem.Media(PendingMediaSlot("refused-slot", uri))
        val gifMetadata = LocalPreviewMetadata(isVideo = false, displayName = null, isGif = true)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Box(Modifier.width(360.dp).height(640.dp).testTag(ROOT_TAG)) {
                            MediaPreviewPager(
                                items = listOf(item),
                                pagerState = rememberPagerState { 1 },
                                metadata =
                                    mapOf(uri to gifMetadata),
                                prepared = emptyMap(),
                                excluded = emptySet(),
                                onIncludedChange = { _, _ -> },
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Captures the card at 200% text in a right-to-left layout. */
    private fun captureLargeTextRtl(name: String) = capture(name, fontScale = 2f, direction = LayoutDirection.Rtl)

    /** Light theme: the refused source shows its filename card where a spinner used to stay forever. */
    @Test
    fun refusedGifPreviewLight() = capture("composer_refused_preview_light")

    /** Dark theme keeps the card and the include checkbox legible. */
    @Test
    fun refusedGifPreviewDark() = capture("composer_refused_preview_dark", dark = true)

    /** Large text in RTL keeps the card centered and the checkbox at the mirrored corner. */
    @Test
    fun refusedGifPreviewLargeTextRtl() = captureLargeTextRtl("composer_refused_preview_large_rtl")

    private companion object {
        const val ROOT_TAG = "composer-refused-preview"
    }
}
