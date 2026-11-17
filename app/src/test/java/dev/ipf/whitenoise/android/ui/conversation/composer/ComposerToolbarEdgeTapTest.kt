package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Toolbar targets keep their whole hit area; only the gaps between them reach the editor (#3857). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerToolbarEdgeTapTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cameraCalls = 0
    private var galleryCalls = 0
    private var keyboardShows = 0
    private val keyboard =
        object : SoftwareKeyboardController {
            override fun show() {
                keyboardShows++
            }

            override fun hide() = Unit
        }

    @Test
    fun shortcutTargetEdgesRunTheirActionWithoutOpeningTheKeyboard() {
        renderCameraAndGallery()
        val (camera, edges) = cameraEdges()
        edges.forEachIndexed { index, point ->
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onRoot().performTouchInput { click(point) }
            composeRule.waitForIdle()
            assertEquals(
                "camera edge $point runs Camera once; camera=$camera galleryCalls=$galleryCalls " +
                    "keyboard=$keyboardShows",
                index + 1,
                cameraCalls,
            )
        }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals("a shortcut tap never requests the keyboard", 0, keyboardShows)
    }

    @Test
    fun blankToolbarGapOpensTheKeyboardAndHoldingAShortcutExplainsIt() {
        renderCameraAndGallery()
        val (camera, _) = cameraEdges()
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        val controls =
            listOf(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
                hasSetTextAction(),
            ).flatMap { matcher ->
                composeRule
                    .onAllNodes(matcher, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .map { it.touchBoundsInRoot.inflate(4f) }
            }
        val gap =
            generateSequence(surface.left + 24f) { it + 4f }
                .takeWhile { it < surface.right - 24f }
                .map { Offset(it, camera.center.y) }
                .first { point -> controls.none { it.contains(point) } }
        composeRule.onRoot().performTouchInput { click(gap) }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("a toolbar gap opens the keyboard", 1, keyboardShows)
        assertEquals("a toolbar gap never runs a shortcut", 0, cameraCalls)
        assertEquals(0, galleryCalls)

        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        assertEquals("holding Camera explains it instead of running it", 0, cameraCalls)
    }

    /** With voice on the toolbar, the empty draft's disabled Send still owns its whole target. */
    @Test
    fun disabledSendTargetEdgesNeverOpenTheKeyboard() {
        val voice =
            VoiceRecordingController(
                context = context,
                outputDirectory = context.cacheDir,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                onPermissionRequest = { false },
                onRecordingComplete = { _, _ -> },
                onError = {},
            )
        try {
            render(
                ComposerToolbarSettings(
                    listOf(ComposerShortcut.Camera, ComposerShortcut.VoiceNote),
                    ComposerToolbarLayout.AlwaysExpanded,
                    rightShortcuts = emptySet(),
                ),
                voice,
            )
            val sendNode = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
            sendNode.assertIsNotEnabled()
            // The enabled Send answers inside its laid-out bounds; disabled, it claims the same area.
            val send = sendNode.fetchSemanticsNode().boundsInRoot
            val others =
                composeRule
                    .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .map { it.boundsInRoot }
                    .filterNot { it == send }
            val edges = (edgePoints(send) + send.center).filterNot { point -> others.any { it.contains(point) } }
            assertTrue("Send must expose edges of its own", edges.size >= 3)
            for (point in edges) {
                composeRule.onRoot().performTouchInput { click(point) }
                composeRule.waitForIdle()
                assertEquals(
                    "disabled Send edge $point never opens the keyboard; send=$send others=$others",
                    0,
                    keyboardShows,
                )
            }
            composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        } finally {
            voice.release()
        }
    }

    /** Camera's touch bounds and the edge points inside them that the neighboring Gallery cannot claim. */
    private fun cameraEdges(): Pair<Rect, List<Offset>> {
        val camera = composeRule.onNodeWithTag("composer.shortcut.camera").fetchSemanticsNode().touchBoundsInRoot
        val gallerySlot = composeRule.onNodeWithTag("composer.shortcut.gallery").fetchSemanticsNode().boundsInRoot
        // Neighboring buttons overlap where their touch areas meet; keep only edges Gallery cannot claim.
        val gallery =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.touchBoundsInRoot }
                .first { it.contains(gallerySlot.center) }
        val edges = edgePoints(camera).filterNot { gallery.contains(it) }
        assertTrue("Camera must expose edges of its own; camera=$camera gallery=$gallery", edges.size >= 3)
        return camera to edges
    }

    private fun edgePoints(bounds: Rect): List<Offset> =
        listOf(
            Offset(bounds.left + 1f, bounds.center.y),
            Offset(bounds.right - 1f, bounds.center.y),
            Offset(bounds.center.x, bounds.top + 1f),
            Offset(bounds.center.x, bounds.bottom - 1f),
        )

    private fun renderCameraAndGallery() =
        render(
            ComposerToolbarSettings(
                listOf(ComposerShortcut.Camera, ComposerShortcut.Gallery),
                ComposerToolbarLayout.AlwaysExpanded,
            ),
        )

    private fun render(
        settings: ComposerToolbarSettings,
        voiceController: VoiceRecordingController? = null,
    ) {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        val appState = emptyAppState()
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                WhiteNoiseTheme {
                    ExplanationHost {
                        Surface {
                            Box(Modifier.width(360.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                                ComposerBar(
                                    appState = appState,
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    softwareKeyboardController = keyboard,
                                    onCaptureFromCamera = { cameraCalls++ },
                                    onPickFromGallery = { galleryCalls++ },
                                    voiceRecordingController = voiceController,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
