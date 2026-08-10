package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageBubbleFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun replyPreviewShowsBodyAndConvergenceWarning() {
        composeRule.setContent {
            MaterialTheme {
                ReplyPreviewCard(
                    senderTitle = "Alice",
                    isOwn = false,
                    body = "Quoted message",
                    warning = "May not be visible to everyone",
                    mediaKind = ReplyMediaKind.None,
                    onClick = null,
                    onDismiss = null,
                )
            }
        }

        composeRule.onNodeWithText("Quoted message").assertIsDisplayed()
        composeRule.onNodeWithText("May not be visible to everyone").assertIsDisplayed()
    }

    @Test
    fun customAmoledReplyAccentUsesCurrentBorderColorInsideBubbleAndAboveMedia() {
        val presentation = customAmoledPresentation()

        assertEquals(
            CUSTOM_BACKGROUND,
            replyPreviewAccentArgb(
                insideBubble = true,
                customBubbleColorActive = true,
                presentation = presentation,
            ),
        )
        assertEquals(
            CUSTOM_BACKGROUND,
            replyPreviewAccentArgb(
                insideBubble = false,
                customBubbleColorActive = true,
                presentation = presentation,
            ),
        )
    }

    @Test
    fun customAmoledBorderKeepsBlackCaptionPlainAndReplyBubbleContent() {
        val captionContentArgb = AtomicInteger()
        val plainContentArgb = AtomicInteger()
        val presentation = customAmoledPresentation()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Column {
                    MessageBubbleFrame(
                        presentation = presentation,
                        highlighted = false,
                        mine = false,
                        mentionedSelf = true,
                        mentionedYouLabel = "Mentioned you",
                        modifier = Modifier.size(width = 120.dp, height = 60.dp).testTag(CAPTION_TAG),
                        contentModifier = Modifier.fillMaxSize(),
                    ) {
                        val contentColor = LocalContentColor.current
                        SideEffect { captionContentArgb.set(contentColor.toArgb()) }
                        Box(Modifier.size(8.dp))
                    }
                    MessageBubbleFrame(
                        presentation = presentation,
                        highlighted = false,
                        mine = false,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                        modifier = Modifier.size(width = 120.dp, height = 60.dp).testTag(PLAIN_TAG),
                        contentModifier = Modifier.fillMaxSize(),
                    ) {
                        val contentColor = LocalContentColor.current
                        SideEffect { plainContentArgb.set(contentColor.toArgb()) }
                        ReplyPreviewCard(
                            senderTitle = "Alex",
                            isOwn = false,
                            body = "Quoted message",
                            mediaKind = ReplyMediaKind.None,
                            onClick = null,
                            onDismiss = null,
                            containerColor = Color.Transparent,
                            contentColor = colorFromArgb(presentation.contentArgb),
                            accentColor = colorFromArgb(presentation.contentArgb),
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(OPAQUE_BLACK_ARGB, presentation.backgroundArgb)
        assertEquals(CUSTOM_BACKGROUND, presentation.borderOverrideArgb)
        assertEquals(MENTION_ACCENT, presentation.mentionAccentArgb)
        assertEquals(OPAQUE_WHITE.toInt(), captionContentArgb.get())
        assertEquals(OPAQUE_WHITE.toInt(), plainContentArgb.get())
        composeRule.onNodeWithTag(CAPTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PLAIN_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mentioned you").assertIsDisplayed()
        composeRule.onNodeWithText("Quoted message").assertIsDisplayed()
    }

    @Test
    fun replyFooterPinsToQuoteWidenedBubbleEnd() {
        composeRule.setContent {
            Column(Modifier.width(IntrinsicSize.Max).testTag(REPLY_BUBBLE_TAG)) {
                Box(Modifier.width(220.dp).height(1.dp))
                BubbleFooterLayout(
                    footer = {
                        Box(
                            Modifier
                                .width(58.dp)
                                .height(12.dp)
                                .testTag(REPLY_FOOTER_TAG),
                        )
                    },
                    modifier =
                        messageBubbleBodyModifier(
                            hasReplyPreview = true,
                            hasMedia = false,
                        ),
                    lastLineWidth = 24,
                ) {
                    Box(Modifier.width(24.dp).height(20.dp))
                }
            }
        }

        composeRule.runOnIdle {
            val bubbleBounds = composeRule.onNodeWithTag(REPLY_BUBBLE_TAG).fetchSemanticsNode().boundsInRoot
            val footerBounds = composeRule.onNodeWithTag(REPLY_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(bubbleBounds.right, footerBounds.right, 1f)
        }
    }

    private fun customAmoledPresentation() =
        resolveBubblePresentationArgb(
            deleted = false,
            amoled = true,
            mine = false,
            customArgb = CUSTOM_BACKGROUND,
            tokens =
                BubblePresentationTokens(
                    errorBackgroundArgb = 0xFFFFDAD6,
                    errorContentArgb = 0xFF410002,
                    surfaceBackgroundArgb = 0xFFE1E3E4,
                    surfaceContentArgb = OPAQUE_WHITE,
                    mineBackgroundArgb = 0xFFB5EFFF,
                    mineContentArgb = 0xFF001F28,
                    mentionAccentArgb = MENTION_ACCENT,
                ),
        )

    @Test
    fun mediaCaptionSharesTheMediaEnvelopeWidth() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    MediaCaptionFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = false),
                        highlighted = false,
                        mine = false,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                        alignEnd = false,
                        modifier = Modifier.testTag(MEDIA_REPLY_COLUMN_TAG),
                        contentModifier = Modifier.testTag(MEDIA_REPLY_CAPTION_TAG),
                        media = { Box(Modifier.width(220.dp).height(100.dp).testTag(MEDIA_REPLY_MEDIA_TAG)) },
                    ) {
                        BubbleFooterLayout(
                            footer = {
                                Box(
                                    Modifier
                                        .width(58.dp)
                                        .height(12.dp)
                                        .testTag(MEDIA_REPLY_FOOTER_TAG),
                                )
                            },
                            modifier =
                                messageBubbleBodyModifier(
                                    hasReplyPreview = true,
                                    hasMedia = true,
                                ),
                            lastLineWidth = 24,
                        ) {
                            Box(Modifier.width(24.dp).height(20.dp))
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle {
            val mediaBounds = composeRule.onNodeWithTag(MEDIA_REPLY_MEDIA_TAG).fetchSemanticsNode().boundsInRoot
            val captionBounds = composeRule.onNodeWithTag(MEDIA_REPLY_CAPTION_TAG).fetchSemanticsNode().boundsInRoot
            val footerBounds = composeRule.onNodeWithTag(MEDIA_REPLY_FOOTER_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(mediaBounds.width, captionBounds.width, 1f)
            assertEquals(mediaBounds.bottom, captionBounds.top, 0.1f)
            assertEquals(captionBounds.right - 14f, footerBounds.right, 1f)
        }
    }

    @Test
    fun mediaCaptionIdentityHeaderInsideSurfacePreservesEnvelopeWidth() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    MediaCaptionFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = false),
                        highlighted = false,
                        mine = false,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                        alignEnd = false,
                        modifier = Modifier.testTag(MEDIA_IDENTITY_FRAME_TAG),
                        contentModifier = Modifier.testTag(MEDIA_IDENTITY_CAPTION_TAG),
                        showIdentityHeader = true,
                        identityHeader = {
                            Box(
                                Modifier
                                    .height(48.dp)
                                    .testTag(MEDIA_IDENTITY_HEADER_TAG),
                            )
                        },
                        media = {
                            Box(Modifier.width(220.dp).height(100.dp).testTag(MEDIA_IDENTITY_MEDIA_TAG))
                        },
                    ) {
                        Box(
                            Modifier
                                .width(58.dp)
                                .height(12.dp)
                                .testTag(MEDIA_IDENTITY_FOOTER_TAG),
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            val frameBounds =
                composeRule.onNodeWithTag(MEDIA_IDENTITY_FRAME_TAG).fetchSemanticsNode().boundsInRoot
            val headerBounds =
                composeRule.onNodeWithTag(MEDIA_IDENTITY_HEADER_TAG).fetchSemanticsNode().boundsInRoot
            val mediaBounds =
                composeRule.onNodeWithTag(MEDIA_IDENTITY_MEDIA_TAG).fetchSemanticsNode().boundsInRoot
            val captionBounds =
                composeRule.onNodeWithTag(MEDIA_IDENTITY_CAPTION_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(frameBounds.left <= headerBounds.left)
            assertTrue(frameBounds.top <= headerBounds.top)
            assertTrue(frameBounds.right >= headerBounds.right)
            assertTrue(frameBounds.bottom >= headerBounds.bottom)
            assertTrue(headerBounds.bottom <= mediaBounds.top + 0.1f)
            assertEquals(mediaBounds.width, captionBounds.width, 1f)
        }
    }

    @Test
    fun mediaItemsKeepTheirSpacingAndCaptionHasNoExternalGap() {
        composeRule.setContent {
            MediaSupplementEnvelope(
                alignEnd = false,
                media = {
                    Box(Modifier.width(20.dp).height(10.dp).testTag(FIRST_MEDIA_TAG))
                    Box(Modifier.width(20.dp).height(10.dp).testTag(SECOND_MEDIA_TAG))
                },
                supplement = {
                    Box(Modifier.width(20.dp).height(10.dp).testTag(MEDIA_SUPPLEMENT_TAG))
                },
            )
        }

        composeRule.runOnIdle {
            val first = composeRule.onNodeWithTag(FIRST_MEDIA_TAG).fetchSemanticsNode().boundsInRoot
            val second = composeRule.onNodeWithTag(SECOND_MEDIA_TAG).fetchSemanticsNode().boundsInRoot
            val supplement = composeRule.onNodeWithTag(MEDIA_SUPPLEMENT_TAG).fetchSemanticsNode().boundsInRoot
            assertEquals(6f, second.top - first.bottom, 0.1f)
            assertEquals(0f, supplement.top - second.bottom, 0.1f)
        }
    }

    @Test
    fun nonReplyFooterKeepsNaturalWidth() {
        composeRule.setContent {
            Column(Modifier.width(220.dp).testTag(NON_REPLY_COLUMN_TAG)) {
                BubbleFooterLayout(
                    footer = { Box(Modifier.width(58.dp).height(12.dp)) },
                    modifier =
                        messageBubbleBodyModifier(
                            hasReplyPreview = false,
                            hasMedia = false,
                        ).testTag(NON_REPLY_BODY_TAG),
                    lastLineWidth = 24,
                ) {
                    Box(Modifier.width(24.dp).height(20.dp))
                }
            }
        }

        composeRule.runOnIdle {
            val columnBounds = composeRule.onNodeWithTag(NON_REPLY_COLUMN_TAG).fetchSemanticsNode().boundsInRoot
            val bodyBounds = composeRule.onNodeWithTag(NON_REPLY_BODY_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(bodyBounds.width < columnBounds.width)
        }
    }

    private companion object {
        const val CAPTION_TAG = "custom-caption-bubble"
        const val PLAIN_TAG = "custom-plain-bubble"
        const val CUSTOM_BACKGROUND = 0xFF336699
        const val MENTION_ACCENT = 0xFF006780
        const val FIRST_MEDIA_TAG = "first-media"
        const val SECOND_MEDIA_TAG = "second-media"
        const val MEDIA_SUPPLEMENT_TAG = "media-supplement"
        const val OPAQUE_WHITE = 0xFFFFFFFF
        const val REPLY_BUBBLE_TAG = "reply-bubble"
        const val REPLY_FOOTER_TAG = "reply-footer"
        const val MEDIA_REPLY_COLUMN_TAG = "media-reply-column"
        const val MEDIA_REPLY_MEDIA_TAG = "media-reply-media"
        const val MEDIA_REPLY_CAPTION_TAG = "media-reply-caption"
        const val MEDIA_REPLY_FOOTER_TAG = "media-reply-footer"
        const val MEDIA_IDENTITY_FRAME_TAG = "media-identity-frame"
        const val MEDIA_IDENTITY_HEADER_TAG = "media-identity-header"
        const val MEDIA_IDENTITY_MEDIA_TAG = "media-identity-media"
        const val MEDIA_IDENTITY_CAPTION_TAG = "media-identity-caption"
        const val MEDIA_IDENTITY_FOOTER_TAG = "media-identity-footer"
        const val NON_REPLY_COLUMN_TAG = "non-reply-column"
        const val NON_REPLY_BODY_TAG = "non-reply-body"
    }
}
