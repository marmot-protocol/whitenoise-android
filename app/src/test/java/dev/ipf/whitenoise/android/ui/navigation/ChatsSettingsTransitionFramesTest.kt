package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review aid, not a gate: writes every 16 ms frame of the production chat list -> Settings open and
 * the Back close through the real MainShell host with one signed-in account. Runs only with
 * ANIMATION_CAPTURE_FRAMES=1 and writes build/transition-frames/<theme>/<direction>/frame-NNNN.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsSettingsTransitionFramesTest {
    @get:Rule val rule = createComposeRule()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun light() = capture("light", dark = false, amoled = false)

    private fun capture(
        theme: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        assumeTrue("set ANIMATION_CAPTURE_FRAMES=1 to write transition frames", captureFrames)
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        val fixture = ReturnFixture()
        val app = fixture.appState
        val holder = MainShellStateHolder(app, SavedStateHandle())
        val controller = holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, app.runtimeGeneration)
        val mounted = mutableStateOf(true)
        var back: OnBackPressedDispatcher? = null
        app.setAppInForeground(true)
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            if (mounted.value) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) { MainShell(app, holder) }
            }
        }
        try {
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                controller.items.size == fixture.rows.size && !controller.isLoading
            }
            rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 1_000L)
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            val open = File("build/transition-frames/$theme/open").apply { deleteRecursively() }
            writeFrame(open, 0)
            rule.onNodeWithTag("chats.switchProfile").performClick()
            recordFrames(open)
            rule.mainClock.autoAdvance = true
            rule.mainClock.advanceTimeBy(1_000L)
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            val close = File("build/transition-frames/$theme/close").apply { deleteRecursively() }
            writeFrame(close, 0)
            rule.runOnUiThread { checkNotNull(back).onBackPressed() }
            recordFrames(close)
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            rule.waitForIdle()
        }
    }

    /** The tapped or Back frame is frame 1; frames follow at 16 ms past the settled end of either leg. */
    private fun recordFrames(directory: File) {
        repeat(FRAME_COUNT) { index ->
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            shadowOf(Looper.getMainLooper()).idle()
            writeFrame(directory, index + 1)
        }
    }

    private fun writeFrame(
        directory: File,
        index: Int,
    ) {
        directory.mkdirs()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "frame-%04d.png".format(index)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val FRAME_COUNT = 28
        const val PNG_QUALITY = 100
    }
}
