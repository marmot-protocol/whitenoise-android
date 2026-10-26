package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationFixedReactionChoicesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val choices = listOf(":marmot:", ":wn:", "👍", ":unknown:", "❤️", "😂")
    private val target =
        NotificationActionTarget(
            NotificationTarget("preview", "a".repeat(64), "b".repeat(64), NotificationTargetKind.MESSAGE),
            "card",
            7,
        )

    @After
    fun reset() {
        RecentEmojiPreferences.resetQuickReactions(context)
    }

    @Test
    fun fixedChoicesKeepExactValueAndNeverAliasEvenWithRequestCodeZero() {
        val marmot = checkNotNull(fixedNotificationReactionPendingIntent(context, target, ":marmot:", choices))
        val wn = checkNotNull(fixedNotificationReactionPendingIntent(context, target, ":wn:", choices))
        assertNotEquals(marmot, wn)
        assertTrue(marmot.isImmutable)
        val variants =
            listOf(
                target.copy(target = target.target.copy(accountRef = "other")),
                target.copy(target = target.target.copy(groupIdHex = "c".repeat(64))),
                target.copy(target = target.target.copy(messageIdHex = "d".repeat(64))),
                target.copy(notificationTag = "different"),
                target.copy(notificationId = 8),
            )
        variants.forEach { other ->
            assertNotEquals(marmot, fixedNotificationReactionPendingIntent(context, other, ":marmot:", choices))
        }
        val intent = checkNotNull(fixedNotificationReactionIntent(context, target, ":marmot:", choices))
        assertEquals(NotificationActionKind.REACT, NotificationActions.parse(intent)?.kind)
        assertEquals(target.target, NotificationActions.parse(intent)?.target)
        assertEquals(":marmot:", notificationReactionChoice(intent, choices))
        assertNull(notificationReactionChoice(intent, listOf(":wn:")))
        assertFalse(intent.component == null)
        assertNull(fixedNotificationReactionIntent(context, target, "not-configured", choices))
    }

    @Test
    fun savedShortcodesBecomeBundledImagesAndUnknownChoiceStaysHonestText() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        assertEquals(choices, notificationQuickReactionChoices(context))
        val original = card("first")
        val expanded = withExpandedReactionChoices(context, original, "card", 7)
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(expanded))
        assertEquals(
            "first",
            style.messages
                .last()
                .text
                .toString(),
        )
        assertEquals(
            original.extras.getString(Notification.EXTRA_TEMPLATE),
            expanded.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertNull(expanded.contentView)
        assertNull(expanded.headsUpContentView)
        val view = checkNotNull(expanded.bigContentView).apply(context, FrameLayout(context))
        val first = view.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_first)
        val marmot = first.getChildAt(0)
        assertEquals(View.VISIBLE, marmot.findViewById<ImageView>(R.id.reaction_choice_image).visibility)
        assertEquals(View.GONE, marmot.findViewById<TextView>(R.id.reaction_choice_text).visibility)
        val second = view.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_second)
        assertEquals(
            ":unknown:",
            second
                .getChildAt(0)
                .findViewById<TextView>(R.id.reaction_choice_text)
                .text
                .toString(),
        )
    }

    @Test
    fun recoveredCardsRegenerateBodyAndRedactionOrRemovedActionsClearOldRail() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val first = withExpandedReactionChoices(context, card("old excerpt"), "card", 7)
        val renamed = NotificationCompat.Builder(context, first).setStyle(style("new excerpt")).build()
        val updated = withExpandedReactionChoices(context, renamed, "card", 7)
        val view = checkNotNull(updated.bigContentView).apply(context, FrameLayout(context))
        val body = view.findViewById<TextView>(R.id.reaction_message_body).text.toString()
        assertTrue(body.contains("new excerpt"))
        assertFalse(body.contains("old excerpt"))
        val noActions = NotificationCompat.Builder(context, first).clearActions().build()
        assertNull(withExpandedReactionChoices(context, noActions, "card", 7).bigContentView)
        first.extras.putBoolean(EXTRA_CONTENT_REDACTED, true)
        assertNull(withExpandedReactionChoices(context, first, "card", 7).bigContentView)
    }

    @Test
    fun latestMessageMismatchAndNativeReplyHistoryKeepNativeLayout() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val notification = card("message")
        notification.extras.putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, "e".repeat(64))
        assertNull(withExpandedReactionChoices(context, notification, "card", 7).bigContentView)
        val reply =
            NotificationCompat
                .Builder(
                    context,
                    card("message"),
                ).apply {
                    extras.remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                    extras.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)
                }.setRemoteInputHistory(arrayOf("sent reply"))
                .build()
        assertEquals(
            listOf("sent reply"),
            reply.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.toList(),
        )
        val expandedReply = withExpandedReactionChoices(context, reply, "card", 7)
        assertEquals(
            listOf("sent reply"),
            expandedReply.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.toList(),
        )
        val replyView = checkNotNull(expandedReply.bigContentView).apply(context, FrameLayout(context))
        val rows = replyView.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_messages)
        assertEquals(
            "sent reply",
            rows
                .getChildAt(rows.childCount - 1)
                .findViewById<TextView>(R.id.reaction_message_body)
                .text
                .toString(),
        )
    }

    @Test
    fun longFirstMessageKeepsFullBodyAndAttachmentMessagesKeepNativeTemplate() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val body = "Long first message ".repeat(40)
        val bigText =
            NotificationCompat
                .Builder(context, card("message"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .build()
        val expanded = withExpandedReactionChoices(context, bigText, "card", 7)
        assertEquals(body, expanded.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        val view = checkNotNull(expanded.bigContentView).apply(context, FrameLayout(context))
        assertEquals(body, view.findViewById<TextView>(R.id.reaction_choices_body).text.toString())
        val sender = Person.Builder().setName("Fixture contact").build()
        val attachment =
            NotificationCompat.MessagingStyle
                .Message("artwork", 2L, sender)
                .setData("image/png", android.net.Uri.parse("content://fixture/retained-artwork"))
        val original =
            NotificationCompat
                .Builder(context, card("message"))
                .setStyle(style("message").addMessage(attachment))
                .build()
        val native = withExpandedReactionChoices(context, original, "card", 7)
        assertNull(native.bigContentView)
        val retained = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(native))
        assertEquals(attachment.dataUri, retained.messages.last().dataUri)
    }

    @Test
    fun actualEmojiMessagePairStaysOneLogicalRowAndKeepsItsNativeUri() =
        kotlinx.coroutines.runBlocking<Unit> {
            RecentEmojiPreferences.saveQuickReactions(context, choices)
            val sender =
                Person
                    .Builder()
                    .setName("Fixture contact")
                    .setKey("fixture-contact")
                    .build()
            val artifact = checkNotNull(notificationEmojiArtwork(context, "hello :wn:"))
            try {
                val pair = notificationEmojiMessages("hello :wn:", 3L, sender, artifact.uri, "Custom emoji")
                val messages = style("earlier").also { value -> pair.forEach(value::addMessage) }
                val original = NotificationCompat.Builder(context, card("message")).setStyle(messages).build()
                val expanded = withExpandedReactionChoices(context, original, "card", 7)
                val retained =
                    checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(expanded))
                assertEquals(artifact.uri, retained.messages[retained.messages.size - 2].dataUri)
                assertEquals(2, notificationLogicalMessageGroups(retained.messages).size)
                val view = checkNotNull(expanded.bigContentView).apply(context, FrameLayout(context))
                val rows = view.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_messages)
                assertEquals(2, rows.childCount)
                assertEquals(
                    "Fixture contact: hello :wn:",
                    rows
                        .getChildAt(1)
                        .findViewById<TextView>(R.id.reaction_message_body)
                        .text
                        .toString(),
                )
                checkNotNull(rows.getChildAt(1).findViewById<ImageView>(R.id.reaction_message_artwork).drawable)
            } finally {
                artifact.close()
                pruneNotificationEmojiArtwork(context, emptyArray())
            }
        }

    @Test
    fun controlsPrecedeUnboundedLongBodyAndMultipleLogicalHistoryRows() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val body = "Long first message ".repeat(100)
        val longCard =
            NotificationCompat
                .Builder(context, card("message"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .build()
        val longView = assertControlsPrecedeContent(longCard, R.id.reaction_choices_body)
        val retainedBody = checkNotNull(longCard.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).toString()
        assertTrue(retainedBody.isNotBlank())
        assertTrue(body.startsWith(retainedBody))
        assertEquals(retainedBody, longView.findViewById<TextView>(R.id.reaction_choices_body).text.toString())
        val sender = Person.Builder().setName("Fixture contact").build()
        val history = style(body)
        notificationEmojiMessages("second logical message", 2L, sender, null).forEach(history::addMessage)
        notificationEmojiMessages("third logical message", 3L, sender, null).forEach(history::addMessage)
        val historyCard = NotificationCompat.Builder(context, card("message")).setStyle(history).build()
        val historyView = assertControlsPrecedeContent(historyCard, R.id.reaction_choices_messages)
        val rows = historyView.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_messages)
        assertEquals(3, rows.childCount)
        val retainedFirstMessage =
            checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(historyCard))
                .messages
                .first()
                .text
                .toString()
        assertTrue(retainedFirstMessage.isNotBlank())
        assertTrue(body.startsWith(retainedFirstMessage))
        assertTrue(
            rows
                .getChildAt(0)
                .findViewById<TextView>(R.id.reaction_message_body)
                .text
                .toString()
                .contains(retainedFirstMessage),
        )
    }

    @Test
    @Config(sdk = [30])
    fun api30ReplyHistorySurvivesExpansionAndClearWithoutStructuredHistoryItems() {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val reply =
            NotificationCompat
                .Builder(context, card("message"))
                .setRemoteInputHistory(arrayOf("sent reply"))
                .build()
                .withoutApi30RemoteInputHistoryItems()
        val expanded = withExpandedReactionChoices(context, reply, "card", 7)
        assertTrue(expanded.extras.getBoolean(EXTRA_EXPANDED_REACTION_CHOICES))
        assertNotNull(expanded.bigContentView)
        val cleared = clearExpandedReactionChoices(context, expanded)
        assertFalse(cleared.extras.getBoolean(EXTRA_EXPANDED_REACTION_CHOICES))
        assertNull(cleared.bigContentView)
        listOf(expanded, cleared).forEach { value ->
            assertEquals(
                listOf("sent reply"),
                value.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.toList(),
            )
            assertFalse(value.extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS))
        }
    }

    /** Real app RemoteViews inflation/order, without claiming SystemUI height or device rendering. */
    private fun assertControlsPrecedeContent(
        notification: Notification,
        contentId: Int,
    ): android.widget.LinearLayout {
        val expanded = withExpandedReactionChoices(context, notification, "card", 7)
        val root =
            checkNotNull(
                expanded.bigContentView,
            ).apply(context, FrameLayout(context)) as android.widget.LinearLayout
        val first = root.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_first)
        val second = root.findViewById<android.widget.LinearLayout>(R.id.reaction_choices_second)
        val content = root.findViewById<View>(contentId)
        assertEquals(3, first.childCount)
        assertEquals(3, second.childCount)
        assertTrue(root.indexOfChild(first) < root.indexOfChild(content))
        assertTrue(root.indexOfChild(second) < root.indexOfChild(content))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(first.top < content.top)
        assertTrue(second.bottom <= content.top)
        return root
    }

    private fun style(body: String): NotificationCompat.MessagingStyle {
        val sender = Person.Builder().setName("Fixture contact").build()
        return NotificationCompat
            .MessagingStyle(Person.Builder().setName("Fixture account").build())
            .addMessage(body, 1L, sender)
    }

    private fun card(body: String): Notification =
        NotificationCompat
            .Builder(context, "fixture")
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setStyle(style(body))
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_stat_whitenoise,
                        "Reply",
                        checkNotNull(fixedNotificationReactionPendingIntent(context, target, ":marmot:", choices)),
                    ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            ).addExtras(notificationFixedReactionTargetExtras(target))
            .addExtras(
                android.os.Bundle().apply {
                    putString(
                        LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
                        target.target.messageIdHex,
                    )
                },
            ).build()
}
