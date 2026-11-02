package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val MessageBubbleOppositeGutter = 48.dp
private val MessageBubbleGroupRichContentAllowance = 40.dp

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

/** Text recovers both gutters in incoming groups; selection keeps its own leading lane. */
internal fun messageBubbleTextColumnMaxWidth(
    containerWidth: Dp,
    selectionGutterWidth: Dp,
    incomingGroup: Boolean,
): Dp =
    if (incomingGroup) {
        (containerWidth - selectionGutterWidth).coerceAtLeast(0.dp)
    } else {
        messageBubbleColumnMaxWidth(containerWidth, selectionGutterWidth, 0.dp)
    }

/** Rich cards retain their established canvas even though the external identity lane is gone. */
internal fun messageBubbleRichColumnMaxWidth(
    containerWidth: Dp,
    selectionGutterWidth: Dp,
    incomingGroup: Boolean,
): Dp =
    messageBubbleColumnMaxWidth(
        containerWidth,
        selectionGutterWidth,
        if (incomingGroup) MessageBubbleGroupRichContentAllowance else 0.dp,
    )
