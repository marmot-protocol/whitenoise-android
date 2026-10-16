package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaViewerChromeGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A confirmed single tap toggles chrome exactly once. */
    @Test
    fun singleTapTogglesChrome() {
        var singleTaps = 0
        var doubleTaps = 0
        renderGestureSurface(onSingleTap = { singleTaps++ }, onDoubleTap = { doubleTaps++ })

        composeRule.onNodeWithTag(GESTURE_TAG).performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        assertEquals(1, singleTaps)
        assertEquals(0, doubleTaps)
    }

    /** A double tap resets the transform without also toggling chrome. */
    @Test
    fun doubleTapDoesNotToggleChrome() {
        var singleTaps = 0
        var doubleTaps = 0
        renderGestureSurface(onSingleTap = { singleTaps++ }, onDoubleTap = { doubleTaps++ })

        composeRule.onNodeWithTag(GESTURE_TAG).performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertEquals(0, singleTaps)
        assertEquals(1, doubleTaps)
    }

    /** The photo tap keeps toggling while hidden chrome hands its space to the page and visible chrome reclaims it. */
    @Test
    fun singleTapKeepsTogglingWhileThePageResizes() {
        var chromeVisible by mutableStateOf(true)
        composeRule.setContent {
            MediaViewerFrame(
                senderLabel = "Alex",
                recordedAtLabel = "Oct 15, 2026, 9:41 AM",
                onDismiss = {},
                onSave = {},
                onShare = {},
                snackbarHostState = remember { SnackbarHostState() },
                contentWindowInsets = WindowInsets(top = 24.dp, bottom = 48.dp),
                chromeVisible = chromeVisible,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .viewerTapGestureModifier(Unit, { chromeVisible = !chromeVisible }, {})
                        .testTag(GESTURE_TAG),
                )
            }
        }

        listOf(false, true, false).forEach { expected ->
            composeRule.onNodeWithTag(GESTURE_TAG).performTouchInput { click() }
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(expected, chromeVisible) }
            val chrome = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG)
            if (expected) chrome.assertExists() else chrome.assertDoesNotExist()
        }
    }

    /** Renders the production tap arbiter on a deterministic image-sized surface. */
    private fun renderGestureSurface(
        onSingleTap: () -> Unit,
        onDoubleTap: () -> Unit,
    ) {
        composeRule.setContent {
            Box(
                Modifier
                    .size(240.dp)
                    .viewerTapGestureModifier(Unit, onSingleTap, onDoubleTap)
                    .testTag(GESTURE_TAG),
            )
        }
    }

    private companion object {
        const val GESTURE_TAG = "media-viewer-chrome-gesture"
    }
}
