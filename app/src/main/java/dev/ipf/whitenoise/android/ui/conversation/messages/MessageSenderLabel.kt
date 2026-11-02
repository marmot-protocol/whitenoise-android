@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.common.Avatar

internal const val MESSAGE_SENDER_AVATAR_TAG = "message-sender-avatar"
private const val SENDER_AVATAR_INLINE_ID = "message-sender-identity-avatar"

/** An avatar inside the name's existing line box, with no extra vertical padding or row height. */
@Composable
@Suppress("LongParameterList")
internal fun MessageSenderLabel(
    title: String,
    seed: String,
    pictureUrl: String?,
    picture: ImageBitmap?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val style = MaterialTheme.typography.labelMedium
    val avatarSize = with(LocalDensity.current) { style.fontSize.toDp() }
    val name = remember(title) { EmojiShortcodes.annotate(AnnotatedString(title)) }
    val label =
        remember(name) {
            buildAnnotatedString {
                appendInlineContent(SENDER_AVATAR_INLINE_ID, " ")
                append("\u2002")
                append(name)
            }
        }
    // Keep the inline lambda current when a cached image or private source changes for this sender.
    val inlineContent =
        EmojiShortcodes.content(ReceivedEmoji.None) +
            (
                SENDER_AVATAR_INLINE_ID to
                    InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                        Box(Modifier.fillMaxSize().testTag(MESSAGE_SENDER_AVATAR_TAG).clearAndSetSemantics {}) {
                            Avatar(
                                title = title,
                                seed = seed,
                                size = avatarSize,
                                pictureUrl =
                                    pictureUrl.takeIf {
                                        picture == null || PrivateContactAvatarLoader.isPrivate(it)
                                    },
                                picture = picture,
                            )
                        }
                    }
            )
    Text(
        text = label,
        inlineContent = inlineContent,
        modifier = modifier,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style =
            style.copy(
                textDirection =
                    if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr,
            ),
    )
}
