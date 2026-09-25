package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.MediaCaptionFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInlineFooter
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleBorder
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleFillColor
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleTimestampColor
import dev.ipf.whitenoise.android.ui.conversation.messages.replyPreviewAccentArgb
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.conversation.reactions.reactionSummaryAttachment
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the message-bubble chrome that renders without a live
 * conversation controller: the bubble facsimile used by the font-size
 * preview, the inline time/status footer, and the reply quote card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageBubbleChromeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bubbleChromeLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_chrome_light.png")
    }

    @Test
    fun bubbleChromeDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_chrome_dark.png")
    }

    /** Long captions widen the frame and center narrow portrait media without stretching it. */
    @Test
    fun narrowMediaWithLongCaptionsUsesReadableSharedWidth() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag(NARROW_MEDIA_CAPTION_TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        NarrowCaptionBubble(mine = false)
                        NarrowCaptionBubble(mine = true, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(NARROW_MEDIA_CAPTION_TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_narrow_media_long_caption_light.png")
    }

    @Test
    fun acceptedPendingFooterLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG)) {
                        Text("Accepted, awaiting publication")
                        MessageInlineFooter(
                            timeText = "12:36",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showStatus = true,
                            status = MessageStatus.Pending,
                            editedLabel = null,
                            onEditedClick = null,
                            retention = null,
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_accepted_pending_light.png")
    }

    /** Recent labels stay compact beside incoming and outgoing delivery chrome. */
    @Test
    fun recentElapsedTimeFootersLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(RECENT_TIME_TAG),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DirectionalBubble(text = "Just sent", time = "now", mine = true)
                        DirectionalBubble(text = "Recent reply", time = "1m", mine = false)
                        DirectionalBubble(text = "Approaching clock time", time = "59m", mine = true)
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(RECENT_TIME_TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_recent_elapsed_times_light.png")
    }

    @Test
    fun bubbleChromeAmoledDirectionAccents() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(color = Color.Black) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DirectionalBubble(text = "Incoming message", time = "12:34", mine = false)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DirectionalBubble(text = "Outgoing message", time = "12:35", mine = true)
                        }
                        CustomAmoledReplyBubble(highlighted = false)
                        CustomAmoledReplyBubble(highlighted = true)
                        CustomAmoledMediaCaptionBubble(highlighted = true)
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_chrome_amoled.png")
    }

    @Test
    fun reactionSummariesAmoledRepresentativeStates() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(color = Color.Black) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AmoledReactionBubble(
                            text = "Incoming · one reaction",
                            time = "12:40",
                            outgoing = false,
                            customArgb = CUSTOM_AMOLED_ARGB,
                            tallies = listOf(ReactionTally("👍", 1, mine = false)),
                        )
                        AmoledReactionBubble(
                            text = "Outgoing · one reaction",
                            time = "12:41",
                            outgoing = true,
                            customArgb = OUTGOING_CUSTOM_AMOLED_ARGB,
                            tallies = listOf(ReactionTally("❤️", 1, mine = false)),
                        )
                        AmoledReactionBubble(
                            text = "Incoming · you reacted",
                            time = "12:42",
                            outgoing = false,
                            customArgb = CUSTOM_AMOLED_ARGB,
                            tallies = listOf(ReactionTally("😂", 1, mine = true)),
                        )
                        AmoledReactionBubble(
                            text = "Outgoing · you reacted",
                            time = "12:43",
                            outgoing = true,
                            customArgb = OUTGOING_CUSTOM_AMOLED_ARGB,
                            tallies = listOf(ReactionTally("🎉", 1, mine = true)),
                        )
                        AmoledReactionBubble(
                            text = "Several people reacted",
                            time = "12:44",
                            outgoing = false,
                            tallies =
                                listOf(
                                    ReactionTally("👍", 8, mine = false),
                                    ReactionTally("❤️", 5, mine = false),
                                    ReactionTally("😂", 3, mine = false),
                                ),
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_reactions_amoled.png")
    }

    @Test
    fun reactionSummaryAmoledLargeFontNarrowWidth() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1.6f) {
                Surface(color = Color.Black) {
                    Column(
                        modifier = Modifier.width(280.dp).padding(12.dp).testTag(TAG),
                    ) {
                        AmoledReactionBubble(
                            text = "Maximum visible reactions",
                            time = "12:45",
                            outgoing = true,
                            customArgb = OUTGOING_CUSTOM_AMOLED_ARGB,
                            tallies =
                                listOf(
                                    ReactionTally("👍", 9_990, mine = true),
                                    ReactionTally("❤️", 4, mine = false),
                                    ReactionTally("😂", 3, mine = false),
                                    ReactionTally("🎉", 2, mine = false),
                                    ReactionTally("😮", 1, mine = false),
                                ),
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_reactions_amoled_large_font_narrow.png")
    }

    @Test
    fun reactionSummariesAmoledRtlAttachment() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = Color.Black) {
                        Column(
                            modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AmoledReactionBubble(
                                text = "رسالة واردة",
                                time = "12:46",
                                outgoing = false,
                                customArgb = CUSTOM_AMOLED_ARGB,
                                tallies = listOf(ReactionTally("👍", 2, mine = false)),
                            )
                            AmoledReactionBubble(
                                text = "رسالة صادرة",
                                time = "12:47",
                                outgoing = true,
                                tallies = listOf(ReactionTally("❤️", 3, mine = true)),
                            )
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_reactions_amoled_rtl.png")
    }

    @Test
    fun disappearingFootersLargeFontRtl() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1.6f) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = Color.Black) {
                        Column(
                            modifier = Modifier.width(320.dp).padding(16.dp).testTag(TAG),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DirectionalBubble(text = "رسالة واردة", time = "١٢:٣٤", mine = false)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                DirectionalBubble(text = "رسالة صادرة", time = "١٢:٣٥", mine = true)
                            }
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_retention_amoled_large_rtl.png")
    }

    /** Captures sent and received unavailable quotes in the same deterministic frame. */
    @Test
    fun unavailableReplyQuotesSentAndReceived() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReplyQuoteBubble(mine = false, originalUnavailable = true)
                        ReplyQuoteBubble(mine = true, originalUnavailable = true)
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_reply_unavailable_sent_received.png")
    }

    /** Captures typed reply attachments in a constrained dark layout. */
    @Test
    fun typedReplyAttachmentsNormalAndNarrow() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    Column(
                        modifier = Modifier.width(280.dp).padding(12.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReplyQuoteBubble(
                            mine = false,
                            fileName = "release.apk",
                            mediaType = "application/vnd.android.package-archive",
                        )
                        ReplyQuoteBubble(
                            mine = true,
                            fileName = "board.pcb",
                            mediaType = "application/vnd.acme.machine-part",
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_reply_attachments_narrow_dark.png")
    }

    /** Captures typed attachment labels under large-font RTL pressure. */
    @Test
    fun typedReplyAttachmentsLargeFontRtl() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, fontScale = 1.6f) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface {
                        Column(
                            modifier = Modifier.width(320.dp).padding(12.dp).testTag(TAG),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ReplyQuoteBubble(
                                mine = false,
                                fileName = "release.apk",
                                mediaType = "application/vnd.android.package-archive",
                            )
                            ReplyQuoteBubble(
                                mine = true,
                                fileName = "README.md",
                                mediaType = "text/markdown",
                            )
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_reply_attachments_large_rtl.png")
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).padding(8.dp).testTag(TAG)) {
                        FontSizePreviewBubble(text = "Incoming message bubble", mine = false)
                        FontSizePreviewBubble(text = "Outgoing message bubble", mine = true)
                        DirectionalBubble(text = "Incoming conversation bubble", time = "12:34", mine = false)
                        DirectionalBubble(text = "Outgoing conversation bubble", time = "12:35", mine = true)
                        MessageInlineFooter(
                            timeText = "12:34",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showStatus = true,
                            status = MessageStatus.Sent,
                            editedLabel = "edited",
                            onEditedClick = null,
                            retention = retentionInput("light-active", expiresAtEpochSeconds = 200uL),
                            retentionClockMillis = { 150_000L },
                        )
                        MessageInlineFooter(
                            timeText = "12:35",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showStatus = true,
                            status = MessageStatus.Failed,
                            editedLabel = null,
                            onEditedClick = null,
                            retention = retentionInput("light-waiting", expiresAtEpochSeconds = null),
                        )
                        ReplyPreviewCard(
                            senderTitle = "Alex",
                            isOwn = false,
                            body = "Original quoted message",
                            mediaKind = ReplyMediaKind.None,
                            onClick = null,
                            onDismiss = null,
                        )
                        ReplyPreviewCard(
                            senderTitle = "You",
                            isOwn = true,
                            body = "Photo reply quote",
                            mediaKind = ReplyMediaKind.Photo,
                            onClick = null,
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "bubble-chrome"
        const val NARROW_MEDIA_CAPTION_TAG = "narrow-media-caption-gallery"
        const val RECENT_TIME_TAG = "recent-time-gallery"
    }
}

/** Screenshot fixture for a portrait image whose substantial caption owns the shared width. */
@Composable
private fun NarrowCaptionBubble(
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    MediaCaptionFrame(
        presentation = messageBubblePresentation(deleted = false, mine = mine),
        highlighted = false,
        mine = mine,
        mentionedSelf = false,
        mentionedYouLabel = "Mentioned you",
        alignEnd = mine,
        modifier = modifier.widthIn(max = 290.dp),
        media = {
            Box(
                Modifier
                    .width(82.dp)
                    .height(138.dp)
                    .background(if (mine) Color(0xFF6A4C93) else Color(0xFF2A9D8F)),
            )
        },
    ) {
        Text("A substantial caption now uses the readable conversation width instead of a narrow image column.")
        Text(
            text = if (mine) "19:08" else "19:07",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/** Screenshots fixture for production reply-quote combinations. */
@Composable
private fun ReplyQuoteBubble(
    mine: Boolean,
    originalUnavailable: Boolean = false,
    fileName: String? = null,
    mediaType: String? = null,
) {
    val presentation = messageBubblePresentation(deleted = false, mine = mine, customArgb = null)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = mine,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReplyPreviewCard(
                    senderTitle = if (mine) "You" else "Alice",
                    isOwn = mine,
                    body = "Original message",
                    mediaKind = if (fileName == null) ReplyMediaKind.None else ReplyMediaKind.Document,
                    mediaFileName = fileName,
                    mediaType = mediaType,
                    originalUnavailable = originalUnavailable,
                    onClick = {},
                    onDismiss = null,
                )
                Text(if (mine) "Outgoing reply" else "Incoming reply")
                Text(
                    text = if (mine) "12:35" else "12:34",
                    style = MaterialTheme.typography.labelSmall,
                    color = messageBubbleTimestampColor(mine = mine, deleted = false),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun CustomAmoledMediaCaptionBubble(highlighted: Boolean) {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = true,
            customArgb = OUTGOING_CUSTOM_AMOLED_ARGB,
        )
    MediaCaptionFrame(
        presentation = presentation,
        highlighted = highlighted,
        mine = true,
        mentionedSelf = false,
        mentionedYouLabel = "Mentioned you",
        alignEnd = true,
        media = { Box(Modifier.width(180.dp).height(80.dp).background(Color(0xFF303030))) },
    ) {
        Text("Highlighted media caption")
        Text(
            text = "12:36",
            style = MaterialTheme.typography.labelSmall,
            color = messageBubbleTimestampColor(mine = true, deleted = false),
        )
    }
}

@Composable
private fun CustomAmoledReplyBubble(highlighted: Boolean) {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = false,
            customArgb = CUSTOM_AMOLED_ARGB,
        )
    MessageBubbleFrame(
        presentation = presentation,
        highlighted = highlighted,
        mine = false,
        mentionedSelf = false,
        mentionedYouLabel = "Mentioned you",
    ) {
        ReplyPreviewCard(
            senderTitle = "Alex",
            isOwn = false,
            body = if (highlighted) "Highlighted target" else "Current custom accent",
            mediaKind = ReplyMediaKind.None,
            onClick = null,
            onDismiss = null,
            containerColor = Color.Transparent,
            contentColor = colorFromArgb(presentation.contentArgb),
            accentColor =
                replyPreviewAccentArgb(
                    insideBubble = true,
                    customBubbleColorActive = true,
                    presentation = presentation,
                )?.let(::colorFromArgb),
        )
    }
}

/** Directional bubble. */
@Composable
private fun DirectionalBubble(
    text: String,
    time: String,
    mine: Boolean,
) {
    MessageBubbleFrame(
        presentation = messageBubblePresentation(deleted = false, mine = mine),
        highlighted = false,
        mine = mine,
        mentionedSelf = false,
        mentionedYouLabel = "Mentioned you",
    ) {
        Column {
            Text(text)
            MessageInlineFooter(
                timeText = time,
                color = messageBubbleTimestampColor(mine = mine, deleted = false),
                showStatus = mine,
                status = if (mine) MessageStatus.Sent else MessageStatus.Received,
                editedLabel = null,
                onEditedClick = null,
                retention = retentionInput(if (mine) "outgoing" else "incoming", expiresAtEpochSeconds = 200uL),
                retentionClockMillis = { 150_000L },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Amoled reaction bubble. */
@Composable
private fun AmoledReactionBubble(
    text: String,
    time: String,
    outgoing: Boolean,
    customArgb: Long? = null,
    tallies: List<ReactionTally>,
) {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = outgoing,
            customArgb = customArgb,
        )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = outgoing,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(text)
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = messageBubbleTimestampColor(mine = outgoing, deleted = false),
                )
            }
        }
        Box(modifier = Modifier.reactionSummaryAttachment(outgoing = outgoing)) {
            ReactionPillRow(
                tallies = tallies,
                enabled = true,
                onOpenDetails = {},
            )
        }
    }
}

/** Retention input. */
private fun retentionInput(
    messageIdHex: String,
    expiresAtEpochSeconds: ULong?,
): RetentionIndicatorInput =
    RetentionIndicatorInput(
        controllerKey = screenshotControllerKey,
        accountRef = "personal",
        groupIdHex = "group",
        messageIdHex = messageIdHex,
        sourceEpoch = 1uL,
        durationSeconds = 100uL,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

private const val CUSTOM_AMOLED_ARGB = 0xFFFFC107L
private const val OUTGOING_CUSTOM_AMOLED_ARGB = 0xFF9C27B0L
private val screenshotControllerKey = Any()

/** The Appearance font-size preview bubble, kept here for the message-chrome baselines it anchors. */
@Composable
private fun FontSizePreviewBubble(
    text: String,
    mine: Boolean,
) {
    val bubbleColor = messageBubbleFillColor(deleted = false, mine = mine)
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart),
            color = bubbleColor,
            shape = RoundedCornerShape(18.dp),
            border = messageBubbleBorder(highlighted = false, mine = mine),
            tonalElevation = if (mine) 1.dp else 0.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
