package dev.ipf.whitenoise.android.ui.chats

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Retains actual ChatsScreen pixels and measured eligibility; the fixture paints real rows green over black. */
internal class ChatFolderFadeFrameProbe(private val rule: ComposeTestRule) {
    /** Inspect inside the 28dp bands but below the separate 8dp destination arrival displacement. */
    fun assertEdges(
        stage: String,
        top: Boolean,
        bottom: Boolean,
    ) {
        val levels = capture(stage, top, bottom)
        assertTrue("$stage readable interior: $levels", levels[1] > 0.95f)
        listOf(top, bottom).zip(listOf(levels.first(), levels.last())).forEach { (eligible, level) ->
            if (eligible) {
                assertTrue("$stage eligible edge must remain visibly attenuated: $levels", level in 0.1f..0.8f)
            } else {
                assertTrue("$stage resting edge must be fully opaque: $levels", level > 0.95f)
            }
        }
    }

    /** A fitting destination has one opaque row at the start and no lingering scroll eligibility. */
    fun assertFitting(stage: String) {
        val levels = capture(stage, top = false, bottom = false)
        assertTrue("$stage fitting row must immediately lose the top mask: $levels", levels.first() > 0.95f)
    }

    /** Save each frame before assertions so failed eligibility or paint is inspectable in the hosted report. */
    private fun capture(
        stage: String,
        top: Boolean,
        bottom: Boolean,
    ): List<Float> {
        rule.waitForIdle()
        val node = rule.onNodeWithTag("chats.list")
        val axis = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val position = axis.value()
        val maximum = axis.maxValue()
        val image = node.captureToImage()
        val pixels = image.toPixelMap()
        val inset = with(rule.density) { 12.dp.roundToPx() }
        val levels =
            listOf(inset, pixels.height / 2, pixels.height - inset).map { y ->
                (pixels.width / 4 until pixels.width / 2).maxOf { x -> pixels[x, y].green }
            }
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val directory = File(output, "chat-folder-fades").also { check(it.isDirectory || it.mkdirs()) }
        File(directory, "$stage.png").outputStream().use { stream ->
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        File(directory, "$stage.json").writeText(
            JSONObject()
                .put("frame_time_ms", rule.mainClock.currentTime)
                .put("scroll_position", position)
                .put("scroll_maximum", maximum)
                .put("top_expected", top)
                .put("bottom_expected", bottom)
                .put("green_levels", JSONArray(levels))
                .toString(),
        )
        assertEquals("$stage top eligibility", top, position > 0f)
        assertEquals("$stage bottom eligibility", bottom, position < maximum)
        return levels
    }
}
