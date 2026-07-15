package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.readableTextArgb

internal fun colorFromArgb(argb: Long): Color = Color(argb)

internal data class BubblePresentation(
    val backgroundArgb: Long,
    val contentArgb: Long,
)

internal data class BubblePresentationTokens(
    val errorBackgroundArgb: Long,
    val errorContentArgb: Long,
    val surfaceBackgroundArgb: Long,
    val surfaceContentArgb: Long,
    val mineBackgroundArgb: Long,
    val mineContentArgb: Long,
)

/** Keeps invalidation/tombstone semantics fixed while allowing an explicit
 * user color to opt ordinary bubbles out of the AMOLED black default. */
internal fun resolveBubblePresentationArgb(
    invalidated: Boolean,
    deleted: Boolean,
    amoled: Boolean,
    mine: Boolean,
    customArgb: Long?,
    tokens: BubblePresentationTokens,
): BubblePresentation =
    when {
        invalidated -> BubblePresentation(tokens.errorBackgroundArgb, tokens.errorContentArgb)
        deleted && amoled -> BubblePresentation(OPAQUE_BLACK_ARGB, tokens.surfaceContentArgb)
        deleted -> BubblePresentation(tokens.surfaceBackgroundArgb, tokens.surfaceContentArgb)
        customArgb != null ->
            BubblePresentation(
                backgroundArgb = customArgb,
                contentArgb = readableTextArgb(customArgb) ?: tokens.surfaceContentArgb,
            )
        amoled -> BubblePresentation(OPAQUE_BLACK_ARGB, tokens.surfaceContentArgb)
        mine -> BubblePresentation(tokens.mineBackgroundArgb, tokens.mineContentArgb)
        else -> BubblePresentation(tokens.surfaceBackgroundArgb, tokens.surfaceContentArgb)
    }
