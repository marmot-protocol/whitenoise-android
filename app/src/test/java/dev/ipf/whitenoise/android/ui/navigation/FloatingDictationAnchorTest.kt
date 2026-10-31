package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FloatingDictationAnchorTest {
    @Test
    fun outgoingComposerReservesSpaceUntilItActuallyLeaves() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val incoming = Any()
        anchor.report(outgoing, Rect(0f, 500f, 360f, 780f))
        anchor.report(incoming, Rect(0f, 650f, 360f, 780f))
        assertEquals(500f, anchor.topInWindow)
        anchor.remove(outgoing)
        assertEquals(650f, anchor.topInWindow)
        anchor.remove(incoming)
        assertNull(anchor.topInWindow)
    }

    @Test
    fun translatedOffscreenNodeStopsObstructingWithoutWaitingForDisposal() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val replacement = Any()
        anchor.report(outgoing, Rect(0f, 500f, 360f, 780f))
        anchor.report(replacement, Rect(0f, 650f, 360f, 780f))
        anchor.report(outgoing, Rect.Zero)
        anchor.remove(outgoing)
        assertEquals(650f, anchor.topInWindow)
    }

    /** A clipped outgoing footer must not win over the visible replacement or its scroll owner. */
    @Test
    fun offscreenFooterCannotClipTheOnlyCapsuleAway() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val incoming = Any()
        val currentScroll = ScrollState(0)
        val viewport = Rect(0f, 600f, 360f, 680f)
        val slot = Rect(0f, 600f, 360f, 660f)
        anchor.reportFooter(outgoing, Rect(0f, 400f, 360f, 480f), slot, ScrollState(0))
        anchor.reportFooter(incoming, viewport, slot, currentScroll)
        anchor.reportFooter(outgoing, viewport = Rect.Zero)
        assertEquals(slot to viewport, anchor.scrollingCapsule)
        assertSame(currentScroll, anchor.scrollingState)
        anchor.remove(incoming)
        assertNull(anchor.scrollingCapsule)
        assertNull(anchor.scrollingState)
    }
}

/** The one shell action set remains placed even before its current composer slot is measured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingDictationFallbackTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun missingInlineSlotPlacesOneClickableCancelAtSafeFallbackWithoutStaleBounds() {
        val anchor = FloatingDictationAnchor()
        val oldSlot = Any()
        val stale = Rect(10f, 20f, 58f, 68f)
        anchor.reportComposerControls(oldSlot, stale)
        anchor.removeComposerControls(oldSlot)
        anchor.reportFooter(Any(), Rect(0f, 0f, 360f, 640f), Rect(0f, 10f, 360f, 58f), ScrollState(0))
        val actions = mutableListOf<String>()
        var actionInstance: Any? = null
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp).testTag("fallback-root")) {
                FloatingDictationLayout(
                    anchor = anchor,
                    content = { Box(Modifier.fillMaxSize()) },
                    control = {
                        val instance = remember { Any() }
                        SideEffect { actionInstance = instance }
                        Row(Modifier.testTag("fallback-actions")) {
                            listOf("Cancel", "Paste", "Send").forEach { action ->
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .testTag("fallback-$action")
                                        .clickable { actions += action },
                                ) { Text(action) }
                            }
                        }
                    },
                    inlineControl = true,
                )
            }
        }
        rule.waitForIdle()
        assertNull(anchor.composerControlBounds)
        val placed = assertFallbackControlBounds(stale)
        val originalInstance = actionInstance
        listOf("Cancel", "Paste", "Send").forEach { action ->
            val tag = "fallback-$action"
            rule.onAllNodes(hasTestTag(tag) and hasClickAction()).assertCountEquals(1)
            rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        }
        rule.runOnIdle { assertEquals(listOf("Cancel", "Paste", "Send"), actions) }
        rule.runOnIdle { anchor.reportComposerControls(Any(), Rect(80f, 100f, 224f, 148f)) }
        rule.waitForIdle()
        val relocated =
            rule
                .onNodeWithTag("fallback-actions")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Current measured slot must relocate the same action set", relocated != placed)
        assertEquals("Inline slot must ignore an unrelated scrolling footer", 100f, relocated.top, 0.5f)
        assertSame(originalInstance, actionInstance)
        rule.onAllNodes(hasTestTag("fallback-actions")).assertCountEquals(1)
    }

    /** Validates the fallback's unique, visible hit region independently of callback/relocation checks. */
    private fun assertFallbackControlBounds(stale: Rect): Rect {
        rule.onAllNodes(hasTestTag("fallback-actions")).assertCountEquals(1)
        val control = rule.onNodeWithTag("fallback-actions").assertIsDisplayed()
        val placed = control.fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("fallback-root").fetchSemanticsNode().boundsInRoot
        assertTrue(placed.width > 0f && placed.height > 0f)
        assertTrue(placed.left >= root.left && placed.right <= root.right)
        assertTrue(placed.top >= root.top && placed.bottom <= root.bottom)
        assertTrue("Removed slot must not provide the control position", placed != stale)
        return placed
    }

    @Test
    fun scrollingSlotReportsTheActualControlOriginBeforeAndAfterScroll() {
        val anchor = FloatingDictationAnchor()
        val footer = Any()
        val motion = MeasuredControlHandoff(ConversationDictationControlOwner.Persistent)
        val viewport = Rect(0f, 0f, 1080f, 1000f)
        anchor.reportFooter(footer, viewport, Rect(0f, 30f, 360f, 78f), ScrollState(0))
        var placed: Rect? = null
        rule.setContent {
            Box(Modifier.size(360.dp, 96.dp)) {
                FloatingDictationLayout(
                    anchor = anchor,
                    content = { Box(Modifier.fillMaxSize()) },
                    control = {
                        Box(
                            Modifier.size(48.dp).onPlaced {
                                placed = Rect(it.positionInWindow(), it.size.toSize())
                            },
                        )
                    },
                    controlMotion = motion,
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(30f, placed?.top)
            assertEquals(placed, motion.targetBounds)
            assertEquals(placed, motion.captureOrigin())
            // Footer placement reports a new slot as its scroll position changes.
            anchor.reportFooter(footer, slot = Rect(0f, 50f, 360f, 98f))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(50f, placed?.top)
            assertEquals(placed, motion.targetBounds)
            assertEquals(placed, motion.captureOrigin())
        }
    }
}
