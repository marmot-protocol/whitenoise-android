package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.core.TimelineInvalidationPresentation
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.isBlueFreeAccentVisible
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.state.withoutBlueChannel

internal fun colorFromArgb(argb: Long): Color = Color(argb)

/**
 * Whether the outgoing delivery glyph renders. Any row whose invalidation
 * reason already states its delivery on the row suppresses it: the glyph is
 * derived from send status, which for an unpublished row reads "Sending"
 * forever and would contradict a "Delivery not confirmed" warning sitting
 * beside it.
 */
internal fun shouldShowMessageStatus(
    mine: Boolean,
    deleted: Boolean,
    presentation: TimelineInvalidationPresentation,
): Boolean =
    mine &&
        !deleted &&
        when (presentation) {
            TimelineInvalidationPresentation.PersistedFailure,
            TimelineInvalidationPresentation.UnconfirmedDelivery,
            -> false
            TimelineInvalidationPresentation.None,
            TimelineInvalidationPresentation.PartialVisibility,
            TimelineInvalidationPresentation.NonCanonicalHistory,
            -> true
        }

/** Exclusive destinations for a message-level invalidation warning. */
internal data class InvalidationWarningPlacement(
    val fileFooter: String?,
    val outerBubble: String?,
)

/** Assigns an invalidation warning to exactly one layout owner. */
internal fun placeInvalidationWarning(
    warning: String?,
    fileCardOwnsFooter: Boolean,
): InvalidationWarningPlacement =
    InvalidationWarningPlacement(
        fileFooter = warning.takeIf { fileCardOwnsFooter },
        outerBubble = warning.takeUnless { fileCardOwnsFooter },
    )

internal data class BubblePresentation(
    val backgroundArgb: Long,
    val contentArgb: Long,
    val mentionAccentArgb: Long,
    val borderOverrideArgb: Long? = null,
    val suppressBorder: Boolean = false,
)

internal data class BubblePresentationTokens(
    val errorBackgroundArgb: Long,
    val errorContentArgb: Long,
    val surfaceBackgroundArgb: Long,
    val surfaceContentArgb: Long,
    val mineBackgroundArgb: Long,
    val mineContentArgb: Long,
    val mentionAccentArgb: Long,
)

/**
 * Selects the text owned by a message bubble after media and structured-share
 * presentation have been resolved. Tombstones keep their explanatory copy;
 * only an actually recognized structured share may consume the raw body.
 */
internal fun messageBodyTextToRender(
    displayedBody: String,
    deleted: Boolean,
    persistedFailure: Boolean,
    structuredShareOwnsBody: Boolean,
    hasPendingMediaName: Boolean,
    hasConfirmedMedia: Boolean,
    mediaCaption: String?,
): String? =
    when {
        deleted || persistedFailure -> displayedBody
        structuredShareOwnsBody -> null
        hasPendingMediaName && !hasConfirmedMedia -> mediaCaption
        hasConfirmedMedia -> mediaCaption
        else -> displayedBody
    }

/** Keeps failure/tombstone semantics fixed while routing ordinary AMOLED
 * customization through the bubble border instead of its black fill. */
internal fun resolveBubblePresentationArgb(
    deleted: Boolean,
    amoled: Boolean,
    mine: Boolean,
    customArgb: Long?,
    tokens: BubblePresentationTokens,
    persistedFailure: Boolean = false,
): BubblePresentation =
    when {
        persistedFailure ->
            BubblePresentation(
                backgroundArgb = tokens.errorBackgroundArgb,
                contentArgb = tokens.errorContentArgb,
                mentionAccentArgb = tokens.mentionAccentArgb,
                suppressBorder = true,
            )
        deleted && amoled -> BubblePresentation(OPAQUE_BLACK_ARGB, tokens.surfaceContentArgb, tokens.mentionAccentArgb)
        deleted -> BubblePresentation(tokens.surfaceBackgroundArgb, tokens.surfaceContentArgb, tokens.mentionAccentArgb)
        amoled ->
            BubblePresentation(
                backgroundArgb = OPAQUE_BLACK_ARGB,
                contentArgb = tokens.surfaceContentArgb,
                mentionAccentArgb = tokens.mentionAccentArgb,
                borderOverrideArgb =
                    customArgb
                        ?.withoutBlueChannel()
                        ?.takeIf(Long::isBlueFreeAccentVisible),
            )
        customArgb != null ->
            BubblePresentation(
                backgroundArgb = customArgb,
                contentArgb = readableTextArgb(customArgb) ?: tokens.surfaceContentArgb,
                mentionAccentArgb = tokens.mentionAccentArgb,
            )
        mine -> BubblePresentation(tokens.mineBackgroundArgb, tokens.mineContentArgb, tokens.mentionAccentArgb)
        else -> BubblePresentation(tokens.surfaceBackgroundArgb, tokens.surfaceContentArgb, tokens.mentionAccentArgb)
    }
