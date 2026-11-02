package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale

/**
 * Normal bitmaps avoid IconCompat's adaptive-icon safe-zone crop, which can cut
 * into already-tight headshots. Android's conversation surfaces still apply
 * their own circular presentation. Missing avatars get a stable per-conversation
 * monogram instead of every shortcut sharing the launcher icon.
 */
internal fun notificationConversationIcon(
    title: String,
    seed: String,
    avatarBitmap: Bitmap?,
): IconCompat = IconCompat.createWithBitmap(avatarBitmap ?: notificationMonogramBitmap(title, seed))

internal fun notificationMonogramBitmap(
    title: String,
    seed: String,
    sizePx: Int = NOTIFICATION_MONOGRAM_SIZE_PX,
): Bitmap {
    require(sizePx > 0) { "sizePx must be positive" }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(notificationMonogramBackgroundColor(seed))
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    val baseline = sizePx / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(notificationAvatarInitials(title), sizePx / 2f, baseline, paint)
    return bitmap
}

internal fun notificationMonogramBackgroundColor(seed: String): Int {
    val hue = Math.floorMod(seed.hashCode(), 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.45f))
}

internal fun notificationAvatarInitials(title: String): String {
    val words = title.trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotBlank)
    val initials =
        buildString {
            words.take(if (words.size > 1) 2 else 1).forEach { word ->
                word.firstMonogramCodePoint()?.let(::appendCodePoint)
            }
        }.uppercase(Locale.ROOT)
    return initials.ifBlank { "?" }
}

private fun String.firstMonogramCodePoint(): Int? {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        offset += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        if (!Character.isWhitespace(codePoint) &&
            !Character.isISOControl(codePoint) &&
            type != Character.FORMAT.toInt()
        ) {
            return codePoint
        }
    }
    return null
}

internal fun notificationSenderPerson(
    content: LocalNotificationContent,
    avatarBitmap: Bitmap?,
    redacted: Boolean = false,
): Person =
    Person
        .Builder()
        .setName(content.senderName)
        .setKey(content.senderKey)
        .apply {
            if (!redacted) {
                val icon =
                    if (content.isGroupConversation && content.senderKey.isNotBlank()) {
                        groupSenderIcon()
                    } else {
                        avatarBitmap?.let(IconCompat::createWithBitmap)
                    }
                icon?.let(::setIcon)
            }
        }.build()

private const val NOTIFICATION_MONOGRAM_SIZE_PX = 128
