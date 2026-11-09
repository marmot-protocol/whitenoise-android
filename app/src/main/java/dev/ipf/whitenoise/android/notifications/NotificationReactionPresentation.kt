package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ImageSpan
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlin.math.roundToInt

/**
 * Places the reactor at the leading edge of the reaction content. Plain text uses a TextView; only text
 * containing bundled artwork needs a bitmap. Android still supplies the notification header and controls.
 */
internal fun applyReactionNotificationPresentation(
    context: Context,
    builder: NotificationCompat.Builder,
    content: LocalNotificationContent,
    reactor: NotificationIdentityImage,
): Boolean {
    val text = ReactionNotificationText(content.title, content.body, content.reactionArtworkBody)
    return builder.renderReactionNotification(context, text, reactor)
}

/** The text already carried by the notification and its optional authored shortcodes for inline artwork. */
private data class ReactionNotificationText(
    val title: String,
    val body: String,
    val artworkBody: String?,
)

/** Builds each view from text and the current reactor picture without carrying any previous picture actions. */
private fun NotificationCompat.Builder.renderReactionNotification(
    context: Context,
    text: ReactionNotificationText,
    reactor: NotificationIdentityImage,
): Boolean {
    var images = emptyMap<String, Bitmap>()
    return try {
        images = reactionBodyArtwork(context, text.artworkBody ?: text.body)
        val compact = reactionInlineView(context, text, images, reactor.bitmap, expanded = false)
        val expanded = reactionInlineView(context, text, images, reactor.bitmap, expanded = true)
        setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
            .recordLeadingReactionIdentity(reactor, text.artworkBody)
        true
    } catch (
        @Suppress("TooGenericExceptionCaught")
        _: RuntimeException,
    ) {
        clearReactionNotificationPresentation(text.body)
        false
    } catch (_: OutOfMemoryError) {
        clearReactionNotificationPresentation(text.body)
        false
    } finally {
        images.values.forEach { it.recycle() }
    }
}

/** Restores a readable text-only card if building or posting the custom presentation fails. */
internal fun NotificationCompat.Builder.clearReactionNotificationPresentation(body: String) {
    extras.remove(EXTRA_IDENTITY_IMAGE)
    this
        .setCustomContentView(null)
        .setCustomBigContentView(null)
        .setCustomHeadsUpContentView(null)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
}

/** Only APK assets can identify quoted custom emoji without borrowing unrelated device-local files. */
internal fun reactionBodyArtwork(
    context: Context,
    body: String,
): Map<String, Bitmap> =
    EmojiShortcodes
        .presentationShortcodes(body)
        .take(REACTION_BODY_ARTWORK_LIMIT)
        .mapNotNull { code ->
            val image = notificationBuiltinEmojiBitmap(context, code)
            image?.let { code to it }
        }.toMap()

/** Resource-selected full-color images follow SystemUI's night mode when the same card re-inflates. */
private fun reactionInlineView(
    context: Context,
    text: ReactionNotificationText,
    artwork: Map<String, Bitmap>,
    avatar: Bitmap,
    expanded: Boolean,
): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_reaction_inline).apply {
        setImageViewBitmap(R.id.reaction_notification_avatar, avatar)
        setTextViewText(R.id.reaction_notification_title, text.title)
        setInt(R.id.reaction_notification_title, "setMaxLines", if (expanded) 2 else 1)
        if (artwork.isEmpty()) {
            setViewVisibility(R.id.reaction_notification_artwork, View.GONE)
            setTextViewText(R.id.reaction_notification_body, text.body)
            setInt(R.id.reaction_notification_body, "setMaxLines", if (expanded) REACTION_EXPANDED_PREVIEW_LINES else 1)
        } else {
            setViewVisibility(R.id.reaction_notification_body, View.GONE)
            setImageViewBitmap(
                R.id.reaction_notification_body_light,
                reactionBodyBitmap(context, text.artworkBody ?: text.body, artwork, expanded, dark = false),
            )
            setImageViewBitmap(
                R.id.reaction_notification_body_dark,
                reactionBodyBitmap(context, text.artworkBody ?: text.body, artwork, expanded, dark = true),
            )
        }
        setContentDescription(R.id.reaction_notification, "${text.title}, ${text.body}")
    }

/** Builds fresh views on Save, Clear and late refresh so prior private pixels are absent even from the parcel. */
internal fun NotificationCompat.Builder.withUpdatedReactionAvatar(
    context: Context,
    source: Notification,
    reactor: NotificationIdentityImage,
    artworkBody: String?,
): NotificationCompat.Builder {
    val extras = source.extras
    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
    val body = extras?.getCharSequence(Notification.EXTRA_TEXT)
    val text =
        ReactionNotificationText(
            title?.toString().orEmpty(),
            body?.toString().orEmpty(),
            artworkBody,
        )
    renderReactionNotification(context, text, reactor)
    return this
}

/** Draw the same shortcode annotations as the message UI, with bounded wrapping and system text colors. */
private fun reactionBodyBitmap(
    context: Context,
    body: String,
    artwork: Map<String, Bitmap>,
    expanded: Boolean,
    dark: Boolean,
): Bitmap {
    val metrics = context.resources.displayMetrics
    val paint = reactionTextPaint(context, dark)
    val text = SpannableString(body)
    val size = (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt().coerceAtLeast(1)
    EmojiShortcodes.annotate(AnnotatedString(body)).getStringAnnotations(0, body.length).forEach { token ->
        artwork[token.item]?.let { bitmap ->
            val span = ImageSpan(context, bitmap, ImageSpan.ALIGN_CENTER)
            span.drawable.setBounds(0, 0, size, size)
            text.setSpan(span, token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    // Leave room for the system icon, margins and leading picture.
    val widthDp =
        (context.resources.configuration.screenWidthDp - REACTION_BODY_INSET_DP - REACTION_AVATAR_INSET_DP)
            .coerceIn(REACTION_BODY_MIN_WIDTH_DP, REACTION_BODY_MAX_WIDTH_DP)
    val width = (widthDp * metrics.density).toInt().coerceAtLeast(1)
    val direction =
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            TextDirectionHeuristics.FIRSTSTRONG_RTL
        } else {
            TextDirectionHeuristics.FIRSTSTRONG_LTR
        }
    val layout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setTextDirection(direction)
            .setMaxLines(if (expanded) REACTION_EXPANDED_PREVIEW_LINES else 1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    return Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.density = metrics.densityDpi
        layout.draw(Canvas(bitmap))
    }
}

/** Resolve each palette independently at posting, so reinflation can select either without redrawing. */
private fun reactionTextPaint(
    context: Context,
    dark: Boolean,
): TextPaint {
    val themeContext =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            },
        )
    val appearance =
        themeContext.obtainStyledAttributes(
            androidx.core.R.style.TextAppearance_Compat_Notification,
            intArrayOf(android.R.attr.textColor, android.R.attr.textSize),
        )
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    try {
        paint.color = appearance.getColor(0, Color.GRAY)
        paint.textSize = appearance.getDimension(1, REACTION_TEXT_SP * context.resources.displayMetrics.scaledDensity)
    } finally {
        appearance.recycle()
    }
    return paint
}

private const val REACTION_EXPANDED_PREVIEW_LINES = 3
private const val REACTION_BODY_ARTWORK_LIMIT = 4
private const val REACTION_TEXT_SP = 14f
private const val REACTION_BODY_INSET_DP = 96

/** The 36 dp picture and 8 dp gap beside the content column. */
private const val REACTION_AVATAR_INSET_DP = 44
private const val REACTION_BODY_MIN_WIDTH_DP = 96
private const val REACTION_BODY_MAX_WIDTH_DP = 280

/** Shared static APK artwork rendering; no device-local files or received attachment guesses. */
internal fun notificationBuiltinEmojiBitmap(
    context: Context,
    code: String,
): Bitmap? {
    val resource = EmojiShortcodes.builtinDrawable(code) ?: return null
    val region = EmojiShortcodes.builtinArtworkRegion(resource)
    val tile = NOTIFICATION_EMOJI_TILE_PX
    return AppCompatResources.getDrawable(context, resource)?.let { drawable ->
        Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888).also { bitmap ->
            if (region == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            } else {
                // Scale the padded canvas so its artwork region fills the tile, as the in-app glyph crops it.
                val scale = bitmap.width.toFloat() / maxOf(region.width, region.height)
                val left = (-region.left * scale).roundToInt()
                val top = (-region.top * scale).roundToInt()
                drawable.setBounds(
                    left,
                    top,
                    left + (drawable.intrinsicWidth * scale).roundToInt(),
                    top + (drawable.intrinsicHeight * scale).roundToInt(),
                )
            }
            drawable.draw(Canvas(bitmap))
        }
    }
}
