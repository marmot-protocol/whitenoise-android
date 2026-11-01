package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sender pixels are presentation-only; native group identity, history and action payloads stay intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationGroupSenderImagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Current/historic senders and self lose pixels, while every identity/content field survives. */
    @Test
    fun groupKeepsNamesAndRichHistoryWithoutSenderPicturesOrGeneratedInitials() {
        val before = richCard()
        val after = withoutGroupSenderPictures(context, before)
        val original = style(before)
        val rewritten = style(after)
        assertTrue(rewritten.isGroupConversation)
        assertEquals(original.conversationTitle, rewritten.conversationTitle)
        assertPerson(original.user, rewritten.user)
        val originalMessages = original.historicMessages + original.messages
        val rewrittenMessages = rewritten.historicMessages + rewritten.messages
        assertEquals(originalMessages.size, rewrittenMessages.size)
        originalMessages.zip(rewrittenMessages).forEach { (old, current) ->
            assertEquals(old.text.toString(), current.text.toString())
            assertEquals(old.timestamp, current.timestamp)
            assertEquals(old.dataMimeType, current.dataMimeType)
            assertEquals(old.dataUri, current.dataUri)
            assertEquals(old.extras.getString("entry"), current.extras.getString("entry"))
            if (old.person == null) assertNull(current.person) else assertPerson(old.person!!, current.person!!)
        }
        assertEquals(Color.BLUE, pixel(before.getLargeIcon().loadDrawable(context)!!.toBitmap()))
        assertEquals(Color.BLUE, pixel(after.getLargeIcon().loadDrawable(context)!!.toBitmap()))
        assertEquals(
            Color.RED,
            pixel(
                original.messages
                    .first()
                    .person!!
                    .icon!!
                    .loadDrawable(context)!!
                    .toBitmap(),
            ),
        )
        assertEquals(
            0,
            Color.alpha(
                pixel(
                    rewritten.user.icon!!
                        .loadDrawable(context)!!
                        .toBitmap(),
                ),
            ),
        )
    }

    /** The original native action objects preserve Unicode/custom choices and exact PendingIntent targets. */
    @Test
    fun groupRetainsNativeActionsAndAlertConversationMetadata() {
        val before = richCard()
        val after = withoutGroupSenderPictures(context, before)
        assertEquals(before.flags, after.flags)
        assertEquals(before.groupAlertBehavior, after.groupAlertBehavior)
        assertEquals(before.group, after.group)
        assertEquals(before.channelId, after.channelId)
        assertEquals(before.category, after.category)
        assertEquals(before.shortcutId, after.shortcutId)
        assertEquals(before.locusId, after.locusId)
        assertEquals(
            before.extras.getString(Notification.EXTRA_TEMPLATE),
            after.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(before.actions.size, after.actions.size)
        before.actions.zip(after.actions).forEach { (old, current) -> assertSame(old, current) }
        assertEquals(
            CHOICES.toList(),
            after.actions
                .first()
                .remoteInputs
                .single()
                .choices
                .toList(),
        )
        assertEquals(
            "reply",
            after.actions
                .first()
                .remoteInputs
                .single()
                .resultKey,
        )
        assertTrue(
            after.actions
                .first()
                .remoteInputs
                .single()
                .allowFreeFormInput,
        )
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, after.actions.first().semanticAction)
        assertNull(after.contentView)
        assertNull(after.bigContentView)
        assertNull(after.headsUpContentView)
    }

    /** Native rich people are normalized without dropping legacy URI people or remote-input history. */
    @Test
    @Suppress("DEPRECATION")
    fun groupNormalizesPeopleAndKeepsRemoteInputHistory() {
        val before =
            richCard().apply {
                extras.putBoolean("android.remoteInputSpinner", true)
                extras.putBoolean("android.hideSmartReplies", true)
            }
        val after = withoutGroupSenderPictures(context, before)
        assertTrue(after.extras.getBoolean("android.remoteInputSpinner"))
        assertTrue(after.extras.getBoolean("android.hideSmartReplies"))
        if (Build.VERSION.SDK_INT == 30) {
            assertFalse(after.extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS))
        } else {
            assertEquals(
                before.extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS),
                after.extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS),
            )
        }
        val people = after.extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)!!
        val alice = people.single { it.key == "alice" }
        assertEquals("nostr:alice", alice.uri)
        people.forEach { assertEquals(0, Color.alpha(pixel(it.icon!!.loadDrawable(context)!!.toBitmap()))) }
        assertTrue(people.any { it.uri == "nostr:legacy" })
        assertEquals(
            before.extras.getStringArray(Notification.EXTRA_PEOPLE)?.toList(),
            after.extras.getStringArray(Notification.EXTRA_PEOPLE)?.toList(),
        )
        assertEquals(
            before.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.map(CharSequence::toString),
            after.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.map(CharSequence::toString),
        )
    }

    /** DM pixels and generic/redacted/reaction-event cards are returned byte-for-byte by the presentation fence. */
    @Test
    fun directAndRedactedAndPlainCardsStayUntouched() {
        val direct = richCard(group = false)
        assertSame(direct, withoutGroupSenderPictures(context, direct))
        val redacted = richCard().apply { extras.putBoolean(EXTRA_CONTENT_REDACTED, true) }
        assertSame(redacted, withoutGroupSenderPictures(context, redacted))
        val plain = NotificationCompat.Builder(context, "reactions").setSmallIcon(R.drawable.ic_stat_whitenoise).build()
        assertSame(plain, withoutGroupSenderPictures(context, plain))
        assertEquals(
            Color.RED,
            pixel(
                style(direct)
                    .messages
                    .first()
                    .person!!
                    .icon!!
                    .loadDrawable(context)!!
                    .toBitmap(),
            ),
        )
    }

    /** Explicit hidden/redacted state cannot acquire the group sentinel even with rich identity inputs. */
    @Test
    fun initialPersonKeepsHiddenPayloadsIconlessAndDirectPicturesUnchanged() {
        val content =
            LocalNotificationContent(
                notificationTag = "group",
                notificationId = 1,
                title = "Group",
                body = "message",
                senderName = "Alice",
                senderKey = "alice",
                selfName = "Me",
                selfKey = "self",
                isGroupConversation = true,
                conversationTitle = "Group",
            )
        val picture = solid(Color.RED)
        assertNull(notificationSenderPerson(content, picture, redacted = true).icon)
        val group = notificationSenderPerson(content, picture)
        assertEquals(0, Color.alpha(pixel(group.icon!!.loadDrawable(context)!!.toBitmap())))
        val direct = notificationSenderPerson(content.copy(isGroupConversation = false), picture)
        assertEquals(Color.RED, pixel(direct.icon!!.loadDrawable(context)!!.toBitmap()))
    }

    /** One multi-sender group fixture includes an image attachment, self row and older history. */
    @Suppress("DEPRECATION") // Retain and check legacy URI people alongside rich Person metadata.
    private fun richCard(group: Boolean = true): Notification {
        val alice = person("Alice", "alice", Color.RED)
        val reply = PendingIntent.getBroadcast(context, 1, Intent("fixture.reply"), PendingIntent.FLAG_IMMUTABLE)
        val input =
            RemoteInput
                .Builder("reply")
                .setChoices(CHOICES)
                .setAllowFreeFormInput(true)
                .build()
        val action =
            NotificationCompat.Action
                .Builder(0, "Reply", reply)
                .addRemoteInput(input)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()
        return NotificationCompat
            .Builder(context, "messages")
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setStyle(richStyle(group))
            .setLargeIcon(solid(Color.BLUE))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup("messages")
            .setGroupAlertBehavior(1)
            .setShortcutId("group-shortcut")
            .setLocusId(androidx.core.content.LocusIdCompat("group-shortcut"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setRemoteInputHistory(arrayOf("sent"))
            .addPerson(alice)
            .addPerson("nostr:legacy")
            .addAction(action)
            .addAction(NotificationCompat.Action.Builder(0, "Mark read", reply).build())
            .build()
            .withoutApi30RemoteInputHistoryItems()
    }

    /** Retained structured history includes two senders, one attachment and a self row. */
    private fun richStyle(group: Boolean): NotificationCompat.MessagingStyle {
        val alice = person("Alice", "alice", Color.RED)
        val bob = person("Bob", "bob", Color.GREEN)
        val style = NotificationCompat.MessagingStyle(person("Me", "self", Color.YELLOW))
        style.isGroupConversation = group
        if (group) style.conversationTitle = "Design group"
        style.addHistoricMessage(NotificationCompat.MessagingStyle.Message("older", 1L, bob))
        style.addMessage(
            NotificationCompat.MessagingStyle.Message("attachment", 2L, alice).apply {
                setData("image/png", Uri.parse("content://fixture/image"))
                extras.putString("entry", "message-a")
            },
        )
        style.addMessage(NotificationCompat.MessagingStyle.Message("newer", 3L, bob))
        style.addMessage(NotificationCompat.MessagingStyle.Message("mine", 4L, null as Person?))
        return style
    }

    private fun style(card: Notification): NotificationCompat.MessagingStyle =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))

    private fun person(
        name: String,
        key: String,
        color: Int,
    ): Person =
        Person
            .Builder()
            .setName(name)
            .setKey(key)
            .setUri("nostr:$key")
            .setBot(true)
            .setImportant(true)
            .setIcon(IconCompat.createWithBitmap(solid(color)))
            .build()

    private fun assertPerson(
        before: Person,
        after: Person,
    ) {
        assertEquals(before.name.toString(), after.name.toString())
        assertEquals(before.key, after.key)
        assertEquals(before.uri, after.uri)
        assertEquals(before.isBot, after.isBot)
        assertEquals(before.isImportant, after.isImportant)
        assertEquals(0, Color.alpha(pixel(after.icon!!.loadDrawable(context)!!.toBitmap())))
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    private fun pixel(bitmap: Bitmap): Int = bitmap.getPixel(0, 0)

    private companion object {
        val CHOICES = arrayOf<CharSequence>("👍🏽", "❤️", "👩‍💻", ":wn:", ":bitcoin:")
    }
}
