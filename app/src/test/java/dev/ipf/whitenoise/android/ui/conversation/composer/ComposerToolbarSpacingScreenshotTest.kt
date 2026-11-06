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
import androidx.compose.ui.test.performClick
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
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerShortcutSide
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
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

/** Real composer controls use compact centers, nominal 48dp touches and physical left/right groups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerToolbarSpacingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var transitionVoice: VoiceRecordingController

    /** The compact default two-account composer at 360dp keeps Message between the physical groups. */
    @Test fun empty360() = render(ToolbarCase("empty360", 360))

    /** An explicitly crowded selection still wraps without losing its separate action targets. */
    @Test fun crowdedTwoAccounts360() = render(ToolbarCase("crowded360", 360, crowded = true))

    /** At320dp compact visual slots retain usable expanded touch targets. */
    @Test fun empty320() = render(ToolbarCase("empty320", 320))

    /** The default composer retains physical sides in RTL at320dp with2x text. */
    @Test fun empty320RtlLarge() = render(ToolbarCase("empty320_rtl_large", 320, rtl = true, fontScale = 2f))

    /** A drafted default composer keeps Send reachable beside the compact groups at 360dp. */
    @Test fun draft360() = render(ToolbarCase("draft360", 360, draft = "Ready to send"))

    /** A drafted composer at 320dp in RTL with 2x text retains separate targets around Send. */
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

    /** The default one-account composer at360dp keeps Message between compact side groups. */
    @Test fun singleAccount360() = render(ToolbarCase("single360", 360, accounts = 1))

    /** With one account the sending-identity slot is absent and the remaining slots stay evenly spaced at 320dp. */
    @Test fun singleAccount320() = render(ToolbarCase("single320", 320, accounts = 1))

    /** A locked voice recording at 360dp keeps separate Cancel and Stop targets. */
    @Test fun lockedVoice360() = render(ToolbarCase("locked360", 360, locked = true))

    /** A locked voice recording at 320dp in RTL with a 2x font scale keeps Cancel and Stop evenly spaced. */
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

    /** Locking retains an inline composer and the configured physical voice edge in either direction. */
    @Test fun lockedSingleLtrLeft() = recordingTransition("single_ltr_left", false, ComposerShortcutSide.Left)

    /** A right-side voice launcher keeps locked controls on the right in LTR. */
    @Test fun lockedSingleLtrRight() = recordingTransition("single_ltr_right", false, ComposerShortcutSide.Right)

    /** A left-side voice launcher keeps locked controls on the left in RTL. */
    @Test fun lockedSingleRtlLeft() = recordingTransition("single_rtl_left", true, ComposerShortcutSide.Left)

    /** A right-side voice launcher keeps locked controls on the right in RTL. */
    @Test fun lockedSingleRtlRight() = recordingTransition("single_rtl_right", true, ComposerShortcutSide.Right)

    /** An already wrapped two-account composer retains its row floor when locking. */
    @Test fun lockedTwoAccountsKeepRows() {
        render(ToolbarCase("two_accounts_transition", 360, transition = true, crowded = true))
    }

    /** Hidden toolbar rows cannot add lock height when the two-account surface is narrow. */
    @Test fun lockedNarrowTwoAccountsKeepRows() {
        render(ToolbarCase("narrow_two_accounts_transition", 320, transition = true, crowded = true))
    }

    /** The same row floor survives large RTL type; existing label truncation is separately reviewed. */
    @Test fun lockedNarrowRtlLargeKeepRows() {
        render(
            ToolbarCase(
                "narrow_rtl_large_transition",
                320,
                rtl = true,
                fontScale = 2f,
                transition = true,
                crowded = true,
            ),
        )
    }

    /** Uses the real recording controller to verify the idle, active and locked geometry sequence. */
    private fun recordingTransition(
        name: String,
        rtl: Boolean,
        side: ComposerShortcutSide,
    ) = render(ToolbarCase(name, 360, rtl = rtl, accounts = 1, voiceSide = side, transition = true))

    /** Compact discs keep 48dp touch targets, and clicks at or just past each visual edge reach only that disc. */
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
                            width = ComposerShortcutPitch,
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
        val voiceSide: ComposerShortcutSide? = null,
        val transition: Boolean = false,
        val crowded: Boolean = false,
    )

    /** Renders the toolbar for the case, asserts its slot geometry and records the Roborazzi baseline. */
    private fun render(case: ToolbarCase) {
        val settings =
            if (case.crowded) {
                ComposerToolbarSettings(
                    listOf(
                        ComposerShortcut.SendingIdentity,
                        ComposerShortcut.More,
                        ComposerShortcut.Emoji,
                        ComposerShortcut.Paste,
                        ComposerShortcut.Dictation,
                        ComposerShortcut.VoiceNote,
                    ),
                    rightShortcuts =
                        setOf(
                            ComposerShortcut.Emoji,
                            ComposerShortcut.Paste,
                            ComposerShortcut.Dictation,
                            ComposerShortcut.VoiceNote,
                        ),
                )
            } else {
                ComposerToolbarSettings()
            }
        val selected = case.voiceSide?.let { settings.withSide(ComposerShortcut.VoiceNote, it) } ?: settings
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", selected.encode())
            .commit()
        val alice = testAccount("alice", "aa".repeat(32))
        val bob = testAccount("bob", "bb".repeat(32))
        val state =
            emptyAppState(
                accounts = listOf(alice, bob).take(case.accounts),
                activeAccountRef = if (case.accounts == 1) "alice" else "bob",
            )
        composeRule.setContent { toolbarContent(case, state) }
        composeRule.waitForIdle()
        // Preserve the actual rendered state even when a geometry assertion identifies a defect.
        composeRule
            .onNodeWithTag("toolbar-spacing")
            .captureRoboImage("src/test/snapshots/composer_toolbar_spacing_${case.name}.png")
        if (case.transition) assertRecordingTransition(case) else assertToolbarGeometry(case)
    }

    /** Builds the voice and dictation controllers, locking the recording when asked, and hosts the toolbar. */
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
            transitionVoice = voice
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

    /** Locking reserves mandatory controls without counting the hidden idle toolbar a second time. */
    private fun assertRecordingTransition(case: ToolbarCase) {
        val root = composeRule.onNodeWithTag("toolbar-spacing")
        val idle = root.fetchSemanticsNode().boundsInRoot
        val voice = bounds("Hold to record voice message")
        composeRule.runOnIdle { check(transitionVoice.start()) }
        composeRule.waitForIdle()
        root.captureRoboImage("src/test/snapshots/composer_toolbar_spacing_${case.name}_recording.png")
        assertEquals("recording keeps idle height", idle.height, root.fetchSemanticsNode().boundsInRoot.height, 1f)
        composeRule.runOnIdle { transitionVoice.lock() }
        composeRule.waitForIdle()
        root.captureRoboImage("src/test/snapshots/composer_toolbar_spacing_${case.name}_locked.png")
        val locked = root.fetchSemanticsNode().boundsInRoot
        val cancel = bounds("Cancel voice message")
        val stop = bounds("Stop recording")
        assertEquals("lock keeps idle height", idle.height, locked.height, 1f)
        assertTrue("locked controls do not overlap", cancel.right <= stop.left || stop.right <= cancel.left)
        listOf(cancel, stop).forEach { target ->
            assertTrue("locked controls remain inside", target.left >= locked.left && target.right <= locked.right)
        }
        val physicalLeft = case.voiceSide == ComposerShortcutSide.Left
        if (case.voiceSide != null) {
            val controlsCenter = (minOf(cancel.left, stop.left) + maxOf(cancel.right, stop.right)) / 2f
            assertEquals("launcher uses configured side", physicalLeft, voice.center.x < idle.center.x)
            assertEquals("locked controls retain voice side", physicalLeft, controlsCenter < locked.center.x)
        }
        composeRule.onNodeWithContentDescription("Cancel voice message").performClick()
        composeRule.runOnIdle { assertTrue("cancel stops recording", !transitionVoice.isRecording) }
    }

    /** Builds a voice controller backed by a no-op recorder session and unconfined dispatchers. */
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
                    /** Pretends recording began without touching the microphone. */
                    override fun start() = Unit

                    /** Yields no recording, since the fake session captures no audio. */
                    override fun stop(): VoiceRecordingResult? = null

                    /** Discards nothing, because the fake session holds no audio. */
                    override fun cancel() = Unit
                }
            },
            mainDispatcher = Dispatchers.Unconfined,
            recorderDispatcher = Dispatchers.Unconfined,
        )

    /** Hosts the composer bar in a themed, fixed-width surface using the case's font scale and layout direction. */
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

    /** Asserts the visible toolbar slots are evenly spaced and non-overlapping, and that the editor stays usable. */
    private fun assertToolbarGeometry(case: ToolbarCase) {
        val sign = if (case.rtl) -1f else 1f
        if (case.locked) {
            val cancel = bounds("Cancel voice message")
            val stop = bounds("Stop recording")
            assertTargets(listOf(cancel, stop), sign)
        } else {
            val left =
                buildList {
                    if (case.accounts > 1) {
                        add(composeRule.onNodeWithTag("composer-sending-identity").fetchSemanticsNode().boundsInRoot)
                    } else {
                        composeRule.onNodeWithTag("composer-sending-identity").assertDoesNotExist()
                    }
                    add(bounds("Add attachment"))
                    if (!case.crowded) add(bounds("Open emoji picker"))
                }
            val right =
                if (case.crowded) {
                    listOf(bounds("Open emoji picker"), bounds("Paste from clipboard"), bounds("Dictate text"))
                } else {
                    composeRule.onNodeWithContentDescription("Paste from clipboard").assertDoesNotExist()
                    composeRule.onNodeWithContentDescription("Dictate text").assertDoesNotExist()
                    if (case.draft.isNotEmpty()) listOf(bounds("Hold to record voice message")) else emptyList()
                }
            assertTargets(left, sign)
            assertTargets(right, sign)
            val primary = bounds(if (case.draft.isEmpty()) "Hold to record voice message" else "Send")
            assertFootprints(listOf(primary))
            if (case.draft.isEmpty()) assertTargets(right + primary, sign)
            val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "editor retains a usable composing width",
                editor.width >= 96f * case.fontScale && editor.height > 1f,
            )
            assertDistinctFootprints(left + right + primary, expectedToolbarLabels(case))
            if (case.width == 360 && case.fontScale == 1f && case.draft.isEmpty()) {
                assertDefaultFieldPlacement(case, left, right + primary, editor)
            }
        }
    }

    /** Labels for the visible toolbar targets, in the order [assertToolbarGeometry] collects their bounds. */
    private fun expectedToolbarLabels(case: ToolbarCase): List<String> =
        buildList {
            if (case.accounts > 1) add("Sending identity")
            add("Add attachment")
            add("Emoji")
            if (case.crowded) {
                addAll(listOf("Paste", "Dictation"))
            } else if (case.draft.isNotEmpty()) {
                add("Voice")
            }
            add(if (case.draft.isEmpty()) "Voice" else "Send")
        }

    /** Defaults retain inline spacing when they fit, otherwise the editor sits above separated side groups. */
    private fun assertDefaultFieldPlacement(
        case: ToolbarCase,
        left: List<androidx.compose.ui.geometry.Rect>,
        right: List<androidx.compose.ui.geometry.Rect>,
        editor: androidx.compose.ui.geometry.Rect,
    ) {
        if (!case.crowded || case.accounts == 1) {
            assertTrue("physical left actions precede the field", left.all { it.right <= editor.left + 1f })
            assertTrue("physical right actions follow the field", right.all { it.left >= editor.right - 1f })
            assertTrue(
                "single-line controls align with Message",
                (left + right).all { kotlin.math.abs(it.center.y - editor.center.y) < 8f },
            )
        } else {
            assertTrue(
                "wrapped controls stay below the editor",
                (left + right).all { it.top >= editor.bottom - 1f },
            )
            assertTrue(
                "physical side groups stay separated below the editor",
                left.maxOf { it.right } <= right.minOf { it.left } + 1f,
            )
        }
    }

    /** Configured controls retain compact36dp centers within each physical group. */
    private fun assertTargets(
        controls: List<androidx.compose.ui.geometry.Rect>,
        sign: Float,
    ) {
        controls.zipWithNext().forEach { (first, second) ->
            if (kotlin.math.abs(second.center.y - first.center.y) <= 1f) {
                assertEquals(
                    "adjacent selected controls on one row",
                    36f,
                    (second.center.x - first.center.x) * sign,
                    1f,
                )
            } else {
                assertTrue("wrapped controls preserve logical row order", second.top >= first.bottom - 1f)
            }
        }
        assertFootprints(controls)
    }

    private fun assertFootprints(controls: List<androidx.compose.ui.geometry.Rect>) {
        controls.forEach {
            assertTrue("compact visual slot width", it.width in 36f..48f)
            assertEquals("interaction height", 48f, it.height, 1f)
        }
    }

    /** Returns the root bounds of the node with the given content description. */
    private fun bounds(description: String): androidx.compose.ui.geometry.Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private object IdlePlatform : ConversationDictationPlatform {
        /** Reports the microphone permission as granted. */
        override fun hasRecordAudioPermission() = true

        /** Reports speech recognition as available so the dictation slot renders. */
        override fun recognitionAvailable() = true

        /** Fails the test if dictation is ever started, since this host only renders the toolbar. */
        override fun createSession(listener: ToolbarListener): ToolbarSession = error("Unexpected recognition")
    }
}

/** Visual slots stay distinct; platform-expanded touch areas may overlap between compact neighbors. */
private fun assertDistinctFootprints(
    all: List<androidx.compose.ui.geometry.Rect>,
    labels: List<String>,
) {
    all.forEachIndexed { index, first ->
        all.drop(index + 1).forEachIndexed { offset, second ->
            val overlap =
                first.left < second.right - 1f &&
                    first.right > second.left + 1f &&
                    first.top < second.bottom - 1f &&
                    first.bottom > second.top + 1f
            val secondIndex = index + offset + 1
            assertTrue(
                "visual slot overlap: [$index] ${labels[index]}=$first; " +
                    "[$secondIndex] ${labels[secondIndex]}=$second; all=$all",
                !overlap,
            )
        }
    }
}
