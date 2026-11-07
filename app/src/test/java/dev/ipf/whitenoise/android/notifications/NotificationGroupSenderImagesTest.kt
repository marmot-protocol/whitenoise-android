package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #3635: group message rows draw each member's own picture. Rows carried from a card that an earlier build posted
 * with blank member pictures get the member's monogram, and the group's conversation shortcut keeps a blank sender.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationGroupSenderImagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** A group sender Person carries the member's picture, and a redacted card's Person carries none. */
    @Test
    fun groupSenderPersonCarriesTheMembersPictureAndRedactedCardsNone() {
        val picture = solid(Color.RED)
        assertEquals(Color.RED, pixel(notificationSenderPerson(groupContent(), picture)))
        val direct = groupContent().copy(isGroupConversation = false)
        assertEquals(Color.RED, pixel(notificationSenderPerson(direct, picture)))
        assertNull(notificationSenderPerson(groupContent(), picture, redacted = true).icon)
    }

    /**
     * Carried rows whose senders still have an earlier build's blank picture get those senders' monograms, while
     * their text, timestamps, attachment data, keys and names are kept, and the new message shows its own picture.
     */
    @Test
    fun carriedBlankRowsGetTheirSendersMonogramAndKeepTheirContent() {
        val alice = groupNotificationPerson(person("Alice", "alice", Color.RED))
        val bob = groupNotificationPerson(person("Bob", "bob", Color.GREEN))
        val carried =
            listOf(
                NotificationCompat.MessagingStyle.Message("older", 1L, bob),
                NotificationCompat.MessagingStyle.Message("attachment", 2L, alice).apply {
                    setData("image/png", Uri.parse("content://fixture/image"))
                },
                NotificationCompat.MessagingStyle.Message("mine", 3L, null as Person?),
            )
        val carol = person("Carol", "carol", Color.BLUE)

        val style =
            NotificationCardContent(context) { _, _ -> false to null }.messagingStyle(
                content = groupContent(),
                conversationTitleOverride = null,
                historyCap = HISTORY_CAP,
                carriedHistory = carried,
                sender = carol,
                newMessageTimestampMs = 4L,
            )

        val rows = style.messages
        assertEquals(listOf("older", "attachment", "mine", "hi"), rows.map { it.text.toString() })
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.timestamp })
        assertEquals(Uri.parse("content://fixture/image"), rows[1].dataUri)
        assertEquals(listOf("bob", "alice", null, "carol"), rows.map { it.person?.key })
        assertEquals("Bob", rows[0].person?.name.toString())
        assertEquals(opaqueMonogram("Bob", "bob"), pixel(rows[0].person!!))
        assertEquals(opaqueMonogram("Alice", "alice"), pixel(rows[1].person!!))
        assertNull("a self row stays a self row", rows[2].person)
        assertEquals(Color.BLUE, pixel(rows[3].person!!))
    }

    /** A real picture, and a Person without one, pass through the blank-picture check unchanged. */
    @Test
    fun realPicturesAndPicturelessPeopleAreReturnedUnchanged() {
        val pictured = person("Alice", "alice", Color.RED)
        assertSame(pictured, withoutBlankGroupSenderPicture(context, pictured))
        val pictureless =
            Person
                .Builder()
                .setName("Bob")
                .setKey("bob")
                .build()
        assertSame(pictureless, withoutBlankGroupSenderPicture(context, pictureless))
    }

    /** A group's conversation shortcut stands for the group, so its sender keeps a blank picture; a DM's is kept. */
    @Test
    fun groupShortcutSenderStaysBlankAndDirectSenderKeepsItsPicture() {
        val sender = person("Alice", "alice", Color.RED)
        assertEquals(0, Color.alpha(pixel(notificationShortcutSender(groupContent(), sender))))
        assertSame(sender, notificationShortcutSender(groupContent().copy(isGroupConversation = false), sender))
    }

    /** One group message's presentation content for the fixture conversation. */
    private fun groupContent() =
        LocalNotificationContent(
            notificationTag = "group",
            notificationId = 1,
            title = "Group",
            body = "hi",
            senderName = "Carol",
            senderKey = "carol",
            selfName = "Me",
            selfKey = "self",
            isGroupConversation = true,
            conversationTitle = "Group",
        )

    /** The top-left pixel of the monogram a sender gets, which must be opaque, never the blank picture. */
    private fun opaqueMonogram(
        name: String,
        key: String,
    ): Int =
        notificationMonogramBitmap(name, key).getPixel(0, 0).also {
            assertTrue("a monogram is opaque", Color.alpha(it) == OPAQUE)
        }

    /** A named, keyed Person whose picture is one solid color. */
    private fun person(
        name: String,
        key: String,
        color: Int,
    ): Person =
        Person
            .Builder()
            .setName(name)
            .setKey(key)
            .setIcon(IconCompat.createWithBitmap(solid(color)))
            .build()

    /** A small single-color bitmap whose pixels identify which picture reached the row. */
    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    /** The top-left pixel of a Person's picture. */
    private fun pixel(person: Person): Int = checkNotNull(person.icon?.loadDrawable(context)).toBitmap().getPixel(0, 0)

    private companion object {
        const val HISTORY_CAP = 10
        const val OPAQUE = 255
    }
}
