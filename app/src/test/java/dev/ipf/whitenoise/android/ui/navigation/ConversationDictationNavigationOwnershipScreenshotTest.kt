package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_DICTATION_COMPACT_ACTIONS_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_ACCESSIBILITY_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_HANDLE_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.ACCOUNT
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.COMPOSER_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.GROUP
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
// Shared Compose fixture and screenshot identity bind this navigation scenario catalog.
@Suppress("LargeClass")
internal class ConversationDictationNavigationOwnershipScreenshotTest : DictationNavigationScenarioFixture() {
    /** Captures shared scenarios through this selected owner using its existing Compose rule. */
    protected override fun captureGolden(path: String) {
        composeRule.onRoot().captureRoboImage(path)
    }

    @After
    fun releaseMotionVoiceFixture() = releasePreviewVoiceController()

    /** The origin uses the same single floating owner as every other route. */
    @Test
    fun sameOriginBubbleLight() {
        render(configured = true)
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dictate text").assertIsNotEnabled()
        val voiceLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.voice_message_record)
        composeRule.onAllNodesWithContentDescription(voiceLabel).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(voiceLabel).assertIsDisplayed().assertIsNotEnabled()
        assertSingleActionSet()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft in progress")
        composeRule
            .onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(COMPOSER_TAG)))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_same_origin_light.png")
    }

    @Test
    fun sameOriginTypedSendLargeFontRtlDark() {
        render(dark = true, largeRtl = true, configured = true)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft in progress")
        composeRule
            .onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(COMPOSER_TAG)))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_same_origin_typed_rtl_dark.png")
    }

    @Test
    fun sameOriginTypedSendAmoled() {
        render(dark = true, amoled = true, configured = true)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft in progress")
        composeRule
            .onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(COMPOSER_TAG)))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_same_origin_typed_amoled.png")
    }

    /** The persistent origin bubble does not take over the destination's ordinary Send. */
    @Test
    fun foreignTypedSendLight() {
        val fixture = render(configured = true)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Another conversation")
        val voiceLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.voice_message_record)
        composeRule.onAllNodesWithContentDescription(voiceLabel).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(voiceLabel).assertIsDisplayed().assertIsNotEnabled()
        composeRule
            .onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(COMPOSER_TAG)))
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_foreign_typed_send_light.png")
    }

    @Test
    fun persistentActionsAboveKeyboardLight() = captureKeyboardActions(dark = false, largeRtl = false)

    @Test
    fun persistentActionsAboveKeyboardLargeRtlDark() = captureKeyboardActions(dark = true, largeRtl = true)

    @Test
    fun keyboardAmoled() = captureKeyboardActions(dark = true, largeRtl = false, amoled = true)

    @Test
    fun returnedComposerAmoled() = captureNavigation(dark = true, largeRtl = false, returning = true, amoled = true)

    @Test
    fun departureAmoled() = captureNavigation(dark = true, largeRtl = false, returning = false, amoled = true)

    @Test
    fun settledChatListLight() = captureChatList(dark = false, amoled = false)

    @Test
    fun settledChatListDark() = captureChatList(dark = true, amoled = false)

    @Test
    fun settledChatListAmoled() = captureChatList(dark = true, amoled = true)

    @Test
    fun otherChatPaintedComposerGapLight() = captureOtherChatGap(largeRtl = false)

    @Test
    fun otherChatPaintedComposerGapLargeRtlDark() = captureOtherChatGap(largeRtl = true)

    /** Light: another chat's jump stack rests above the floating capsule. */
    @Test
    fun otherChatJumpStackAboveCapsuleLight() =
        captureOtherChatJumpStack(
            dark = false,
            amoled = false,
            largeRtl = false,
            variant = "light",
        )

    /** AMOLED: the lifted jump stack stays legible above the capsule on pure black. */
    @Test
    fun otherChatJumpStackAboveCapsuleAmoled() =
        captureOtherChatJumpStack(
            dark = true,
            amoled = true,
            largeRtl = false,
            variant = "amoled",
        )

    /** Dark RTL at 200% text: the mirrored stack and the larger capsule keep clear of each other. */
    @Test
    fun otherChatJumpStackAboveCapsuleLargeRtlDark() =
        captureOtherChatJumpStack(
            dark = true,
            amoled = false,
            largeRtl = true,
            variant = "large_rtl_dark",
        )

    @Test
    @Config(qualifiers = "w360dp-h480dp-mdpi")
    fun compactExpandedOtherChatLight() = captureCompactExpanded(largeRtl = false)

    @Test
    @Config(qualifiers = "w360dp-h480dp-mdpi")
    fun compactExpandedOtherChatLargeRtlDark() = captureCompactExpanded(largeRtl = true)

    @Test
    fun departureFirstFrameLight() = captureNavigation(dark = false, largeRtl = false, returning = false)

    @Test
    fun departureFirstFrameLargeFontRtlDark() = captureNavigation(dark = true, largeRtl = true, returning = false)

    @Test
    fun returnedComposerLight() = captureNavigation(dark = false, largeRtl = false, returning = true)

    @Test
    fun returnedComposerLargeFontRtlDark() = captureNavigation(dark = true, largeRtl = true, returning = true)

    /** A navigation commit retains one floating action set above the outgoing composer. */
    @Test
    fun leaveOriginShowsActionsOnFirstCommittedFrame() {
        val fixture = render()
        assertSingleActionSet()
        val capture = fixture.controller.state

        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }

        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertExists()
        assertSingleActionSet()
        assertSame(capture, fixture.controller.state)
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            assertSingleActionSet()
        }
    }

    /** Re-entering before outgoing disposal retains one persistent root action set. */
    @Test
    fun returnTransfersOwnershipOnFirstCommittedFrame() {
        val fixture = render()
        val capture = fixture.controller.state
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()

        firstNavigationFrame { fixture.route = origin }

        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        assertSingleActionSet()
        assertSame(capture, fixture.controller.state)
    }

    /** Covers same-group account switches and covered composers without touching capture state. */
    @Test
    fun accountAndCoveringRouteChangesDoNotWaitForRecognitionCallbacks() {
        val fixture = render()
        listOf(
            origin.copy(renderedAccountRef = "other-account"),
            origin.copy(navigationAccountStable = false),
            origin.copy(composerVisible = false),
        ).forEach { hiddenOrigin ->
            firstNavigationFrame { fixture.route = hiddenOrigin }
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
            assertSingleActionSet()
            firstNavigationFrame { fixture.route = origin }
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
            assertSingleActionSet()
        }
    }

    /** The lock boundary suppresses both the persistent strip and the protected content. */
    @Test
    fun lockNeverExposesEitherActionSet() {
        val fixture = render()
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null)
            fixture.locked = true
        }
        listOf(
            "Cancel",
            transcriptPasteLabel,
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.paste_from_clipboard),
            "Send",
        ).forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
    }

    /** Shell actions keep the captured account/group even after a different destination is selected. */
    @Test
    fun pasteAfterNavigationWritesOnlyTheImmutableOrigin() {
        val fixture = render()
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
        composeRule
            .onNode(
                hasContentDescription(transcriptPasteLabel) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).performClick()
        composeRule.runOnUiThread { fixture.platform.listener.onResult("dictated words") }
        assertEquals(listOf(ACCOUNT to GROUP), fixture.writes)
        assertEquals("dictated words", fixture.draft.text)
        assertEquals(ConversationDictationState.Idle, fixture.controller.state)
    }

    /** Actual transformed hit testing keeps Cancel owned until its pointer-up dispatch. */
    @Test
    fun midpointCancelUsesOneRealActionAndCancelsOnce() {
        val fixture = render()
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.mainClock.advanceTimeBy(80)
        assertSingleActionSet()
        composeRule
            .onNode(
                hasContentDescription("Cancel") and hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).performTouchInput {
                down(center)
                up()
            }
        composeRule.runOnIdle {
            assertEquals(ConversationDictationState.Idle, fixture.controller.state)
            assertEquals(1, fixture.platform.cancels)
        }
    }

    /** First/intermediate/settled pixels come from the production shell and its single control set. */
    @Test
    // Keep frame-clock actions and ownership assertions in causal order.
    @Suppress("LongMethod")
    fun dictationMorphFramesAmoled() {
        val fixture = render(dark = true, amoled = true, withComposerMorph = true)
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.mainClock.advanceTimeBy(256)
        val chatsTop = requireNotNull(fixture.anchor?.topInWindow)
        firstNavigationFrame { fixture.route = origin }
        composeRule.mainClock.advanceTimeBy(256)
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
            repeat(3) { cycle ->
                listOf(false, true).forEach { returning ->
                    val name = "dictation_cycle_$cycle" + if (returning) "_back" else ""
                    val folder = File("build/dictation-route-motion/$name").apply { mkdirs() }
                    captureScreenRoboImage(File(folder, "source.png").path)
                    firstNavigationFrame {
                        fixture.route =
                            if (returning) origin else origin.copy(selectedChatId = null, selectedGroupIdHex = null)
                    }
                    if (!returning) {
                        composeRule.onNodeWithTag(COMPOSER_TAG).assertExists()
                        assertEquals(chatsTop, requireNotNull(fixture.anchor?.topInWindow), 0.5f)
                    }
                    repeat(32) { frame ->
                        assertSingleActionSet()
                        val frameTime = composeRule.mainClock.currentTime
                        composeRule
                            .onRoot()
                            .captureToImage()
                            .asAndroidBitmap()
                            .captureRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
                        assertEquals(frameTime, composeRule.mainClock.currentTime)
                        composeRule.mainClock.advanceTimeBy(16)
                    }
                    if (!returning) assertEquals(chatsTop, requireNotNull(fixture.anchor?.topInWindow), 0.5f)
                    assertSingleActionSet()
                }
            }
            return
        }
        listOf(false, true).forEach { returning ->
            firstNavigationFrame {
                fixture.route = if (returning) origin else origin.copy(selectedChatId = null, selectedGroupIdHex = null)
            }
            val direction = if (returning) "into_composer" else "into_capsule"
            if (!returning) {
                composeRule.onNodeWithTag(COMPOSER_TAG).assertExists()
                assertEquals(chatsTop, requireNotNull(fixture.anchor?.topInWindow), 0.5f)
            }
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_first_amoled.png")
            composeRule.mainClock.advanceTimeBy(32)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_early_amoled.png")
            composeRule.mainClock.advanceTimeBy(32)
            if (!returning) assertEquals(chatsTop, requireNotNull(fixture.anchor?.topInWindow), 0.5f)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_middle_amoled.png")
            composeRule.mainClock.advanceTimeBy(48)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_late_amoled.png")
            composeRule.mainClock.advanceTimeBy(144)
            if (!returning) assertEquals(chatsTop, requireNotNull(fixture.anchor?.topInWindow), 0.5f)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_settled_amoled.png")
        }
    }

    /** Source-render only: models IME release before committing a route, not production Back dispatch. */
    @Test
    // Three real activation cycles share one retained composer fixture and clock.
    @Suppress("LongMethod")
    fun tallComposerDictationThreeCyclesAmoled() {
        val fixture = render(dark = true, amoled = true, withComposerMorph = true, startImmediately = false)
        val draft = "First synthetic line\nSecond synthetic line\nThird synthetic line\nFourth synthetic line"
        composeRule.onNode(hasSetTextAction()).performTextReplacement(draft)
        composeRule.runOnIdle {
            fixture.draft = TextFieldValue(draft)
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            fixture.platform.listener.onReady()
        }
        val session = fixture.controller.state.sessionId
        val target = requireNotNull(fixture.controller.state.target)
        assertEquals(ACCOUNT, target.accountRef)
        assertEquals(GROUP, target.groupIdHex)
        val captureRoot =
            if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
                val parent = File("build/dictation-route-motion").apply { mkdirs() }
                java.nio.file.Files
                    .createTempDirectory(parent.toPath(), "tall-origin-three-cycles-")
                    .toFile()
            } else {
                null
            }
        val measurements = captureRoot?.resolve("measurements.tsv")
        measurements?.writeText("chapter\tframe\ttimeMs\timeBottom\tpenBounds\tcapsuleBounds\n")

        fun assertOwnership() {
            assertSingleActionSet()
            assertEquals(session, fixture.controller.state.sessionId)
            assertEquals(target, fixture.controller.state.target)
            assertEquals(0, fixture.platform.cancels)
            assertTrue(fixture.writes.isEmpty())
        }

        fun dispatchIme(bottom: Int) {
            composeRule.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(
                    renderedView.rootView,
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                        .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                        .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                        .build(),
                )
            }
            // Insets update Compose state; a paused capture clock must apply that frame first.
            if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            assertEquals(bottom, observedImeBottom)
        }

        fun captureChapter(
            name: String,
            frames: Int,
        ) {
            val folder = captureRoot?.resolve(name)?.apply { check(mkdir()) }
            repeat(frames) { frame ->
                assertOwnership()
                if (folder != null) {
                    val time = composeRule.mainClock.currentTime
                    composeRule
                        .onRoot()
                        .captureToImage()
                        .asAndroidBitmap()
                        .captureRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
                    assertEquals(time, composeRule.mainClock.currentTime)
                    val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
                    val pen =
                        if (fixture.route.selectedChatId == null) {
                            composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
                        } else {
                            null
                        }
                    measurements?.appendText("$name\t$frame\t$time\t$observedImeBottom\t$pen\t$capsule\n")
                }
                composeRule.mainClock.advanceTimeBy(16)
            }
        }

        repeat(3) { cycle ->
            // Re-created route content owns its own edit/expansion state; seed through its real actions.
            composeRule.mainClock.autoAdvance = true
            composeRule.onNode(hasSetTextAction()).performTextReplacement(draft)
            dispatchIme(280)
            val expand =
                composeRule
                    .onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single()
            composeRule.runOnIdle { assertTrue(expand.action()) }
            composeRule.waitForIdle()
            val surface =
                composeRule
                    .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Exercise a genuinely tall ComposerBar", surface.height > 96f)
            composeRule.mainClock.autoAdvance = false
            captureChapter("cycle_${cycle}_01_origin_active", 8)
            // The fixture omits ConversationScreen/its exit coordinator. Never claim this proves Back's wait.
            listOf(180, 80, 0).forEachIndexed { index, bottom ->
                dispatchIme(bottom)
                assertEquals(origin, fixture.route)
                captureChapter("cycle_${cycle}_02_ime_release_$index", 4)
            }
            firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
            captureChapter("cycle_${cycle}_03_departure", 32)
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val pen = composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
            val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(0, observedImeBottom)
            assertEquals("Pen retains compact action height", 48f, pen.height, 0.5f)
            assertEquals("Fixed bottom baseline after tall composer", root.bottom - 24f - 6f, pen.bottom, 0.5f)
            assertEquals("Painted capsule-to-pen gap", FloatingControlGap.value, pen.top - capsule.bottom, 0.5f)
            captureChapter("cycle_${cycle}_04_chats_settled", 32)
            firstNavigationFrame { fixture.route = origin }
            captureChapter("cycle_${cycle}_05_return", 32)
            assertEquals(origin, fixture.route)
            assertOwnership()
            composeRule.onNodeWithTag(COMPOSER_TAG).assertExists()
        }
    }

    /** The same floating shell actions keep their measured size while the route changes. */
    @Test
    fun dictationActionsKeepWidthWhenLeavingComposer() {
        val fixture = render(dark = true, amoled = true)
        val embedded =
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        val floating = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Route ownership must not add padding to the live action set",
            embedded.width,
            floating.width,
            0.5f,
        )
        assertSingleActionSet()
    }

    /** Captures the production listening indicator and chrome before and after an interrupted return. */
    @Test
    fun dictationMorphReversalFramesAmoled() {
        val fixture = render(dark = true, amoled = true)
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.mainClock.advanceTimeBy(64)
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_reverse_before_amoled.png")
        firstNavigationFrame { fixture.route = origin }
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_reverse_first_amoled.png")
        composeRule.mainClock.advanceTimeBy(64)
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_reverse_middle_amoled.png")
        composeRule.mainClock.advanceTimeBy(192)
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_reverse_settled_amoled.png")
    }

    /** Keeps failed-recovery label changes visible for review rather than claiming active-capture coverage. */
    @Test
    fun dictationFailedReturnFramesAmoled() {
        val fixture = render(dark = true, amoled = true)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.waitForIdle()
        assertCommittedFailedFixture(fixture, persistent = true)
        firstNavigationFrame { fixture.route = origin }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_failed_return_first_amoled.png")
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_failed_return_middle_amoled.png")
        composeRule.mainClock.advanceTimeBy(192)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_failed_return_settled_amoled.png")
    }

    /** Failed-label width changes must not teleport the one command set on the first frame. */
    @Test
    fun failedCommandsKeepTrailingEdgeDuringEveryFrame() = verifyFailedCommandTrajectory(largeRtl = false)

    @Test
    fun failedCommandsKeepTrailingEdgeDuringEveryFrameLargeRtl() = verifyFailedCommandTrajectory(largeRtl = true)

    @Test
    fun activeCapsuleDimensionsMatchBetweenChatsAndOtherChatLight() = compareActiveCapsuleDimensions(largeRtl = false)

    @Test
    fun activeCapsuleDimensionsMatchBetweenChatsAndOtherChatLargeRtlDark() {
        compareActiveCapsuleDimensions(largeRtl = true)
    }

    /** Same-owner navigation has no dictation animation: the existing container clock owns placement. */
    @Test
    fun persistentCapsuleFollowsPaintedBorderThroughNavigationAndReverse() {
        verifyPersistentTrajectory(largeRtl = false)
    }

    /** A taller real editor makes a stale final-anchor jump visible instead of hiding it at equal endpoints. */
    @Test
    fun persistentCapsuleFollowsTallRtlBorderThroughNavigationAndReverse() = verifyPersistentTrajectory(largeRtl = true)

    /** Changing actual insets during the same-owner morph keeps the controls on the painted border. */
    @Test
    fun persistentCapsuleFollowsBorderWhileKeyboardMoves() {
        verifyPersistentTrajectory(largeRtl = false, moveIme = true)
    }

    /** Interrupted route motion must not detach the persistent bubble from the painted border. */
    @Test
    fun rapidInlineToChatsToOtherChatKeepsBothClocksContinuous() = verifyMixedOwnershipTrajectory(moveIme = false)

    /** The same interrupted route path keeps its real action geometry while the IME changes the destination. */
    @Test
    fun rapidInlineToChatsToOtherChatWithKeyboardKeepsBothClocksContinuous() {
        verifyMixedOwnershipTrajectory(moveIme = true)
    }

    /** The existing frame clock captures the currently drawn command edge on a Failed-route reversal. */
    @Test
    fun failedCommandsStayContinuousOnMidpointReverse() {
        val fixture = render(dark = true, amoled = true, withComposerMorph = true)
        composeRule.runOnIdle {
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.waitForIdle()
        assertCommittedFailedFixture(fixture, persistent = true)

        fun commands(): androidx.compose.ui.geometry.Rect {
            val semanticsNode = composeRule.onNodeWithTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG).fetchSemanticsNode()
            return semanticsNode.boundsInRoot
        }
        // Reference the uninterrupted trajectory at the same presentation time as the
        // reversing commit. That commit itself advances 16 ms of the existing clock.
        val referenceStart = composeRule.mainClock.currentTime
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.mainClock.advanceTimeBy(80)
        val reference = commands()
        assertEquals(referenceStart + 96L, composeRule.mainClock.currentTime)
        firstNavigationFrame { fixture.route = origin }
        composeRule.mainClock.advanceTimeBy(272)
        assertCommittedFailedFixture(fixture, persistent = true)

        val reversalStart = composeRule.mainClock.currentTime
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.mainClock.advanceTimeBy(64)
        val beforeReverse = commands()
        firstNavigationFrame { fixture.route = origin }
        val afterReverse = commands()
        assertEquals(reversalStart + 96L, composeRule.mainClock.currentTime)
        assertEquals(reference.right, afterReverse.right, 0.5f)
        assertEquals(reference.center.y, afterReverse.center.y, 0.5f)
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(beforeReverse.width, commands().width, 0.5f)
        }
    }

    /** Persistent dictation stays single-owned while the production composer handoff reverses. */
    @Test
    fun combinedComposerAndDictationFramesAmoled() {
        val fixture = render(dark = true, amoled = true, withComposerMorph = true)
        listOf(false, true).forEach { returning ->
            firstNavigationFrame {
                fixture.route = if (returning) origin else origin.copy(selectedChatId = null, selectedGroupIdHex = null)
            }
            val direction = if (returning) "into_composer" else "into_capsule"
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_combined_${direction}_first_amoled.png")
            composeRule.mainClock.advanceTimeBy(64)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage(
                "src/test/snapshots/dictation_combined_${direction}_middle_amoled.png",
            )
            settleDictationHandoff(fixture)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage(
                "src/test/snapshots/dictation_combined_${direction}_settled_amoled.png",
            )
        }
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_combined_reverse_before_amoled.png")
        firstNavigationFrame { fixture.route = origin }
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_combined_reverse_first_amoled.png")
        composeRule.mainClock.advanceTimeBy(64)
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_combined_reverse_middle_amoled.png")
        settleDictationHandoff(fixture)
        assertSingleActionSet()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_combined_reverse_settled_amoled.png")
    }

    /** Every rendered frame retains chrome; optional exports are not physical-device measurements. */
    @Test
    fun activeDictationControlsKeepArtworkOnEveryFrame() {
        val fixture = render(dark = true, amoled = true, withComposerMorph = true)
        val initialTarget = composeRule.runOnIdle { requireNotNull(fixture.handoff?.targetBounds) }
        val initialGrip =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        fixture.gripTopOffset = initialGrip.top - initialTarget.top
        // Include the placement/start frames before the shared tween begins advancing.
        val settleFrameBudget = (APP_MOTION_OPEN_MILLIS + 15) / 16 + 2

        fun exportUntilSettled(
            scene: String,
            firstIndex: Int,
        ) {
            var frames = 0
            while (requireNotNull(fixture.handoff).active && frames < settleFrameBudget) {
                composeRule.mainClock.advanceTimeByFrame()
                frames++
                exportCurrentFrame(scene, firstIndex + frames)
            }
            assertTrue("$scene must settle within the shared motion budget", !requireNotNull(fixture.handoff).active)
        }
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        exportCurrentFrame("active_departure", 0)
        exportUntilSettled("active_departure", 0)
        fixture.incomingCapsuleLeft =
            composeRule
                .onNodeWithTag(APP_DICTATION_CONTROL_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.left
        firstNavigationFrame { fixture.route = origin }
        exportCurrentFrame("active_return", 0)
        exportUntilSettled("active_return", 0)
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        exportCurrentFrame("active_reverse", 0)
        repeat(4) { index ->
            composeRule.mainClock.advanceTimeByFrame()
            exportCurrentFrame("active_reverse", index + 1)
        }
        firstNavigationFrame { fixture.route = origin }
        exportCurrentFrame("active_reverse", 5)
        exportUntilSettled("active_reverse", 5)
        assertTrue("Every route must exercise its settled pixel checks", fixture.checkedArtworkFrames >= 3)
    }
}
