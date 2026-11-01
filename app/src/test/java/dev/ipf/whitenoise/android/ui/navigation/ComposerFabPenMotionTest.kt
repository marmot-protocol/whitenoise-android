package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Native pixels from the actual shell, FAB and editor; unequal endpoint heights expose icon snapping. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerFabPenMotionTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale: String? = null
    private var destination by mutableStateOf(ComposerFabEndpoint.Composer)
    private var composerLift by mutableStateOf(144.dp)
    private var handoff: MeasuredControlHandoff? = null

    @Before fun enableMotion() {
        previousScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    @Test fun penPixelsFollowTheContainerAtDifferentHeightsInBothDirections() {
        render()
        val composer = rule.runOnIdle { requireNotNull(handoff?.targetBounds) }
        commit(ComposerFabEndpoint.NewMessage)
        val pen = rule.runOnIdle { requireNotNull(handoff?.targetBounds) }
        assertTrue("The causal fixture must have unequal endpoint heights", abs(pen.center.y - composer.center.y) > 64f)
        assertNull("A pen fades in from an editor, without drawing a second editor", penPixelsNear(composer.center))
        advance(80)
        assertMovingPen()
        settle()
        assertPenAt(pen)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        commit(ComposerFabEndpoint.Composer)
        assertPenAt(pen)
        advance(80)
        assertMovingPen()
        settle()
        assertNull("The pen disappears smoothly into the one live editor", penPixelsNear(composer.center))
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun reversingKeepsTheCurrentlyPaintedPenInsteadOfJumpingToTheFab() {
        destination = ComposerFabEndpoint.NewMessage
        render()
        commit(ComposerFabEndpoint.Composer)
        advance(64)
        assertMovingPen()
        commit(ComposerFabEndpoint.NewMessage)
        assertMovingPen()
        advance(64)
        assertMovingPen()
        settle()
        assertPenAt(rule.runOnIdle { requireNotNull(handoff?.targetBounds) })
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun changingComposerInsetsRetargetsThePenWithoutFinishingTheHandoff() {
        destination = ComposerFabEndpoint.NewMessage
        render()
        commit(ComposerFabEndpoint.Composer)
        advance(64)
        assertMovingPen()
        rule.runOnIdle {
            composerLift += 72.dp
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        advance(16)
        assertMovingPen()
        settle()
        assertFalse(rule.runOnIdle { requireNotNull(handoff).active })
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun disabledMotionUsesOnlyTheSettledPenOrEditor() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        destination = ComposerFabEndpoint.NewMessage
        render()
        val pen = rule.runOnIdle { requireNotNull(handoff?.targetBounds) }
        assertPenAt(pen)
        commit(ComposerFabEndpoint.Composer)
        assertFalse(rule.runOnIdle { requireNotNull(handoff).active })
        assertNull(penPixelsNear(pen.center))
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        commit(ComposerFabEndpoint.NewMessage)
        assertFalse(rule.runOnIdle { requireNotNull(handoff).active })
        assertPenAt(rule.runOnIdle { requireNotNull(handoff?.targetBounds) })
    }

    /** Production MainShell placement and route ownership, with a distinct tint for the actual edit drawable. */
    @Suppress("LongMethod")
    private fun render() {
        val state = ChatRowPortFixtures.state(context)
        rule.setContent {
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    MaterialTheme(
                        colorScheme =
                            MaterialTheme.colorScheme.copy(
                                onSurface = Color.Magenta,
                                outline = Color.Black,
                                outlineVariant = Color.Black,
                            ),
                    ) {
                        Surface(Modifier.fillMaxSize().testTag(ROOT), color = Color.Black) {
                            MainShellNoticeLayout(
                                notice = null,
                                dictationController = state.conversationDictation,
                                dictationComposerRoute =
                                    ConversationDictationComposerRoute(null, null, null, "pen-motion", true, true),
                                appLockScreenVisible = false,
                                handoffOwner = "pen-motion",
                                handoffDestination = destination,
                            ) {
                                handoff = LocalComposerFabHandoff.current
                                val route =
                                    updateTransition(
                                        if (destination == ComposerFabEndpoint.Composer) "chat" else null,
                                        label = "unequal height route",
                                    )
                                ConversationRouteAnimatedContent(route, 1, false, { it ?: "list" }) { chat ->
                                    Endpoints(chat != null)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Composable
    private fun Endpoints(composer: Boolean) {
        Box(Modifier.fillMaxSize()) {
            if (composer) {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> error("The pixel fixture must never send") },
                    initialDraft = TextFieldValue(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerLift),
                )
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.navigationBars,
                    floatingActionButton = { ChatsNewMessageFabContent(missingRelays = false) {} },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding))
                }
            }
        }
    }

    /** The first committed frame is sampled before the shared 200 ms clock advances. */
    private fun commit(target: ComposerFabEndpoint) {
        rule.runOnIdle {
            destination = target
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun settle() = advance(300)

    /** Checks the production icon's native pixels, rather than only the already-correct container geometry. */
    private fun assertMovingPen(): Rect {
        val bounds = rule.runOnIdle { requireNotNull(handoff?.renderedBounds) }
        assertTrue(rule.runOnIdle { requireNotNull(handoff).active })
        assertPenAt(bounds)
        return bounds
    }

    private fun assertPenAt(bounds: Rect) {
        val pixels = penPixelsNear(bounds.center)
        assertNotNull("The actual pen drawable must follow both the measured X and Y", pixels)
        requireNotNull(pixels)
        assertEquals(bounds.center.x, pixels.center.x, 1.5f)
        assertEquals(bounds.center.y, pixels.center.y, 1.5f)
        assertTrue("The glyph keeps its normal size", pixels.width in 16f..20f && pixels.height in 16f..20f)
    }

    /** The distinctive onSurface tint isolates the real edit vector from its container and neutral editor hint. */
    private fun penPixelsNear(center: Offset): Rect? {
        val image = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        val minX = floor(center.x - 14f).toInt().coerceAtLeast(0)
        val maxX = ceil(center.x + 14f).toInt().coerceAtMost(image.width)
        val minY = floor(center.y - 14f).toInt().coerceAtLeast(0)
        val maxY = ceil(center.y + 14f).toInt().coerceAtMost(image.height)
        for (y in minY until maxY) {
            for (x in minX until maxX) {
                val color = image[x, y]
                if (color.red > 0.06f && color.blue > 0.06f && color.green < 0.05f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < left) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private companion object {
        const val ROOT = "actual-composer-pen-motion"
    }
}
