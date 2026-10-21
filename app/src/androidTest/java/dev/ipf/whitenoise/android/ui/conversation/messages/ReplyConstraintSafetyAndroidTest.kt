package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicWidth
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises reply measurement with Android's real fonts, Markdown leaves and collapsing footer. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ReplyConstraintSafetyAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A giant body can be mounted, removed and mounted again without poisoning subsequent geometry. */
    @Test
    fun oversizedReplyBodyFitsAndReturnsToNormalSize() {
        val body = mutableStateOf("OK")
        composeRule.setContent { ReplyFixture(body = body.value) }
        val normalBounds = composeRule.onNodeWithTag(BUBBLE).getUnclippedBoundsInRoot()
        repeat(3) {
            composeRule.runOnIdle { body.value = HUGE_TEXT }
            assertBounded("huge-body-$it")
            composeRule.onNodeWithText("Read more").assertIsDisplayed()
            composeRule.runOnIdle { body.value = "OK" }
            composeRule.onNodeWithText("OK").assertIsDisplayed()
            assertEquals(normalBounds, composeRule.onNodeWithTag(BUBBLE).getUnclippedBoundsInRoot())
        }
    }

    /** The first-frame quote fallback clips before an asynchronous preview can replace it. */
    @Test
    fun oversizedQuoteFitsOnFirstFrame() {
        composeRule.setContent { ReplyFixture(quote = HUGE_TEXT) }
        assertBounded("huge-quote")
        composeRule.onNodeWithText("W".repeat(200)).assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    /** Remote display names are also untrusted intrinsic text input. */
    @Test
    fun oversizedQuoteSenderFits() {
        composeRule.setContent { ReplyFixture(sender = HUGE_TEXT) }
        assertBounded("huge-sender")
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    /** Large font rendering exercises native metrics without changing the emulator's shared settings. */
    @Test
    fun oversizedReplyAndQuoteFitLargeFonts() {
        composeRule.setContent { ReplyFixture(HUGE_TEXT, HUGE_TEXT, fontScale = 2f) }
        assertBounded("large-font-ltr")
        composeRule.onNodeWithText("Read more").assertIsDisplayed()
    }

    /** RTL positioning keeps both edges inside the actual available container. */
    @Test
    fun oversizedReplyAndQuoteFitRtl() {
        composeRule.setContent { ReplyFixture(HUGE_TEXT, HUGE_TEXT, direction = LayoutDirection.Rtl) }
        assertBounded("normal-font-rtl")
        composeRule.onNodeWithText("Read more").assertIsDisplayed()
    }

    /** The narrowest scenario combines oversized text, 200% fonts and RTL placement. */
    @Test
    fun oversizedReplyAndQuoteFitNarrowLargeFontRtl() {
        composeRule.setContent {
            ReplyFixture(HUGE_TEXT, HUGE_TEXT, fontScale = 2f, direction = LayoutDirection.Rtl, narrow = true)
        }
        assertBounded("large-font-narrow-rtl")
        composeRule.onNodeWithText("Read more").assertIsDisplayed()
    }

    /** Unclipped bounds and saved platform pixels prevent clipping from hiding an oversized layout. */
    private fun assertBounded(evidenceName: String) {
        composeRule.waitForIdle()
        val container = composeRule.onNodeWithTag(CONTAINER).getUnclippedBoundsInRoot()
        val bubble = composeRule.onNodeWithTag(BUBBLE).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("reply overflowed its container: $bubble outside $container", bubble.left >= container.left)
        assertTrue("reply overflowed its container: $bubble outside $container", bubble.right <= container.right)
        assertTrue(
            "reply must occupy a visible area",
            bubble.right > bubble.left && bubble.bottom > bubble.top,
        )
        saveScreenshot(evidenceName)
    }

    /** Retains actual emulator frames in the standard instrumented-test report artifact. */
    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = output ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).path
        val directory = File(root, "reply-constraints")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Uses production frame, quote, Markdown and collapsed-body components with synthetic local content. */
    @Composable
    @Suppress("LongParameterList")
    private fun ReplyFixture(
        body: String = "OK",
        quote: String = "Quote",
        sender: String = "Alice",
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        narrow: Boolean = false,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, fontScale),
            LocalLayoutDirection provides direction,
        ) {
            WhiteNoiseTheme {
                Box(Modifier.width(if (narrow) 180.dp else 300.dp).testTag(CONTAINER)) {
                    ReplyContents(body, quote, sender)
                }
            }
        }
    }

    /** Measures the same content components that the conversation bubble uses. */
    @Composable
    private fun ReplyContents(
        body: String,
        quote: String,
        sender: String,
    ) {
        MessageBubbleFrame(
            presentation = messageBubblePresentation(deleted = false, mine = false),
            highlighted = false,
            mine = false,
            mentionedSelf = false,
            mentionedYouLabel = "",
            modifier = Modifier.testTag(BUBBLE),
            contentModifier = Modifier.boundedIntrinsicWidth(IntrinsicSize.Max),
        ) {
            ReplyPreviewCard(
                senderTitle = sender,
                isOwn = false,
                body = quote,
                mediaKind = ReplyMediaKind.None,
                onClick = null,
                onDismiss = null,
            )
            BubbleCollapsibleFooterLayout(
                maxBodyHeight = 140.dp,
                readMore = { Text("Read more") },
                footer = { Text("12:45") },
            ) {
                MarkdownMessageBody(
                    document =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blankLinesBefore = byteArrayOf(),
                            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(body)))),
                        ),
                )
            }
        }
    }

    private companion object {
        const val BUBBLE = "reply-constraint-bubble"
        const val CONTAINER = "reply-constraint-container"
        val HUGE_TEXT = "W".repeat(70_000)
    }
}
