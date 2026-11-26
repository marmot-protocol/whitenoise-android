@file:Suppress("MatchingDeclarationName") // The card, its surface variant and icon disc share one recipe.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.Radii

/** How an embed card sits on its bubble. */
internal enum class ConversationEmbedSurface {
    /** Tonal container with an outline; the default for every card. */
    Filled,

    /** Transparent over the bubble, outlined in the caller's content colour (Nostr event cards). */
    OnBubble,
}

/** Corner shape shared by every card-like embed in a message bubble. Media tiles keep [ConversationMediaTileShape]. */
internal val ConversationEmbedShape = RoundedCornerShape(Radii.md)

/** Inner padding shared by embed cards. */
internal val ConversationEmbedPadding = Dimens.spaceMd

/** Leading icon disc / avatar size on share cards. */
internal val ConversationEmbedLeadingSize = 44.dp

/** Trailing action icon size (copy, open) on embed cards. */
internal val ConversationEmbedActionIconSize = 20.dp

/**
 * The one card used by polls, Nostr events, locations, shared users and contacts inside a bubble.
 * Callers add width (`ConversationMessageMetrics.RichContentCanvasWidth` or fill) through [modifier]
 * and apply [ConversationEmbedPadding] to their own content.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun ConversationEmbedCard(
    modifier: Modifier = Modifier,
    surface: ConversationEmbedSurface = ConversationEmbedSurface.Filled,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val filled = surface == ConversationEmbedSurface.Filled
    Surface(
        modifier =
            modifier
                .clip(ConversationEmbedShape)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = ConversationEmbedShape,
        color = if (filled) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        contentColor = contentColor,
        border =
            BorderStroke(
                1.dp,
                if (filled) MaterialTheme.colorScheme.outlineVariant else contentColor.copy(alpha = 0.22f),
            ),
        content = content,
    )
}

/** Circular glyph disc that leads share cards (location, contact, unresolved user). */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun ConversationEmbedIconDisc(
    painter: Painter,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(ConversationEmbedLeadingSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(Dimens.spaceXl),
            )
        }
    }
}
