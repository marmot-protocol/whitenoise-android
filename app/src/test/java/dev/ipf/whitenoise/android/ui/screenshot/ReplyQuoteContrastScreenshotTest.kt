package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.messageReplyQuoteColors
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real bubble/card surfaces pin quote separation independently of message direction and quoted author. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2400dp-mdpi")
class ReplyQuoteContrastScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Default, white, black, boundary-grey and saturated custom fills in Light. */
    @Test
    fun replyQuotesLight() = captureMatrix("light")

    /** Identical direction/author cases in Dark preserve readable custom and default roles. */
    @Test
    fun replyQuotesDark() = captureMatrix("dark", dark = true)

    /** AMOLED retains its pure-black fill and existing quote outlines. */
    @Test
    fun replyQuotesAmoled() = captureMatrix("amoled", dark = true, amoled = true)

    /** Narrow bubbles retain author labels and inset geometry at 200% in RTL. */
    @Test
    fun replyQuotesLargeRtl() = captureMatrix("large_rtl", largeRtl = true)

    /** A broad palette catches contrast regressions near the black/white text-selection boundary. */
    @Test
    fun customFillAndSecondaryTextRemainReadableAfterComposition() {
        val colorScheme = lightColorScheme(surface = Color.White, onSurfaceVariant = Color.Gray)
        for (red in 0..255 step 51) {
            for (green in 0..255 step 51) {
                for (blue in 0..255 step 51) {
                    val argb = 0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
                    val content = colorFromArgb(requireNotNull(readableTextArgb(argb)))
                    val colors =
                        messageReplyQuoteColors(true, false, colorFromArgb(argb), content, colorScheme)
                    assertNotEquals(argb, opaqueArgb(colors.container))
                    assertTrue(contrastRatio(opaqueArgb(content), opaqueArgb(colors.container)) >= WCAG_AA_NORMAL_TEXT_CONTRAST)
                    assertTrue(
                        contrastRatio(
                            opaqueArgb(colors.secondary.compositeOver(colors.container)),
                            opaqueArgb(colors.container),
                        ) >= WCAG_AA_NORMAL_TEXT_CONTRAST,
                    )
                }
            }
        }
    }

    /** Both real text/caption/file call sites consume the shared policy, excluding standalone media. */
    @Test
    fun productionQuotesUseTheSharedPolicyAtBothRenderingBoundaries() {
        val body =
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt")
                .readText()
                .functionBody("MessageBubble")
        assertTrue(body.contains("messageReplyQuoteColors("))
        assertTrue(body.contains("val replyFillColor = replyQuoteColors.container"))
        assertTrue(body.contains("val replySecondaryColor = replyQuoteColors.secondary"))
        assertTrue(body.contains("customBubbleColorActive = customBubbleColorActive"))
        assertTrue(body.contains("outgoingReply = mine && bubbleTheme != BubbleTheme.Amoled"))
        assertEquals(2, Regex("containerColor = replyFillColor\\.").findAll(body).count())
        assertTrue(body.contains("containerColor = replyFillColor.takeIf { insideBubble }"))
        assertTrue(body.contains("containerColor = replyFillColor.takeUnless { hasMedia }"))
    }

    /** Captures actual production frames and cards, then samples their painted parent and child fills. */
    private fun captureMatrix(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val palette = if (largeRtl) listOf(null, 0xFFFFFFFFL, 0xFF000000L) else PALETTE
        val cases =
            palette.flatMap { color ->
                listOf(false, true).flatMap { own ->
                    listOf(false, true).map { mine -> QuoteCase(color, mine, own) }
                }
            }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp).testTag(ROOT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            cases.withIndex().chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { (index, case) -> QuoteBubble(case, index, amoled) }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ROOT).captureRoboImage("src/test/snapshots/reply_quote_contrast_$name.png")
        if (!amoled) {
            cases.forEachIndexed { index, case ->
                if (case.color != null) {
                    val parent = composeRule.onNodeWithTag("frame-$index").captureToImage().toPixelMap()
                    val child = composeRule.onNodeWithTag("quote-$index").captureToImage().toPixelMap()
                    val parentFill = opaqueArgb(parent[parent.width / 2, 4])
                    val quoteFill = opaqueArgb(child[child.width - 5, child.height / 2])
                    assertEquals(case.color, parentFill)
                    assertNotEquals(parentFill, quoteFill)
                    assertTrue(
                        contrastRatio(requireNotNull(readableTextArgb(case.color)), quoteFill) >= WCAG_AA_NORMAL_TEXT_CONTRAST,
                    )
                }
            }
        }
    }

    /** Uses the same resolved presentation and quote policy as MessageBubble without a live controller. */
    @Composable
    private fun QuoteBubble(
        case: QuoteCase,
        index: Int,
        amoled: Boolean,
    ) {
        val presentation = messageBubblePresentation(deleted = false, mine = case.mine, customArgb = case.color)
        val content = colorFromArgb(presentation.contentArgb)
        val colors =
            messageReplyQuoteColors(
                customBubbleColorActive = case.color != null && !amoled,
                outgoingReply = case.mine && !amoled,
                background = colorFromArgb(presentation.backgroundArgb),
                content = content,
                colorScheme = MaterialTheme.colorScheme,
            )
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = case.mine,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
            modifier = Modifier.width(156.dp).testTag("frame-$index"),
        ) {
            Column(Modifier.padding(12.dp)) {
                Box(Modifier.testTag("quote-$index")) {
                    ReplyPreviewCard(
                        senderTitle = "Alice",
                        isOwn = case.own,
                        body = "Quoted text",
                        mediaKind = ReplyMediaKind.None,
                        onClick = null,
                        onDismiss = null,
                        containerColor = colors.container,
                        contentColor = content,
                        accentColor = content,
                        secondaryColor = colors.secondary,
                    )
                }
                Text(if (case.mine) "Sent reply" else "Received reply", color = content)
            }
        }
    }

    /** Converts a painted color to the unsigned ARGB format used by the shared contrast helper. */
    private fun opaqueArgb(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private data class QuoteCase(
        val color: Long?,
        val mine: Boolean,
        val own: Boolean,
    )

    private companion object {
        const val ROOT = "reply-quote-contrast"
        val PALETTE = listOf(null, 0xFFFFFFFFL, 0xFF000000L, 0xFF777777L, 0xFFB91C1CL, 0xFF15803DL)
    }
}
