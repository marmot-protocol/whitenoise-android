package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleTimestampColor
import dev.ipf.whitenoise.android.ui.conversation.messages.replyPreviewAccentArgb
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionSummaryChip
import dev.ipf.whitenoise.android.ui.conversation.reactions.reactionSummaryAttachment
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageSelfMentionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selfMentionPlainAndMediaAcrossThemes() {
        composeRule.setContent {
            Column(modifier = Modifier.width(360.dp).testTag(ROOT_TAG)) {
                MentionThemePanel(label = "Light", darkTheme = false, amoled = false)
                MentionThemePanel(label = "Dark", darkTheme = true, amoled = false)
                MentionThemePanel(label = "AMOLED", darkTheme = true, amoled = true)
                MentionThemePanel(
                    label = "AMOLED · custom bubble",
                    darkTheme = true,
                    amoled = true,
                    customArgb = CUSTOM_AMOLED_ARGB,
                )
            }
        }

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/message_self_mention_theme_matrix.png")
    }

    @Test
    fun selfMentionAmoledLargeFontNarrowRtl() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1.6f) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = Color.Black) {
                        Column(
                            modifier = Modifier.width(300.dp).padding(12.dp).testTag(ROOT_TAG),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RichMentionBubble()
                            MentionMediaBubble(
                                customArgb = CUSTOM_AMOLED_ARGB,
                                modifier = Modifier.fillMaxWidth(),
                                mediaLabel = "صورة المجموعة",
                                caption = "ذكرك @You في وصف طويل يلتف إلى عدة أسطر",
                            )
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/message_self_mention_amoled_large_font_rtl.png")
    }

    private companion object {
        const val ROOT_TAG = "message-self-mention"
    }
}

@Composable
private fun MentionThemePanel(
    label: String,
    darkTheme: Boolean,
    amoled: Boolean,
    customArgb: Long? = null,
) {
    WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MentionPlainBubble(
                        customArgb = customArgb,
                        modifier = Modifier.width(166.dp),
                    )
                    MentionMediaBubble(
                        customArgb = customArgb,
                        modifier = Modifier.width(166.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionPlainBubble(
    customArgb: Long?,
    modifier: Modifier = Modifier,
) {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = false,
            customArgb = customArgb,
        )
    MessageBubbleFrame(
        presentation = presentation,
        highlighted = false,
        mine = false,
        mentionedSelf = true,
        mentionedYouLabel = "Mentioned you",
        modifier = modifier,
    ) {
        Text(text = "Alice", style = MaterialTheme.typography.labelMedium)
        Text("Hi @You, this wraps")
        MentionTimestamp(time = "12:34")
    }
}

@Composable
private fun MentionMediaBubble(
    customArgb: Long?,
    modifier: Modifier = Modifier,
    mediaLabel: String = "Group photo",
    caption: String = "Caption mentions @You",
) {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = false,
            customArgb = customArgb,
        )
    MediaCaptionFrame(
        presentation = presentation,
        highlighted = false,
        mine = false,
        mentionedSelf = true,
        mentionedYouLabel = "Mentioned you",
        alignEnd = false,
        modifier = modifier,
        media = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(Color(0xFF365A68)),
            ) {
                Text(
                    text = mediaLabel,
                    color = Color.White,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    ) {
        Text(caption)
        MentionTimestamp(time = "12:35")
    }
}

@Composable
private fun RichMentionBubble() {
    val presentation =
        messageBubblePresentation(
            deleted = false,
            mine = false,
            customArgb = CUSTOM_AMOLED_ARGB,
        )
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = false,
            mentionedSelf = true,
            mentionedYouLabel = "ذكرك",
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Alice", style = MaterialTheme.typography.labelMedium)
            ReplyPreviewCard(
                senderTitle = "Bob",
                isOwn = false,
                body = "الرسالة الأصلية التي تم الرد عليها",
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
            Text("هذه رسالة جماعية طويلة تذكر @You وتتحقق من التفاف النص")
            MentionTimestamp(time = "١٢:٣٤")
        }
        Box(modifier = Modifier.reactionSummaryAttachment(outgoing = false)) {
            ReactionSummaryChip(
                tallies = listOf(ReactionTally("👍", 12, mine = true), ReactionTally("🎉", 3, mine = false)),
                outgoing = false,
                customAmoledBorderColor = presentation.borderOverrideArgb?.let(::colorFromArgb),
                onClick = {},
            )
        }
    }
}

@Composable
private fun ColumnScope.MentionTimestamp(time: String) {
    MessageInlineFooter(
        timeText = time,
        color = messageBubbleTimestampColor(mine = false, deleted = false),
        showStatus = false,
        status = MessageStatus.Received,
        editedLabel = null,
        onEditedClick = null,
        retention = null,
        modifier = Modifier.align(Alignment.End),
    )
}

private const val CUSTOM_AMOLED_ARGB = 0xFFFFC107L
