package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSizes

private val MessageBubbleOppositeGutter = 48.dp
internal val MessageBubbleSenderAvatarSlotWidth = WhiteNoiseSizes.AvatarCompact

internal fun messageBubbleColumnMaxWidth(
    containerWidth: Dp,
    selectionGutterWidth: Dp,
    senderAvatarSlotWidth: Dp,
): Dp =
    (containerWidth - maxOf(MessageBubbleOppositeGutter, selectionGutterWidth) - senderAvatarSlotWidth)
        .coerceAtLeast(0.dp)

internal fun messageBubbleColumnTestTag(messageIdHex: String): String = "message-bubble-column:$messageIdHex"

internal fun messageBubbleRowTestTag(messageIdHex: String): String = "message-bubble-row:$messageIdHex"

internal fun messageBubbleColumnMinWidth(
    hasGeneralFileCard: Boolean,
    maxWidth: Dp,
): Dp = if (hasGeneralFileCard) maxWidth.coerceAtLeast(0.dp) else Dp.Unspecified
