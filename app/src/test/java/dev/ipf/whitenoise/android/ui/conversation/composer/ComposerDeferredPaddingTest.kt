package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Animated insets retain Compose's infinity sentinel in scrollable/unbounded measurement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerDeferredPaddingTest {
    @get:Rule val composeRule = createComposeRule()

    /** Unbounded axes remain unbounded while the visible layout wraps finite content. */
    @Test
    fun unboundedParentPreservesInfinityAndWrapsContent() {
        val measured = render(Constraints())
        assertEquals(Constraints.Infinity, measured.maxWidth)
        assertEquals(Constraints.Infinity, measured.maxHeight)
        composeRule.onNodeWithTag("padded").assertWidthIsEqualTo(54.dp).assertHeightIsEqualTo(36.dp)
    }

    /** A scrolling parent's finite width is reduced while its infinite height is preserved. */
    @Test
    fun onlyTheBoundedAxisIsReduced() {
        val measured = render(Constraints(maxWidth = 200))
        assertEquals(176, measured.maxWidth)
        assertEquals(Constraints.Infinity, measured.maxHeight)
    }

    /** Excess padding cannot create a negative child range or exceed the parent's bounds. */
    @Test
    fun tinyFiniteParentKeepsValidConstraints() {
        val measured = render(Constraints.fixed(10, 8))
        assertEquals(Constraints.fixed(0, 0), measured)
        composeRule.onNodeWithTag("padded").assertWidthIsEqualTo(10.dp).assertHeightIsEqualTo(8.dp)
    }

    /** Gives the production modifier explicit constraints independently of screen ancestors. */
    private fun render(parent: Constraints): Constraints {
        val observed = AtomicReference<Constraints>()
        composeRule.setContent {
            Layout(content = {
                Box(
                    Modifier
                        .testTag("padded")
                        .deferredPadding(start = { 12.dp }, end = { 12.dp }, top = { 8.dp }, bottom = { 8.dp }),
                ) {
                    Layout(content = {}) { _, constraints ->
                        observed.set(constraints)
                        layout(constraints.constrainWidth(30), constraints.constrainHeight(20)) {}
                    }
                }
            }) { measurables, _ ->
                val child = measurables.single().measure(parent)
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }
        }
        composeRule.waitForIdle()
        return observed.get()
    }
}
