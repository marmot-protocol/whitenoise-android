package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bare `www.` addresses render link-styled beside an unlinked bare domain and trailing punctuation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownWwwLinkScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme at default text size shows only the `www.` addresses as links. */
    @Test
    fun wwwLinksLight() {
        render(darkTheme = false, fontScale = 1f, layoutDirection = LayoutDirection.Ltr)
        capture("markdown_www_links_light.png")
    }

    /** Dark theme with large RTL text keeps the link styling on the same address spans. */
    @Test
    fun wwwLinksDarkLargeRtl() {
        render(darkTheme = true, fontScale = 1.6f, layoutDirection = LayoutDirection.Rtl)
        capture("markdown_www_links_dark_large_rtl.png")
    }

    /** Renders the typed AST MDK emits for prose holding two `www.` addresses and one bare domain. */
    private fun render(
        darkTheme: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("See "),
                                wwwAutolink("www.example.network/path?q=1#top"),
                                MarkdownInlineFfi.Text(". Notes on "),
                                wwwAutolink("www.example.chat"),
                                MarkdownInlineFfi.Text(", but example.network stays plain."),
                            ),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        MarkdownMessageBody(document)
                    }
                }
            }
        }
    }

    /** Builds the autolink node MDK emits for a scheme-less `www.` address. */
    private fun wwwAutolink(typed: String) =
        MarkdownInlineFfi.Autolink(
            typed,
            MarkdownAutolinkKindFfi.WWW,
            MarkdownLinkDestinationKindFfi.WEB,
        )

    /** Captures the rendered message body into the committed baseline [name]. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name")
    }

    private companion object {
        const val TAG = "markdown-www-links"
    }
}
