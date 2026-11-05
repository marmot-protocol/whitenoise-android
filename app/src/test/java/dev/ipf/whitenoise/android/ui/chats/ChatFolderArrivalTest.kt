package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The folder-arrival entrance for #3490: one downward start, then only upward motion to rest.
 *
 * On a device the destination list composes and draws a frame before the arrival effect runs, and the
 * effect's snap then pulled a list already drawn at rest down by 8dp. Robolectric runs the effect before
 * that first draw, so the ordering itself is pinned by [chatFolderArrivalTranslation] and by the
 * emulator frame trace in the PR; the mounted case checks the entrance the user sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w200dp-h200dp-mdpi")
class ChatFolderArrivalTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var oldScale: String? = null

    /** Robolectric reports animations as off by default; the arrival only runs with system motion on. */
    @Before fun enableMotion() {
        oldScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restores the animator scale the test found. */
    @After fun restoreMotion() {
        Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, oldScale)
    }

    /** A folder change starts the destination 8dp low, and the entrance then only moves up to rest. */
    @Test fun theEntranceStartsDisplacedAndOnlyMovesUp() {
        var folder by mutableStateOf<String?>(FOLDER_A)
        mount { folder }
        assertEquals("the settled list rests at the top", 0, contentTop(SOURCE))

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { folder = FOLDER_B }
        // The source keeps drawing until the change is applied; the destination is the blue content.
        val frames =
            List(FRAMES) {
                rule.mainClock.advanceTimeByFrame()
                contentTop(DESTINATION)
            }.dropWhile { it == null }
        assertEquals("the destination's first frame starts the entrance: $frames", ARRIVAL_PX, frames.first())
        val tops = frames.map { requireNotNull(it) }
        assertTrue("the entrance never jumps down again: $tops", tops.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("it never exceeds the entrance distance: $tops", tops.all { it <= ARRIVAL_PX })
        assertEquals("and settles at rest: $tops", 0, tops.last())
    }

    /** Reselecting the current folder or the root starts nothing, so a settled list never moves. */
    @Test fun anUnchangedFolderNeverMoves() {
        var folder by mutableStateOf<String?>(null)
        mount { folder }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { folder = null }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0, contentTop(SOURCE))
    }

    /** Pending entrances begin at the distance and running ones continue from their rendered offset. */
    @Test fun translationContinuesARunningEntrance() {
        assertEquals(8f, chatFolderArrivalTranslation(arrivalPending = true, offset = 0f, distance = 8f))
        assertEquals(3f, chatFolderArrivalTranslation(arrivalPending = true, offset = 3f, distance = 8f))
        assertEquals(3f, chatFolderArrivalTranslation(arrivalPending = false, offset = 3f, distance = 8f))
        assertEquals(0f, chatFolderArrivalTranslation(arrivalPending = false, offset = 0f, distance = 8f))
    }

    /**
     * Draws a block through the production arrival modifier inside a white frame, red for folder B and
     * [SOURCE] for every other folder, so a frame shows which folder it is drawing.
     */
    private fun mount(folder: () -> String?) {
        rule.setContent {
            val current = folder()
            val color = if (current == FOLDER_B) DESTINATION else SOURCE
            Box(Modifier.size(200.dp).background(Color.White).testTag(FRAME_TAG)) {
                Box(Modifier.then(rememberChatFolderArrival(OWNER, current)).size(100.dp).background(color))
            }
        }
        rule.waitForIdle()
    }

    /** The first pixel row drawn in [color], or null when this frame does not draw it. */
    private fun contentTop(color: Color): Int? {
        val pixels = rule.onNodeWithTag(FRAME_TAG).captureToImage().toPixelMap()
        return (0 until pixels.height).firstOrNull { y -> pixels[COLUMN_X, y] == color }
    }

    private companion object {
        const val FRAME_TAG = "arrival-frame"
        val SOURCE = Color.Blue
        val DESTINATION = Color.Red
        const val OWNER = "account"
        const val FOLDER_A = "folder-a"
        const val FOLDER_B = "folder-b"
        const val ARRIVAL_PX = 8
        const val FRAMES = 14
        const val COLUMN_X = 50
    }
}
