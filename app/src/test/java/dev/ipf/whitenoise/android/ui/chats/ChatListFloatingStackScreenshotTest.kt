package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTarget
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import dev.ipf.whitenoise.android.ui.navigation.ConversationDictationControlOwner
import dev.ipf.whitenoise.android.ui.navigation.DICTATION_CONTROL_EXIT_MILLIS
import dev.ipf.whitenoise.android.ui.navigation.DictationControlExit
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlGap
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlVisibility
import dev.ipf.whitenoise.android.ui.navigation.FloatingDictationAnchor
import dev.ipf.whitenoise.android.ui.navigation.FloatingDictationLayout
import dev.ipf.whitenoise.android.ui.navigation.LocalFloatingDictationAnchor
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The chat list's bottom-end stack reads jump-to-top, dictation capsule, pen from the top, with one gap
 * between each present layer. When the capsule shrinks away through its production exit, the jump control
 * follows it down on the capsule's own clock and lands one gap above the pen instead of staying high or
 * overlapping it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h420dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListFloatingStackScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f

    private val capsule = mutableStateOf(true)
    private val jump = mutableStateOf(true)
    private val pen = mutableStateOf(true)

    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotionPolicy() {
        setAnimatorScale(originalAnimatorScale)
    }

    @Test fun allThreeStackWithOneGapLight() {
        render(dark = false)
        assertStacked()
        capture("chat_list_floating_stack_all_light")
    }

    /**
     * RTL keeps the capsule at the physical right, as the dictation suite pins, while the pen and jump
     * control sit at the logical end on the left. The jump control rests on the pen, and the capsule keeps
     * the same gap above the pen's row without touching the jump control's column.
     */
    @Test fun jumpRestsOnThePenBesideTheCapsuleRtlDarkLargeText() {
        render(dark = true, rtl = true, fontScale = 2f)
        val jump = bounds(JUMP)
        val capsule = bounds(CAPSULE)
        assertGap(jump, penBounds())
        assertGap(capsule, penBounds())
        assertTrue("jump=$jump must stay beside capsule=$capsule", jump.right <= capsule.left)
        capture("chat_list_floating_stack_all_rtl_dark_large_text")
    }

    @Test fun jumpControlSettlesOntoThePenWhenTheCapsuleLeavesDark() {
        render(dark = true)
        update { capsule.value = false }
        advanceFrames(DICTATION_CONTROL_EXIT_MILLIS / 2L)
        assertFollowsShrinkingCapsule(bounds(JUMP), bounds(CAPSULE))
        capture("chat_list_floating_stack_capsule_leaving_dark")
        advanceFrames(SETTLE_MILLIS)
        assertGap(bounds(JUMP), penBounds())
        capture("chat_list_floating_stack_no_capsule_dark")
    }

    @Test fun capsuleAndJumpRestOnTheBottomWithoutThePenAmoled() {
        render(dark = true, amoled = true)
        update { pen.value = false }
        advanceFrames(SETTLE_MILLIS)
        assertGap(bounds(JUMP), bounds(CAPSULE))
        capture("chat_list_floating_stack_no_pen_amoled")
    }

    /** Jump control above the capsule, capsule above the pen, each separated by exactly the stack gap. */
    private fun assertStacked() {
        assertGap(bounds(JUMP), bounds(CAPSULE))
        assertGap(bounds(CAPSULE), penBounds())
    }

    /**
     * While the capsule shrinks, the jump control rides one gap above it; a capsule shorter than the gap
     * shares its gap, so the control reaches the pen's resting place exactly as the capsule vanishes.
     */
    private fun assertFollowsShrinkingCapsule(
        jump: Rect,
        capsule: Rect,
    ) {
        assertTrue("capsule=$capsule should be mid-exit", capsule.height > 0f && capsule.height < 48f)
        val expectedBottom = capsule.top - minOf(FloatingControlGap.value, capsule.height)
        assertTrue(
            "jump=$jump should follow capsule=$capsule, expected bottom $expectedBottom",
            kotlin.math.abs(jump.bottom - expectedBottom) <= 1f,
        )
        assertTrue(
            "jump=$jump must stay clear of the pen",
            penBounds().top - jump.bottom >= FloatingControlGap.value - 0.5f,
        )
    }

    private fun assertGap(
        upper: Rect,
        lower: Rect,
    ) {
        val gap = lower.top - upper.bottom
        assertTrue(
            "$upper must sit ${FloatingControlGap.value}px above $lower, gap was $gap",
            gap >= FloatingControlGap.value - 0.5f,
        )
        assertTrue("$upper should not float far above $lower, gap was $gap", gap <= FloatingControlGap.value + 6f)
    }

    private fun bounds(tag: String): Rect =
        if (tag == JUMP) {
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.scroll_to_top))
                .fetchSemanticsNode()
                .boundsInWindow
        } else {
            tagBounds(tag)
        }

    private fun tagBounds(tag: String): Rect =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInWindow

    /** The pen's painted button; its slot wrapper sits higher because the pen aligns with the composer row. */
    private fun penBounds(): Rect =
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.new_message))
            .fetchSemanticsNode()
            .boundsInWindow

    private fun render(
        dark: Boolean,
        rtl: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val anchor = FloatingDictationAnchor()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                CompositionLocalProvider(
                    LocalFloatingDictationAnchor provides anchor,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        FloatingDictationLayout(
                            anchor = anchor,
                            content = { ChatListFixture() },
                            control = {
                                val live = capsule.value
                                DictationControlExit(
                                    owner =
                                        if (live) {
                                            ConversationDictationControlOwner.Persistent
                                        } else {
                                            ConversationDictationControlOwner.Hidden
                                        },
                                    state = if (live) LISTENING else ConversationDictationState.Idle,
                                    facts = FACTS,
                                    motionAllowed = true,
                                ) { _, _, exitModifier ->
                                    // The tag sits outside the exit's cleared semantics, on the shrinking slot.
                                    Box(Modifier.testTag(CAPSULE)) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = exitModifier.size(width = 168.dp, height = 48.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) { Text("0:04") }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
    }

    /** The production pen in a Scaffold FAB slot, under the production jump control and its placement. */
    @Suppress("FunctionNaming")
    @androidx.compose.runtime.Composable
    private fun ChatListFixture() {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                FloatingControlVisibility(visible = pen.value, motionEnabled = true) {
                    Box(Modifier.testTag(PEN)) { ChatsNewMessageFabContent(missingRelays = false, onClick = {}) }
                }
            },
        ) { padding ->
            ChatListJumpToTopControl(
                visible = jump.value,
                onClick = {},
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private fun advanceFrames(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt().coerceAtLeast(1)) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun update(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val CAPSULE = "stack.capsule"
        const val JUMP = "stack.jump"
        const val PEN = "stack.pen"
        const val SETTLE_MILLIS = 600L
        const val FRAME_MILLIS = 16L
        val LISTENING =
            ConversationDictationState.Listening(
                1L,
                ConversationDictationTarget("account", "group", TextFieldValue(), 0, ConversationDictationMode.InApp),
                startedAtElapsedMillis = 0L,
            )
        val FACTS =
            DictationControlFacts(
                captureInProgress = true,
                deliveryInProgress = false,
                completionActionsEnabled = false,
                processingDeliveryMode = null,
                recoveryHandedToComposer = false,
            )
    }
}
