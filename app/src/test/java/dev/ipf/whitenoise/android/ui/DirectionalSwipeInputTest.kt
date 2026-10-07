package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.directionalSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Observes final-pass pointer ownership rather than inferring Off behavior from a missing command. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class DirectionalSwipeInputTest {
    @get:Rule val rule = createComposeRule()
    private val consumed = mutableListOf<Boolean>()
    private var releases = 0

    /** Both disabled bindings must leave all movement available to other recognizers. */
    @Test fun bothOffNeverConsume() {
        render(left = false, right = false)
        drag(100f, 0f)
        assertTrue(consumed.isNotEmpty())
        assertFalse(consumed.any { it })
        assertEquals(0, releases)
    }

    /** A recognizer for one side must not swallow the opposite, disabled stream. */
    @Test fun disabledDirectionNeverConsumesEvenWhenTheOtherSideIsEnabled() {
        render(left = false, right = true)
        drag(-100f, 0f)
        assertFalse(consumed.any { it })
        assertEquals(0, releases)
    }

    /** Vertical intent remains available to the containing scrolling list. */
    @Test fun verticalMovementIsNotConsumed() {
        render(left = true, right = true)
        drag(20f, 100f)
        assertFalse(consumed.any { it })
        assertEquals(0, releases)
    }

    /** Only intentional enabled horizontal movement claims the stream and releases once. */
    @Test fun enabledHorizontalMovementConsumesAndReleasesOnce() {
        render(left = false, right = true)
        drag(100f, 0f)
        assertTrue(consumed.any { it })
        assertEquals(1, releases)
    }

    /** The outer observer runs after the production detector has had an opportunity to consume. */
    private fun render(
        left: Boolean,
        right: Boolean,
    ) {
        rule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("swipe.input")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                event.changes.filter { it.position != it.previousPosition }.forEach {
                                    consumed.add(it.isConsumed)
                                }
                            }
                        }
                    }.directionalSwipe(Unit, Unit, left, right, { _, _ -> }, { releases++ }, {}),
            )
        }
    }

    /** Sends one physical stream through the real pointer dispatch phases. */
    private fun drag(
        x: Float,
        y: Float,
    ) {
        rule.onNodeWithTag("swipe.input").performTouchInput {
            down(center)
            moveBy(Offset(x, y))
            up()
        }
    }
}
