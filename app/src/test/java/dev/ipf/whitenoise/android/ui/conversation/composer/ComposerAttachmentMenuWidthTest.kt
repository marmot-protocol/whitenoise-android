package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's attachment menu sizes to its widest label rather than to the window.
 *
 * A popup hands its content the whole window width and the Expressive menu rows fill whatever they are
 * given, so without an intrinsic-width bound the menu runs edge to edge — which is what #2596 reported.
 * The design port bounded it; nothing pinned that, and this does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerAttachmentMenuWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The menu keeps well inside its window instead of spanning the conversation behind it. */
    @Test
    fun theAttachmentMenuSizesToItsContentNotTheWindow() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.width(WINDOW_WIDTH_DP.dp)) {
                        ComposerAttachmentMenu(
                            anchorBounds = IntRect(0, 0, WINDOW_WIDTH_DP, 48),
                            expanded = true,
                            onDismiss = {},
                            onCamera = {},
                            onGallery = {},
                            onFiles = {},
                            onLocation = {},
                            onUser = {},
                            onContact = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bounds = composeRule.onNodeWithTag("conversation.attachment.menu").getUnclippedBoundsInRoot()
        val width = bounds.right.value - bounds.left.value
        assertTrue(
            "the menu spanned $width dp of a $WINDOW_WIDTH_DP dp window",
            width <= CONTENT_WIDTH_CEILING * WINDOW_WIDTH_DP,
        )
    }

    private companion object {
        const val WINDOW_WIDTH_DP = 360
        const val CONTENT_WIDTH_CEILING = 0.7f
    }
}
