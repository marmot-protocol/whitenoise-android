package dev.ipf.whitenoise.android.notifications

import android.content.Context
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
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiShortcodes

/** Android decorates the card; the body renders inline artwork while retaining complete accessible text. */
internal fun applyReactionNotificationArtwork(
    context: Context,
    builder: NotificationCompat.Builder,
    content: LocalNotificationContent,
    shortcode: String?,
    artwork: Bitmap?,
): Boolean {
    builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
    if (!content.isReactionPreview) return false
    var images = emptyMap<String, Bitmap>()
    return try {
        images = reactionBodyArtwork(context, content.body, shortcode, artwork)
        if (images.isEmpty()) return false
        val compact = reactionInlineView(context, content, images, expanded = false)
        val expanded = reactionInlineView(context, content, images, expanded = true)
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
        true
    } catch (
        @Suppress("TooGenericExceptionCaught")
        _: RuntimeException,
    ) {
        clearReactionArtwork(builder, content.body)
        false
    } catch (_: OutOfMemoryError) {
        clearReactionArtwork(builder, content.body)
        false
    } finally {
        images.values.filter { it !== artwork }.forEach { it.recycle() }
    }
}

/** Failed optional drawing leaves a plain text card with no partially configured custom views. */
private fun clearReactionArtwork(
    builder: NotificationCompat.Builder,
    body: String,
) {
    builder
        .setCustomContentView(null)
        .setCustomBigContentView(null)
        .setCustomHeadsUpContentView(null)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
}

/** Only APK assets can identify quoted custom emoji without borrowing unrelated device-local files. */
internal fun reactionBodyArtwork(
    context: Context,
    body: String,
    shortcode: String?,
    artwork: Bitmap?,
): Map<String, Bitmap> =
    EmojiShortcodes
        .presentationShortcodes(body)
        .take(REACTION_BODY_ARTWORK_LIMIT)
        .mapNotNull { code ->
            val image = if (code == shortcode) artwork else notificationBuiltinEmojiBitmap(context, code)
            image?.let { code to it }
        }.toMap()

/** A standard ImageView carries locally drawn spans; unsupported bitmap spans never cross RemoteViews. */
private fun reactionInlineView(
    context: Context,
    content: LocalNotificationContent,
    artwork: Map<String, Bitmap>,
    expanded: Boolean,
): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_reaction_inline).apply {
        setTextViewText(R.id.reaction_notification_title, content.title)
        setImageViewBitmap(
            R.id.reaction_notification_body,
            reactionBodyBitmap(context, content.body, artwork, expanded),
        )
        setContentDescription(R.id.reaction_notification, "${content.title}, ${content.body}")
    }

/** Draw the same shortcode annotations as the message UI, with bounded wrapping and system text colors. */
private fun reactionBodyBitmap(
    context: Context,
    body: String,
    artwork: Map<String, Bitmap>,
    expanded: Boolean,
): Bitmap {
    val metrics = context.resources.displayMetrics
    val appearance =
        context.obtainStyledAttributes(
            androidx.core.R.style.TextAppearance_Compat_Notification,
            intArrayOf(android.R.attr.textColor, android.R.attr.textSize),
        )
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    try {
        paint.color = appearance.getColor(0, Color.GRAY)
        paint.textSize = appearance.getDimension(1, REACTION_TEXT_SP * metrics.scaledDensity)
    } finally {
        appearance.recycle()
    }
    val text = SpannableString(body)
    val size = (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt().coerceAtLeast(1)
    EmojiShortcodes.annotate(AnnotatedString(body)).getStringAnnotations(0, body.length).forEach { token ->
        artwork[token.item]?.let { bitmap ->
            val span = ImageSpan(context, bitmap, ImageSpan.ALIGN_CENTER)
            span.drawable.setBounds(0, 0, size, size)
            text.setSpan(span, token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    // Leave room for the system icon/margins; narrow OEM templates can scale down without clipping.
    val widthDp =
        (context.resources.configuration.screenWidthDp - REACTION_BODY_INSET_DP)
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

private const val REACTION_EXPANDED_PREVIEW_LINES = 3
private const val REACTION_BODY_ARTWORK_LIMIT = 4
private const val REACTION_TEXT_SP = 14f
private const val REACTION_BODY_INSET_DP = 96
private const val REACTION_BODY_MIN_WIDTH_DP = 96
private const val REACTION_BODY_MAX_WIDTH_DP = 280
