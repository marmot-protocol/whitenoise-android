package dev.ipf.whitenoise.android.ui.chats

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render contract for the chat list's theme-independent edge mask. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-mdpi")
class ChatListEdgeFadeScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var view: View

    /** A list parked in its middle dissolves at both visible edges while keeping its body opaque. */
    @Test
    fun scrollableListDissolvesAtBothEdges() {
        render(rowCount = 40)
        rule.runOnIdle { scope.launch { list.scrollToItem(10) } }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(FRAME)
        val pixels = node.captureToImage().toPixelMap()
        node.captureRoboImage(SNAPSHOT)

        assertTrue(pixels[COLUMN, 14].isPartlyFaded())
        assertEquals(ROW, pixels[COLUMN, 240])
        assertTrue(pixels[COLUMN, 466].isPartlyFaded())
    }

    /** A short list has no scroll boundary and therefore keeps every row fully opaque. */
    @Test
    fun shortListKeepsBothEdgesOpaque() {
        render(rowCount = 4)
        val pixels = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

        assertEquals(ROW, pixels[COLUMN, 1])
        assertEquals(ROW, pixels[COLUMN, 159])
    }

    /** The keyboard suspends the mask in place: the clipped production list keeps its start edge. */
    @Test
    fun keyboardSuspendsMaskWithoutClippingStartEdge() {
        render(rowCount = 40, clipped = true)
        rule.runOnIdle { scope.launch { list.scrollToItem(10) } }
        rule.waitForIdle()
        dispatchIme(bottom = 300)
        val keyboard = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

        assertEquals("start edge under the keyboard", ROW, keyboard[START_COLUMN, 240])
        assertEquals("keyboard viewport stays unmasked", ROW, keyboard[COLUMN, 1])
        dispatchIme(bottom = 0)
        val restored = rule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        assertEquals("start edge after the keyboard", ROW, restored[START_COLUMN, 240])
        assertTrue(restored[COLUMN, 14].isPartlyFaded())
    }

    /** Mounts a normal, non-reversed list over a known background, optionally clipped like the chat list. */
    private fun render(
        rowCount: Int,
        clipped: Boolean = false,
    ) {
        rule.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            view = LocalView.current
            Box(Modifier.size(320.dp, 480.dp).background(BACKGROUND).testTag(FRAME)) {
                LazyColumn(
                    state = list,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (clipped) Modifier.clipToBounds() else Modifier)
                            .chatListEdgeFade(list),
                ) {
                    items((0 until rowCount).toList(), key = { it }) {
                        Box(Modifier.fillMaxWidth().height(40.dp).background(ROW))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Dispatches window insets so Compose sees the IME settle as visible or hidden. */
    private fun dispatchIme(bottom: Int) {
        rule.runOnIdle {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        rule.waitForIdle()
    }

    /** Reports a pixel blended between the cyan row and white page. */
    private fun Color.isPartlyFaded(): Boolean = red > 0.3f && red < 0.7f

    private companion object {
        const val FRAME = "chat-list-edge-fade-frame"
        const val SNAPSHOT = "src/test/snapshots/chat_list_edge_fade.png"
        const val COLUMN = 160

        // Inside the 30 dp lateral outset that a removed mask layer used to leave clipped.
        const val START_COLUMN = 4
        val ROW = Color.Cyan
        val BACKGROUND = Color.White
    }
}
