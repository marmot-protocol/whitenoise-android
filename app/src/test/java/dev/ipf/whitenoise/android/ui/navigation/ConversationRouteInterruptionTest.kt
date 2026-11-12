package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render, input and composition lifetime checks using the production clock and slot owner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationRouteInterruptionTest {
    @get:Rule val rule = createComposeRule()

    private data class Chat(
        val id: String,
        val revision: Int = 0,
    )

    private val route = mutableStateOf<Chat?>(null)
    private val owner = mutableStateOf("runtime-1")
    private var committed: Chat? = null
    private val live = mutableSetOf<String>()
    private val disposals = mutableMapOf<String, Int>()
    private val clicks = mutableListOf<Chat?>()
    private val a = Chat("a")
    private val b = Chat("b")

    @Test fun earlyEntryBackShrinksAfterCommit() = reverseEntry(16)

    @Test fun middleEntryBackShrinksAfterCommit() = reverseEntry(64)

    @Test fun lateEntryBackShrinksAfterCommit() = reverseEntry(160)

    private fun reverseEntry(entryMillis: Long) {
        mount()
        navigate(a)
        rule.mainClock.advanceTimeBy(entryMillis)
        val before = pixels(Color.Blue)
        assertTrue("Entry must be visible before Back", before > 0)
        navigate(null)
        var previous = pixels(Color.Blue)
        assertTrue("Committed Back must retain the conversation", previous > 0)
        rule.runOnIdle { assertTrue("Live outgoing body must remain composed", "a" in live) }
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            val next = pixels(Color.Blue)
            assertTrue("Back grew after SideEffect commit: $previous -> $next", next <= previous)
            previous = next
        }
        settle()
        assertEquals(0, pixels(Color.Blue))
        rule.runOnIdle {
            assertEquals(setOf("list"), live)
            assertEquals(1, disposals["a"])
        }
    }

    @Test fun reopenDuringExitPreservesBodyAndReversesItsVisibleArea() {
        mount()
        navigate(a)
        settle()
        navigate(null)
        rule.mainClock.advanceTimeBy(64)
        val exiting = pixels(Color.Blue)
        assertTrue("Exit must expose some list", exiting in 1 until 360 * 780)
        navigate(a)
        var previous = pixels(Color.Blue)
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            val next = pixels(Color.Blue)
            assertTrue("Reopened conversation shrank: $previous -> $next", next >= previous)
            previous = next
        }
        settle()
        rule.runOnIdle {
            assertEquals(setOf("a"), live)
            assertEquals("Reopening must not recreate the outgoing body", null, disposals["a"])
        }
        clickCenter()
        rule.runOnIdle { assertEquals(listOf(a), clicks) }
    }

    @Test fun returningToRetainedKeyPlacesItsLatestPayloadAboveOtherChats() {
        mount(morph = false)
        navigate(a)
        settle()
        navigate(b)
        // Commit B, but do not settle: A must still be a live outgoing slot.
        rule.runOnIdle { assertTrue("Both chat bodies must be retained", live.containsAll(listOf("a", "b"))) }
        navigate(a)
        assertCenter(Color.Blue)
        // Same identity, new content: no key-changing effect may be needed to render it.
        val updated = a.copy(revision = 1)
        navigate(updated)
        assertCenter(Color.Green)
        clickCenter()
        rule.runOnIdle {
            assertEquals(listOf(updated), clicks)
            assertEquals("Stable chat identity must keep its body", null, disposals["a"])
        }
        settle()
        rule.runOnIdle { assertEquals(setOf("a"), live) }
    }

    @Test fun latestSameKeyPayloadRemainsVisibleDuringExit() {
        mount()
        navigate(a)
        settle()
        navigate(a.copy(revision = 1))
        assertCenter(Color.Green)
        navigate(null)
        assertTrue("The exiting body must retain its latest payload", pixels(Color.Green) > 0)
        assertEquals("An obsolete payload must never return on exit", 0, pixels(Color.Blue))
        settle()
        rule.runOnIdle { assertEquals(setOf("list"), live) }
    }

    @Test fun runtimeReplacementRetiresThePreviousBodyEvenWithTheSameChatKey() {
        mount()
        navigate(a)
        settle()
        rule.runOnIdle { owner.value = "runtime-2" }
        navigate(a.copy(revision = 1))
        assertCenter(Color.Green)
        rule.runOnIdle {
            assertEquals("Runtime replacement must dispose the old composition", 1, disposals["a"])
            assertEquals(setOf("a"), live)
        }
    }

    @Test fun reducedMotionCommitsEndpointsAndReleasesOutgoingBodies() {
        mount(reduced = true)
        navigate(a)
        assertCenter(Color.Blue)
        rule.runOnIdle { assertEquals(setOf("a"), live) }
        clickCenter()
        rule.runOnIdle { assertEquals(listOf(a), clicks) }
        navigate(null)
        assertCenter(Color.Red)
        rule.runOnIdle {
            assertEquals(setOf("list"), live)
            assertEquals(1, disposals["a"])
        }
        clickCenter()
        rule.runOnIdle { assertEquals(listOf(a, null), clicks) }
    }

    private fun mount(
        reduced: Boolean = false,
        morph: Boolean = true,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SideEffect { committed = route.value }
            ConversationRouteAnimatedContent(
                transition =
                    rememberConversationRouteTransition(route.value, reduced, ownerKey = owner.value) {
                        it?.id
                            ?: "list"
                    },
                routeForwardDirection = 1,
                suppressMotion = reduced,
                contentKey = { it?.id ?: "list" },
                activatingBounds = { if (morph && it != null) Rect(16f, 340f, 344f, 392f) else null },
            ) { chat ->
                val id = chat?.id ?: "list"
                DisposableEffect(id) {
                    live.add(id)
                    onDispose {
                        live.remove(id)
                        disposals[id] = (disposals[id] ?: 0) + 1
                    }
                }
                val color =
                    when {
                        chat == null -> Color.Red
                        chat.revision > 0 -> Color.Green
                        chat.id == "a" -> Color.Blue
                        else -> Color.Yellow
                    }
                Box(Modifier.fillMaxSize().background(color).clickable { clicks.add(chat) })
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    /** A frame can still execute old animation work before committing the new target. */
    private fun navigate(destination: Chat?) {
        rule.runOnIdle {
            route.value = destination
            Snapshot.sendApplyNotifications()
        }
        repeat(4) {
            if (committed != destination) rule.mainClock.advanceTimeByFrame()
        }
        rule.runOnIdle { assertEquals("Target must commit before observations", destination, committed) }
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(CONVERSATION_ROUTE_TRANSITION_MILLIS.toLong() + 64)
        rule.waitForIdle()
    }

    private fun clickCenter() {
        rule.onRoot().performTouchInput { click(Offset(180f, 390f)) }
    }

    private fun assertCenter(expected: Color) {
        val actual = rule.onRoot().captureToImage().toPixelMap()[180, 390]
        assertEquals(expected.red, actual.red, 0.02f)
        assertEquals(expected.green, actual.green, 0.02f)
        assertEquals(expected.blue, actual.blue, 0.02f)
    }

    private fun pixels(expected: Color): Int {
        val image = rule.onRoot().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image[x, y]
                if (kotlin.math.abs(pixel.red - expected.red) < 0.05f &&
                    kotlin.math.abs(pixel.green - expected.green) < 0.05f &&
                    kotlin.math.abs(pixel.blue - expected.blue) < 0.05f
                ) {
                    count++
                }
            }
        }
        return count
    }
}
