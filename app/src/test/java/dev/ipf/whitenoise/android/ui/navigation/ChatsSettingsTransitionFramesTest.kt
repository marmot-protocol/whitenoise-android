package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppPageTravel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * One account opens Settings from its real Chats avatar and returns through MainShell. With
 * ANIMATION_CAPTURE_FRAMES=1, export all three cycles for visual inspection at 16 ms intervals.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsSettingsTransitionFramesTest {
    @get:Rule val rule = createComposeRule()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun light() = capture("light", dark = false, amoled = false)

    @Suppress("LongMethod")
    private fun capture(
        theme: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(context, 1f)
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
            repeat(if (captureFrames) 3 else 1) { cycle ->
                val directory = File("build/transition-frames/$theme/cycle-${cycle + 1}")
                rule.mainClock.autoAdvance = false
                val open = File(directory, "open")
                writeFrame(open, 0)
                rule.onNodeWithTag("chats.switchProfile").performClick()
                checkPageTravel(open, "settings.profile_group", opening = true)
                val close = File(directory, "close")
                writeFrame(close, 0)
                rule.runOnUiThread {
                    checkNotNull(back).onBackPressed()
                    Snapshot.sendApplyNotifications()
                }
                checkPageTravel(close, "chats.switchProfile", opening = false)
                rule.mainClock.autoAdvance = true
                rule.waitForIdle()
            }
        } finally {
            setAnimatorScale(context, originalScale)
            rule.mainClock.autoAdvance = true
            rule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            rule.waitForIdle()
        }
    }

    private fun setAnimatorScale(
        context: Context,
        scale: Float,
    ) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private fun checkPageTravel(
        directory: File,
        tag: String,
        opening: Boolean,
    ) {
        val control = rule.onNodeWithTag(tag, useUnmergedTree = true)
        val controls = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        var firstPresented: DpRect? = null
        repeat(FRAME_COUNT) { index ->
            rule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            writeFrame(directory, index + 1)
            // System Back may commit on a later frame than a Compose click. Keep every frame in the clip.
            if (firstPresented == null && controls.fetchSemanticsNodes().isNotEmpty()) {
                firstPresented = control.getUnclippedBoundsInRoot()
            }
        }
        val first = requireNotNull(firstPresented) { "No $tag destination appeared during the captured leg" }
        val settled = control.getUnclippedBoundsInRoot()
        val travel = (first.left - settled.left).value * if (opening) 1 else -1
        assertTrue(
            "Expected bounded page travel at $tag, observed $travel dp",
            travel > 0f && travel <= AppPageTravel.value + 1f,
        )
        val settledWidth = settled.right - settled.left
        val firstWidth = first.right - first.left
        assertEquals("Native width at $tag", settledWidth.value, firstWidth.value, 0.5f)
        assertEquals("Native vertical position at $tag", settled.top.value, first.top.value, 0.5f)
    }

    private fun writeFrame(
        directory: File,
        index: Int,
    ) {
        if (!captureFrames) return
        directory.mkdirs()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "frame-%04d.png".format(index)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val FRAME_COUNT = 24
        const val PNG_QUALITY = 100
    }
}
