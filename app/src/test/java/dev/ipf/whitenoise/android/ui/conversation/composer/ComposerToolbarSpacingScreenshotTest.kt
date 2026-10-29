package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private typealias ToolbarSession = ConversationDictationRecognitionSession

private typealias ToolbarListener = ConversationDictationRecognitionListener

/** Real composer account, tool and recording targets share one compact layout rhythm. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerToolbarSpacingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun empty360() = render(ToolbarCase("empty360", 360))

    @Test fun empty320() = render(ToolbarCase("empty320", 320))

    @Test fun empty320RtlLarge() = render(ToolbarCase("empty320_rtl_large", 320, rtl = true, fontScale = 2f))

    @Test fun draft360() = render(ToolbarCase("draft360", 360, draft = "Ready to send"))

    @Test fun draft320RtlLarge() =
        render(
            ToolbarCase(
                "draft320_rtl_large",
                320,
                rtl = true,
                fontScale = 2f,
                draft = "A readable draft",
            ),
        )

    @Test fun singleAccount320() = render(ToolbarCase("single320", 320, accounts = 1))

    @Test fun lockedVoice360() = render(ToolbarCase("locked360", 360, locked = true))

    @Test fun lockedVoice320RtlLarge() =
        render(
            ToolbarCase(
                "locked320_rtl_large",
                320,
                rtl = true,
                fontScale = 2f,
                locked = true,
            ),
        )

    @Test
    fun compactSlotsKeepExpandedTouchTargetsAndRouteAdjacentClicks() {
        val clicks = intArrayOf(0, 0)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Row(Modifier.testTag("touch-row").padding(8.dp)) {
                    repeat(2) { index ->
                        ComposerActionDisc(
                            onClick = { clicks[index]++ },
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            description = "tool-$index",
                            icon = R.drawable.ic_add,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val nodes = (0..1).map { composeRule.onNodeWithContentDescription("tool-$it").fetchSemanticsNode() }
        val row = composeRule.onNodeWithTag("touch-row").fetchSemanticsNode().boundsInRoot
        val minimum = with(composeRule.density) { 48.dp.toPx() }
        nodes.forEach { node ->
            assertTrue("platform touch width", node.touchBoundsInRoot.width >= minimum)
            assertTrue("platform touch height", node.touchBoundsInRoot.height >= minimum)
        }
        val first = nodes[0].boundsInRoot
        val second = nodes[1].boundsInRoot
        val points =
            listOf(
                first.center.copy(x = first.left - 2f),
                first.center.copy(x = first.right - 1f),
                second.center.copy(x = second.left + 1f),
                second.center.copy(x = second.right + 2f),
            )
        points.forEach { point ->
            composeRule.onNodeWithTag("touch-row").performTouchInput { click(point - row.topLeft) }
        }
        composeRule.runOnIdle { assertEquals(listOf(2, 2), clicks.toList()) }
    }

    private data class ToolbarCase(
        val name: String,
        val width: Int,
        val rtl: Boolean = false,
        val fontScale: Float = 1f,
        val draft: String = "",
        val accounts: Int = 2,
        val locked: Boolean = false,
    )

    private fun render(case: ToolbarCase) {
        val alice = testAccount("alice", "aa".repeat(32))
        val bob = testAccount("bob", "bb".repeat(32))
        val state =
            emptyAppState(
                accounts = listOf(alice, bob).take(case.accounts),
                activeAccountRef = if (case.accounts == 1) "alice" else "bob",
            )
        composeRule.setContent { toolbarContent(case, state) }
        composeRule.waitForIdle()
        assertToolbarGeometry(case)
        composeRule
            .onNodeWithTag("toolbar-spacing")
            .captureRoboImage("src/test/snapshots/composer_toolbar_spacing_${case.name}.png")
    }

    @Composable
    private fun toolbarContent(
        case: ToolbarCase,
        state: WhiteNoiseAppState,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
        val voice =
            remember {
                createVoice(context, scope)
            }
        LaunchedEffect(voice) {
            if (case.locked) {
                check(voice.start())
                voice.lock()
            }
        }
        DisposableEffect(voice) {
            onDispose {
                voice.release()
                scope.cancel()
            }
        }
        val dictation =
            remember {
                ConversationDictationController(
                    platform = IdlePlatform,
                    readDraft = { _, _ ->
                        ConversationDictationDraftSnapshot(TextFieldValue(case.draft), 0L)
                    },
                    writeDraft = { _, _, _, _ -> 0L },
                    disclosureAccepted = { true },
                    markDisclosureAccepted = {},
                )
            }
        toolbarSurface(case, state, dictation, voice)
    }

    private fun createVoice(
        context: Context,
        scope: CoroutineScope,
    ): VoiceRecordingController =
        VoiceRecordingController(
            context,
            context.cacheDir,
            scope,
            { true },
            { _, _ -> },
            { throw it },
            recorderFactory = { _, _, _ ->
                object : VoiceRecordingSession {
                    override fun start() = Unit

                    override fun stop(): VoiceRecordingResult? = null

                    override fun cancel() = Unit
                }
            },
            mainDispatcher = Dispatchers.Unconfined,
            recorderDispatcher = Dispatchers.Unconfined,
        )

    @Composable
    private fun toolbarSurface(
        case: ToolbarCase,
        state: WhiteNoiseAppState,
        dictation: ConversationDictationController,
        voice: VoiceRecordingController,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, case.fontScale),
            LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(Modifier.width(case.width.dp).testTag("toolbar-spacing")) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            appState = state,
                            draftAccountRef = "alice",
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue(case.draft),
                            onPickDocument = {},
                            dictationController = dictation,
                            dictationAccountRef = "alice",
                            dictationGroupIdHex = "toolbar-group",
                            voiceRecordingController = voice,
                        )
                    }
                }
            }
        }
    }

    private fun assertToolbarGeometry(case: ToolbarCase) {
        val sign = if (case.rtl) -1f else 1f
        if (case.locked) {
            val cancel = bounds("Cancel voice message")
            val stop = bounds("Stop recording")
            assertTargets(listOf(cancel, stop), sign)
        } else {
            val controls =
                buildList {
                    if (case.accounts > 1) {
                        add(composeRule.onNodeWithTag("composer-sending-identity").fetchSemanticsNode().boundsInRoot)
                    }
                    add(bounds("Add attachment"))
                    add(bounds("Open emoji picker"))
                    add(bounds("Paste from clipboard"))
                }
            assertTargets(controls, sign)
            val trailing =
                listOf(
                    bounds("Dictate text"),
                    bounds(if (case.draft.isEmpty()) "Hold to record voice message" else "Send"),
                )
            assertTargets(trailing, sign)
            val row = (controls + trailing).sortedBy { it.left }
            row.zipWithNext().forEach { (first, second) ->
                assertTrue("visual slots do not overlap", first.right <= second.left + 1f)
            }
            val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("editor remains usable", editor.width > 1f && editor.height > 1f)
        }
    }

    private fun assertTargets(
        controls: List<androidx.compose.ui.geometry.Rect>,
        sign: Float,
    ) {
        controls.zipWithNext().forEach { (first, second) ->
            assertEquals("all adjacent toolbar centers", 40f, (second.center.x - first.center.x) * sign, 1f)
        }
        controls.forEach {
            assertEquals("compact visual slot width", 40f, it.width, 1f)
            assertEquals("compact visual slot height", 48f, it.height, 1f)
        }
    }

    private fun bounds(description: String): androidx.compose.ui.geometry.Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private object IdlePlatform : ConversationDictationPlatform {
        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ToolbarListener): ToolbarSession = error("Unexpected recognition")
    }
}
