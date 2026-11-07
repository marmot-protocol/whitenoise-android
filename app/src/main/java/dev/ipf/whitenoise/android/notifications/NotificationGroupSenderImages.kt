package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/**
 * A non-null transparent icon stops Android from generating initials for a Person. A group's conversation shortcut
 * stands for the group, never a member, so its sender carries this blank picture. Group message rows show each
 * member's own picture (#3635); builds from #3515 drew this blank picture on every row instead, and
 * [withoutBlankGroupSenderPicture] replaces it when such a card's history is carried into a new post.
 */
private val transparentGroupSenderBitmap: Bitmap by lazy {
    Bitmap.createBitmap(intArrayOf(Color.TRANSPARENT), 1, 1, Bitmap.Config.ARGB_8888)
}

/** Removes picture pixels without changing the sender's name or routing/importance metadata. */
internal fun groupNotificationPerson(person: Person): Person = person.toBuilder().setIcon(groupSenderIcon()).build()

/** A fresh icon wrapper shares immutable pixels, so one Person cannot retint another Person's icon. */
internal fun groupSenderIcon(): IconCompat = IconCompat.createWithBitmap(transparentGroupSenderBitmap)

/** Private overrides are resolved before this final sender policy enters the public shortcut factory. */
internal fun notificationShortcutSender(
    content: LocalNotificationContent,
    sender: Person,
): Person =
    if (content.isGroupConversation) {
        groupNotificationPerson(sender)
    } else {
        sender
    }

/**
 * A carried row whose sender still has the blank picture an earlier build drew gets that sender's monogram, so it
 * shows initials instead of an empty circle until a late picture or their next message brings their own picture.
 * Any other Person is returned unchanged.
 */
internal fun withoutBlankGroupSenderPicture(
    context: Context,
    person: Person,
): Person =
    if (person.icon?.isBlankGroupSenderPicture(context) == true) {
        person
            .toBuilder()
            .setIcon(
                IconCompat.createWithBitmap(
                    notificationMonogramBitmap(person.name?.toString().orEmpty(), person.key.orEmpty()),
                ),
            ).build()
    } else {
        person
    }

/** True only for the 1×1 transparent picture; the app draws no other picture at that size. */
private fun IconCompat.isBlankGroupSenderPicture(context: Context): Boolean =
    type == IconCompat.TYPE_BITMAP &&
        runCatching { (toIcon(context).loadDrawable(context) as? BitmapDrawable)?.bitmap }
            .getOrNull()
            ?.let { it.width == 1 && it.height == 1 && Color.alpha(it.getPixel(0, 0)) == 0 } == true
