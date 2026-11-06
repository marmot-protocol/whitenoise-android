package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_DICTATION_COMPACT_ACTIONS_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_ACCESSIBILITY_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.COMPOSER_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.JUMP_TO_NEWEST_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream

/** Shared navigation trajectories and pixel assertions; JUnit test names remain in their original class. */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class DictationNavigationScenarioFixture : DictationNavigationRenderFixture() {
    /** Keeps every scenario golden owned by the selected concrete JUnit screenshot class. */
    protected abstract fun captureGolden(path: String)

    /** Uses the real composer surface and the normal bottom-bar inset contract, not its outer box. */
    protected fun captureOtherChatGap(largeRtl: Boolean) {
        val fixture = render(dark = largeRtl, largeRtl = largeRtl)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        assertPaintedComposerGap()
        assertSingleActionSet()
        val variant = if (largeRtl) "large_rtl_dark" else "light"
        captureGolden("src/test/snapshots/dictation_other_chat_painted_gap_$variant.png")
    }

    /** Captures another chat whose jump stack rests above the floating capsule, not under it (#3584). */
    protected fun captureOtherChatJumpStack(
        dark: Boolean,
        amoled: Boolean,
        largeRtl: Boolean,
        variant: String,
    ) {
        val fixture = render(dark = dark, amoled = amoled, largeRtl = largeRtl, jumpStack = true)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        assertSingleActionSet()
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val newest = composeRule.onNodeWithTag(JUMP_TO_NEWEST_TAG).fetchSemanticsNode().boundsInRoot
        val sharesColumn = newest.left < capsule.right && capsule.left < newest.right
        assertTrue("Jump to newest $newest must clear capsule $capsule", !sharesColumn || newest.bottom <= capsule.top)
        captureGolden("src/test/snapshots/dictation_other_chat_jump_stack_$variant.png")
    }

    /** Measures capsule clearance from the painted composer, excluding its outer padding. */
    protected fun assertPaintedComposerGap() {
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val paintedComposer =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("Gap must exclude the composer's outer padding", 12f, paintedComposer.top - capsule.bottom, 0.5f)
    }

    /** Captures each chat-list theme after checking the capsule gap above the pen. */
    protected fun captureChatList(
        dark: Boolean,
        amoled: Boolean,
    ) {
        val fixture = render(dark = dark, amoled = amoled)
        composeRule.runOnIdle { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.waitForIdle()
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val pen = composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Capsule gap uses the painted pen, including its baseline offset",
            12f,
            pen.top - capsule.bottom,
            0.5f,
        )
        assertSingleActionSet()
        val theme =
            if (amoled) {
                "amoled"
            } else if (dark) {
                "dark"
            } else {
                "light"
            }
        captureGolden("src/test/snapshots/dictation_navigation_chat_list_$theme.png")
    }

    /** Expands the compact composer after an inset change and checks retained capsule spacing. */
    protected fun captureCompactExpanded(largeRtl: Boolean) {
        val fixture = render(dark = largeRtl, largeRtl = largeRtl, compact = true)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                renderedView.rootView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 280))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
        val expand =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        composeRule.runOnIdle { assertTrue(expand.action()) }
        composeRule.waitForIdle()

        fun capture(phase: String) {
            val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
            val composer = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("Capsule clears status bar: $capsule", capsule.top >= 24f)
            assertTrue("Clearance: capsule=$capsule composer=$composer", capsule.bottom <= composer.top)
            assertTrue("Compact capsule keeps its native action height", capsule.height <= 48f)
            val variant = if (largeRtl) "large_font_rtl_dark" else "light"
            val snapshotName = "dictation_navigation_compact_${phase}_$variant.png"
            captureGolden("src/test/snapshots/$snapshotName")
        }
        capture("expanded")
        composeRule.runOnIdle { fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable) }
        composeRule.waitForIdle()
        capture("failure")
    }

    /** MainActivity is edge-to-edge with adjustResize; dispatch its IME model and settle navigation. */
    protected fun captureKeyboardActions(
        dark: Boolean,
        largeRtl: Boolean,
        amoled: Boolean = false,
    ) {
        val fixture = render(dark, largeRtl, amoled)
        val capture = fixture.controller.state
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(renderedView.rootView, insets)
        }
        composeRule.waitForIdle()
        assertEquals(300, observedImeBottom)
        assertPaintedComposerGap()
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val keyboardTop = rootBounds.bottom - observedImeBottom
        listOf("Cancel", transcriptPasteLabel, "Send").forEach { label ->
            val matcher = hasContentDescription(label) and hasAnyAncestor(hasTestTag(APP_DICTATION_CONTROL_TAG))
            composeRule.onAllNodes(matcher).assertCountEquals(1)
            val action = composeRule.onNode(matcher).assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must be above the IME", bounds.bottom <= keyboardTop)
            assertTrue("$label must not clip above the root", bounds.top >= rootBounds.top)
            assertTrue("$label must retain visible area", bounds.width > 0f && bounds.height > 0f)
        }
        val stripBounds = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val inputBounds = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val inputVisible = inputBounds.height > 0f && inputBounds.top >= rootBounds.top
        assertTrue("Destination input must remain visible", inputVisible)
        val composerBounds = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Capsule must clear the composer: capsule=$stripBounds composer=$composerBounds",
            composerBounds.top >= stripBounds.bottom,
        )
        assertTrue("Capsule must remain physically right aligned", stripBounds.right > rootBounds.right - 40f)
        assertTrue("Capsule must not become a full-width strip", stripBounds.width < rootBounds.width - 64f)
        assertSame(capture, fixture.controller.state)
        val variant =
            if (amoled) {
                "amoled"
            } else if (largeRtl) {
                "large_font_rtl_dark"
            } else {
                "light"
            }
        captureGolden("src/test/snapshots/dictation_navigation_keyboard_$variant.png")
    }

    /** Checks failed-state commands through real ownership transitions and their retirement frames. */
    protected fun verifyFailedCommandTrajectory(largeRtl: Boolean) {
        val fixture = render(dark = true, amoled = true, largeRtl = largeRtl, withComposerMorph = true)
        composeRule.runOnIdle {
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.waitForIdle()
        assertCommittedFailedFixture(fixture, persistent = false)

        fun commands(): androidx.compose.ui.geometry.Rect {
            val semanticsNode = composeRule.onNodeWithTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG).fetchSemanticsNode()
            return semanticsNode.boundsInRoot
        }

        fun trailingEdge(bounds: androidx.compose.ui.geometry.Rect) = if (largeRtl) bounds.left else bounds.right
        val source = commands()
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        val first = commands()
        assertEquals(
            "Failed label must not move the live command edge before the clock starts",
            trailingEdge(source),
            trailingEdge(first),
            0.5f,
        )
        val floating = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Retain the old finite weighted failure-label allocation",
            minOf(288f, root.width),
            floating.width,
            0.5f,
        )
        assertEquals(source.width, first.width, 0.5f)
        val rightEdges = mutableListOf(trailingEdge(first))
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            val current = commands()
            assertEquals(source.width, current.width, 0.5f)
            rightEdges += trailingEdge(current)
        }
        composeRule.mainClock.advanceTimeBy(64)
        val targetRight = trailingEdge(commands())
        val direction = if (targetRight >= trailingEdge(source)) 1f else -1f
        rightEdges.zipWithNext().forEach { (before, after) ->
            assertTrue(
                "Command motion must not reverse during label width interpolation",
                (after - before) * direction >= -0.5f,
            )
        }
        rightEdges.forEach { edge ->
            assertTrue(edge >= minOf(trailingEdge(source), targetRight) - 0.5f)
            assertTrue(edge <= maxOf(trailingEdge(source), targetRight) + 0.5f)
        }
        firstNavigationFrame { fixture.route = origin }
        assertEquals(source.width, commands().width, 0.5f)
    }

    /** Checks width, screen-edge spacing and painted vertical gaps without choosing a new alignment design. */
    protected fun compareActiveCapsuleDimensions(largeRtl: Boolean) {
        val fixture = render(dark = largeRtl, largeRtl = largeRtl, withComposerMorph = true)
        val session = fixture.controller.state.sessionId
        composeRule.runOnIdle { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.waitForIdle()
        val chatsRoot = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val chatsCapsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val pen = composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
        val penGap = pen.top - chatsCapsule.bottom
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        val otherRoot = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val otherCapsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val composer =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val composerGap = composer.top - otherCapsule.bottom
        assertEquals(session, fixture.controller.state.sessionId)
        assertEquals(
            "Active capsule width must not depend on its current route",
            chatsCapsule.width,
            otherCapsule.width,
            0.5f,
        )
        assertEquals(
            "Physical screen-edge whitespace must remain unchanged",
            chatsRoot.right - chatsCapsule.right,
            otherRoot.right - otherCapsule.right,
            0.5f,
        )
        assertEquals("Actual pen-to-capsule gap", 12f, penGap, 0.5f)
        assertEquals("Actual composer-to-capsule gap", 12f, composerGap, 0.5f)
        if (!largeRtl) {
            assertEquals(
                "Normal resting routes preserve the capsule absolute height",
                chatsCapsule.top,
                otherCapsule.top,
                0.5f,
            )
        }
        composeRule
            .onAllNodesWithContentDescription(
                ApplicationProvider.getApplicationContext<Context>().getString(R.string.voice_message_record),
            ).assertCountEquals(0)
        assertSingleActionSet()
        // Scaffold places a logical-end FAB; the current shell uses physical-right placement.
        // Preserve this diagnostic for RTL review instead of asserting an unapproved horizontal design.
        val logicalPenEdge = if (largeRtl) pen.left else pen.right
        val logicalCapsuleEdge = if (largeRtl) chatsCapsule.left else chatsCapsule.right
        println(
            "DICTATION_CAPSULE_DIMENSIONS rtl=$largeRtl width=${chatsCapsule.width} " +
                "rightMargin=${chatsRoot.right - chatsCapsule.right} penGap=$penGap composerGap=$composerGap " +
                "logicalPenEdgeDelta=${logicalCapsuleEdge - logicalPenEdge}",
        )
    }

    /** Pauses real route motion, reverses it, then checks every frame through an optional inset change. */
    protected fun verifyPersistentTrajectory(
        largeRtl: Boolean,
        moveIme: Boolean = false,
    ) {
        val fixture = render(dark = true, amoled = !largeRtl, largeRtl = largeRtl, withComposerMorph = true)
        val chats = origin.copy(selectedChatId = null, selectedGroupIdHex = null)
        val other = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        composeRule.runOnIdle { fixture.route = chats }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(chats, fixture.committedRoute)
            assertEquals(ComposerFabEndpoint.NewMessage, requireNotNull(fixture.handoff).destination)
            assertFalse(requireNotNull(fixture.handoff).active)
        }
        composeRule.onNodeWithContentDescription("New Message").assertIsDisplayed()
        val session = fixture.controller.state.sessionId
        val variant =
            if (moveIme) {
                "keyboard"
            } else if (largeRtl) {
                "large_rtl_dark"
            } else {
                "amoled"
            }
        firstNavigationFrame { fixture.route = other }
        assertPersistentPaintedGap(fixture)
        capturePersistentFrame("first", variant)
        repeat(5) {
            composeRule.mainClock.advanceTimeByFrame()
            assertPersistentPaintedGap(fixture)
        }
        capturePersistentFrame("middle", variant)
        firstNavigationFrame { fixture.route = chats }
        assertPersistentPaintedGap(fixture)
        capturePersistentFrame("reverse_first", variant)
        repeat(18) {
            composeRule.mainClock.advanceTimeByFrame()
            assertPersistentPaintedGap(fixture)
        }
        capturePersistentFrame("chats_settled", variant)
        firstNavigationFrame { fixture.route = other }
        repeat(18) { index ->
            if (moveIme && index == 3) {
                composeRule.runOnUiThread {
                    val insets =
                        WindowInsetsCompat
                            .Builder()
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 180))
                            .setVisible(WindowInsetsCompat.Type.ime(), true)
                            .build()
                    ViewCompat.dispatchApplyWindowInsets(renderedView.rootView, insets)
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            assertPersistentPaintedGap(fixture)
        }
        if (moveIme) assertEquals(180, observedImeBottom)
        capturePersistentFrame("other_settled", variant)
        assertEquals(session, fixture.controller.state.sessionId)
    }

    /** Starts with real inline controls, then changes the container twice before either morph settles. */
    protected fun verifyMixedOwnershipTrajectory(moveIme: Boolean) {
        val fixture = render(dark = true, largeRtl = true, withComposerMorph = true)
        val chats = origin.copy(selectedChatId = null, selectedGroupIdHex = null)
        val other = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        val session = fixture.controller.state.sessionId
        assertEquals(origin, fixture.committedRoute)
        firstNavigationFrame { fixture.route = chats }
        composeRule.mainClock.advanceTimeBy(64)
        assertTrue(requireNotNull(fixture.handoff).active)
        val departingActions = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInWindow
        val settledCapsuleBottom = requireNotNull(fixture.handoff?.targetBounds).top - 12f
        assertTrue(
            "The real inline command motion must still be in flight before opening another chat",
            kotlin.math.abs(departingActions.bottom - settledCapsuleBottom) > 1f,
        )
        val variant = if (moveIme) "mixed_keyboard" else "mixed_large_rtl_dark"
        firstNavigationFrame { fixture.route = other }
        assertCoupledGapFrames(fixture, variant, "other", moveIme, frames = 6)
        firstNavigationFrame { fixture.route = chats }
        assertCoupledGapFrames(fixture, variant, "reverse", moveIme = false, frames = 18)
        assertFalse(requireNotNull(fixture.handoff).active)
        assertPersistentPaintedGap(fixture)
        assertEquals(session, fixture.controller.state.sessionId)
    }

    /** The real command gap converges with the container's measured width progress, across both retirement frames. */
    protected fun assertCoupledGapFrames(
        fixture: Fixture,
        variant: String,
        leg: String,
        moveIme: Boolean,
        frames: Int,
    ) {
        val motion = requireNotNull(fixture.handoff)
        val source = requireNotNull(motion.renderedBounds)
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInWindow
        val sourceGap = source.top - capsule.bottom
        capturePersistentFrame("${leg}_first", variant)
        repeat(frames) { index ->
            if (moveIme && index == 2) {
                composeRule.runOnUiThread {
                    val insets =
                        WindowInsetsCompat
                            .Builder()
                            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 180))
                            .setVisible(WindowInsetsCompat.Type.ime(), true)
                            .build()
                    ViewCompat.dispatchApplyWindowInsets(renderedView.rootView, insets)
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            val target = requireNotNull(motion.targetBounds)
            val moving = motion.presentationBounds ?: target
            assertTrue(
                "The interrupted fixture needs distinct container widths",
                kotlin.math.abs(target.width - source.width) > 1f,
            )
            val fraction = ((moving.width - source.width) / (target.width - source.width)).coerceIn(0f, 1f)
            val current = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInWindow
            val expectedGap = sourceGap + (12f - sourceGap) * fraction
            assertEquals(
                "Older command motion must not finish before the restarted container",
                expectedGap,
                moving.top - current.bottom,
                1.6f,
            )
            assertSingleActionSet()
        }
        if (moveIme) assertEquals(180, observedImeBottom)
        capturePersistentFrame("${leg}_last", variant)
    }

    /** Saves the real shell pixels for the same-owner gap assertions. */
    protected fun capturePersistentFrame(
        frame: String,
        variant: String,
    ) {
        captureGolden("src/test/snapshots/dictation_persistent_${frame}_$variant.png")
    }

    /** Checks visible command bounds against the same frame's painted edge, including first and retirement frames. */
    protected fun assertPersistentPaintedGap(fixture: Fixture) {
        val motion = requireNotNull(fixture.handoff)
        val painted = composeRule.runOnIdle { requireNotNull(motion.presentationBounds ?: motion.targetBounds) }
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInWindow
        assertEquals(
            "Persistent actions follow the painted border without a final-anchor jump",
            12f,
            painted.top - capsule.bottom,
            0.6f,
        )
        assertSingleActionSet()
    }

    /** A failure filename alone cannot qualify a fixture whose terminal state or owner never committed. */
    protected fun assertCommittedFailedFixture(
        fixture: Fixture,
        persistent: Boolean,
    ) {
        composeRule.runOnIdle {
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            assertEquals(fixture.route, fixture.committedRoute)
        }
        val tag = if (persistent) APP_DICTATION_CONTROL_TAG else "composer-dictation-shell-control"
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
        if (persistent) {
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Committed recovery label retains its weighted allocation",
                minOf(288f, root.width),
                bounds.width,
                0.5f,
            )
        }
    }

    /** Checks actual pixels on every frame and optionally preserves them without interpolation. */
    protected fun exportCurrentFrame(
        scene: String,
        index: Int,
    ) {
        check(scene in setOf("active_departure", "active_return", "active_reverse"))
        check(index in 0..31)
        check(!composeRule.mainClock.autoAdvance)
        assertSingleActionSet()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertSettledArtwork(bitmap)
        assertGripFrame(bitmap, scene, index)
        if (System.getenv("WHITENOISE_EXPORT_DICTATION_FRAMES") != "1") return
        val directory = File("build/reports/dictation-morph-frames-alignment-r1", scene)
        check(directory.mkdirs() || directory.isDirectory)
        val image = File(directory, "%03d.png".format(index))
        check(image.createNewFile()) { "Preserve existing diagnostic frame instead of overwriting it" }
        FileOutputStream(image).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        val manifest = File(directory, "frames.tsv")
        if (index == 0) {
            check(manifest.createNewFile()) { "Preserve existing diagnostic manifest" }
            manifest.writeText("index\tclock_ms\twidth\theight\n")
        }
        manifest.appendText("$index\t${composeRule.mainClock.currentTime}\t${bitmap.width}\t${bitmap.height}\n")
    }

    /** No await-idle loop or recognition callback may advance the clock past this single commit. */
    protected fun firstNavigationFrame(change: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        val before = composeRule.mainClock.currentTime
        composeRule.runOnUiThread {
            change()
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(before + 16L, composeRule.mainClock.currentTime)
        assertEquals(renderedFixture.route, renderedFixture.committedRoute)
        assertEquals(renderedFixture.locked, renderedFixture.committedLocked)
    }

    /** Requires one visible Cancel, Paste and Send action under the live command owner. */
    protected fun assertSingleActionSet() {
        listOf("Cancel", transcriptPasteLabel, "Send").forEach { label ->
            val action =
                hasContentDescription(label) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG))
            composeRule.onAllNodes(action).assertCountEquals(1)
            composeRule.onNode(action).assertIsDisplayed()
        }
    }

    /** Pixel evidence for the same first departure frame and the fully returned composer. */
    protected fun captureNavigation(
        dark: Boolean,
        largeRtl: Boolean,
        returning: Boolean,
        amoled: Boolean = false,
    ) {
        val fixture = render(dark, largeRtl, amoled)
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        if (returning) {
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            composeRule.runOnIdle { fixture.route = origin }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
        }
        assertSingleActionSet()
        val phase = if (returning) "returned_composer" else "departure_first_frame"
        val variant =
            if (amoled) {
                "amoled"
            } else if (largeRtl) {
                "large_font_rtl_dark"
            } else {
                "light"
            }
        captureGolden("src/test/snapshots/dictation_navigation_${phase}_$variant.png")
    }
}
