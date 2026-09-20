package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies a cached viewer thumbnail is a useful first frame, not a synthetic download spinner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MediaViewerFirstFrameTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cachedThumbnailRendersWithoutLoadingIndicator() {
        val thumbnail = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                MediaViewerPendingFrame(thumbnail, null, "cached.jpg", failed = false, onRetry = {})
            }
        }

        composeRule.onNodeWithContentDescription("cached.jpg").assertExists()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }
}
