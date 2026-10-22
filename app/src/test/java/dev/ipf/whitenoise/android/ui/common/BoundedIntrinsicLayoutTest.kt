package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

/** Actual text measurement exercises the same frame/quote composition used by the timeline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class BoundedIntrinsicLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    /** Huge reply text is measured safely even before its line cap can take effect. */
    @Test
    fun oversizedReplyBodyFitsAndCanReturnToNaturalWidth() {
        val body = mutableStateOf("W".repeat(70_000))
        composeRule.setContent { BoundedReplyFixture(body.value, "Quote") }
        assertWithinWidth("bubble", 300)
        composeRule.runOnIdle { body.value = "OK" }
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        val width =
            composeRule
                .onNodeWithTag("bubble")
                .fetchSemanticsNode()
                .boundsInRoot
                .width
        assertTrue("short replies should still hug their content", width < 300f)
    }

    /** The uncached quote path remains bounded before any native Markdown result exists. */
    @Test
    fun oversizedQuotedTextFitsOnTheFirstFrame() {
        composeRule.setContent { BoundedReplyFixture("OK", "W".repeat(70_000)) }
        assertWithinWidth("bubble", 300)
        composeRule.onNodeWithText("W".repeat(200)).assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    /** A remote sender name has its own text node and must not bypass the frame's width guard. */
    @Test
    fun oversizedQuoteSenderFits() {
        composeRule.setContent { BoundedReplyFixture("OK", "Quote", senderTitle = "W".repeat(70_000)) }
        assertWithinWidth("bubble", 300)
    }

    /** Height guards clamp an overflowing intrinsic result before asking the child to measure. */
    @Test
    fun oversizedIntrinsicHeightFitsBeforeChildMeasurement() {
        val measured = AtomicReference<Constraints>()
        composeRule.setContent {
            Box(Modifier.width(300.dp).height(400.dp)) {
                Layout(
                    content = {},
                    modifier = Modifier.boundedIntrinsicHeight(IntrinsicSize.Min),
                    measurePolicy =
                        object : MeasurePolicy {
                            /** Records the actual child constraints, avoiding a font-dependent giant paragraph. */
                            override fun MeasureScope.measure(
                                measurables: List<Measurable>,
                                constraints: Constraints,
                            ): MeasureResult {
                                measured.set(constraints)
                                return layout(0, constraints.maxHeight) {}
                            }

                            /** Simulates the unwrapped height of content beyond Compose's packed range. */
                            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                                measurables: List<IntrinsicMeasurable>,
                                width: Int,
                            ): Int = 1_276_071
                        },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(400, measured.get().minHeight)
        assertEquals(400, measured.get().maxHeight)
    }

    /** Font scale and RTL do not turn a bounded long quote or reply back into an intrinsic overflow. */
    @Test
    fun oversizedReplyAndQuoteFitNarrowLargeFontRtl() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                BoundedReplyFixture("W".repeat(70_000), "W".repeat(70_000), narrow = true)
            }
        }
        assertWithinWidth("bubble", 180)
    }

    /** Repeated intrinsic modifiers must answer ancestor queries instead of measuring a child twice. */
    @Test
    fun nestedIntrinsicPoliciesKeepNormalSizing() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(300.dp)) {
                    Column(Modifier.boundedIntrinsicWidth(IntrinsicSize.Max)) {
                        Column(
                            Modifier
                                .boundedIntrinsicWidth(IntrinsicSize.Min)
                                .boundedIntrinsicHeight(IntrinsicSize.Min)
                                .boundedIntrinsicHeight(IntrinsicSize.Max)
                                .testTag("nested"),
                        ) {
                            Text("Normal text")
                        }
                    }
                }
            }
        }
        assertWithinWidth("nested", 300)
        composeRule.onNodeWithText("Normal text").assertIsDisplayed()
    }

    /** Checks actual layout bounds so clipping cannot conceal an oversized child. */
    private fun assertWithinWidth(
        tag: String,
        maximum: Int,
    ) {
        val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$tag exceeds its available width: $bounds", bounds.right - bounds.left <= maximum.dp)
    }
}

/** A real bubble frame and first-frame quote, without requiring an account or native runtime. */
@Composable
internal fun BoundedReplyFixture(
    body: String,
    quote: String,
    narrow: Boolean = false,
    dark: Boolean = false,
    amoled: Boolean = false,
    senderTitle: String = "Alice",
) {
    WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
        Box(Modifier.width(if (narrow) 180.dp else 300.dp)) {
            MessageBubbleFrame(
                presentation = messageBubblePresentation(deleted = false, mine = false),
                highlighted = false,
                mine = false,
                mentionedSelf = false,
                mentionedYouLabel = "",
                modifier = Modifier.testTag("bubble"),
                contentModifier = Modifier.boundedIntrinsicWidth(IntrinsicSize.Max),
            ) {
                ReplyPreviewCard(
                    senderTitle = senderTitle,
                    isOwn = false,
                    body = quote,
                    mediaKind = ReplyMediaKind.None,
                    onClick = null,
                    onDismiss = null,
                )
                Text(body, maxLines = 3)
            }
        }
    }
}
