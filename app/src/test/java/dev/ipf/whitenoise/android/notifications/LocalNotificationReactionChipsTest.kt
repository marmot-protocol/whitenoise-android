package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationReactionChipsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    @Test
    fun replyOffersSixReactionChoicesAndStillAllowsFreeFormInput() {
        val choices = listOf("🥳", "🔥", "😂", "👍", "😮", "😢")
        postConversationCard(presenter(choices))

        val remoteInput = checkNotNull(replyAction().remoteInputs).single()

        assertEquals(NotificationActions.KEY_TEXT_REPLY, remoteInput.resultKey)
        assertEquals(choices, remoteInput.choices?.map(CharSequence::toString))
        assertTrue(remoteInput.allowFreeFormInput)
        assertEquals(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED, remoteInput.editChoicesBeforeSending)
    }

    /** The card offers Reply then Mark as read, and Reply's mutable broadcast routes a chosen chip as a reaction. */
    @Test
    fun rowKeepsReplyAndMarkReadWhileReplyUsesAMutableBroadcast() {
        postConversationCard(presenter(defaultChoices()))

        assertEquals(
            listOf(
                context.getString(R.string.reply),
                context.getString(R.string.chat_row_action_mark_read),
            ),
            actions().map { it.title.toString() },
        )
        val reply = replyAction()
        val pendingIntent = shadowOf(reply.actionIntent)
        assertTrue(pendingIntent.isBroadcast)
        assertFalse(pendingIntent.isImmutable)
        assertFalse(reply.showsUserInterface)
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)

        val selectedChoice = Intent(pendingIntent.savedIntent)
        RemoteInput.addResultsToIntent(
            checkNotNull(reply.remoteInputs),
            selectedChoice,
            Bundle().apply { putCharSequence(NotificationActions.KEY_TEXT_REPLY, defaultChoices().first()) },
        )
        RemoteInput.setResultsSource(selectedChoice, RemoteInput.SOURCE_CHOICE)
        assertEquals(NotificationActionKind.REACT, NotificationActions.parse(selectedChoice)?.kind)
    }

    /** A handled reaction for an older message never writes history onto a newer message's card. */
    @Test
    fun reactionHandledRepostCannotAnnotateANewerMessageGeneration() {
        val presenter = presenter(defaultChoices())
        postConversationCard(presenter, "message-a")
        postConversationCard(presenter, "message-b")

        assertFalse(
            presenter.markReactionHandledIfSameGeneration(
                notificationTag = manager.activeNotifications.single().tag,
                notificationId = manager.activeNotifications.single().id,
                reactedMessageIdHex = "message-a",
                reaction = "🔥",
            ),
        )

        val live = manager.activeNotifications.single().notification
        assertEquals(
            "message-b",
            live.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX),
        )
        assertNull(live.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY))
    }

    /** Android draws choice chips as text only, so shortcode chips show their exact code and tapping one sends it. */
    @Test
    fun shortcodeChipsShowTheirExactCodeAndSendIt() {
        val choices = listOf(":marmot:", ":wn:", "👍")
        postConversationCard(presenter(choices))
        val reply = replyAction()
        val remoteInput = checkNotNull(reply.remoteInputs).single()

        assertEquals(choices, remoteInput.choices?.map(CharSequence::toString))
        val tapped =
            Intent(shadowOf(reply.actionIntent).savedIntent).also { intent ->
                RemoteInput.addResultsToIntent(
                    arrayOf(remoteInput),
                    intent,
                    Bundle().apply { putCharSequence(NotificationActions.KEY_TEXT_REPLY, ":wn:") },
                )
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE)
            }
        assertEquals(":wn:", notificationReactionChoice(tapped, choices))
    }

    /** Built-in choices keep Android's conversation card: no custom expanded view, Reply and Mark as read stay. */
    @Test
    fun builtInChoicesKeepTheStandardConversationCard() {
        postConversationCard(presenter(listOf(":marmot:", ":wn:", "👍")))
        val live = manager.activeNotifications.single().notification

        assertNull(live.bigContentView)
        assertEquals(Notification.MessagingStyle::class.java.name, live.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(
            listOf(context.getString(R.string.reply), context.getString(R.string.chat_row_action_mark_read)),
            actions().map { it.title.toString() },
        )
        assertFalse(live.extras.containsKey(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES))
        assertFalse(live.extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET))
    }

    /** A card left by the earlier expanded-choice build loses that layout on its next write and keeps its thread. */
    @Test
    fun earlierExpandedChoiceCardReturnsToTheStandardCardOnItsNextWrite() {
        val presenter = presenter(listOf(":marmot:", ":wn:", "👍"))
        postConversationCard(presenter, "message-a")
        val posted = manager.activeNotifications.single()
        val legacy =
            NotificationCompat
                .Builder(context, posted.notification)
                .setCustomBigContentView(RemoteViews(context.packageName, R.layout.notification_reaction_inline))
                .addExtras(
                    Bundle().apply {
                        putBoolean(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES, true)
                        putBundle(LEGACY_EXTRA_FIXED_REACTION_TARGET, Bundle().apply { putString("target", "a") })
                    },
                ).build()
        manager.notify(posted.tag, posted.id, legacy)
        assertNotNull(
            manager.activeNotifications
                .single()
                .notification.bigContentView,
        )

        assertTrue(presenter.markReactionHandledIfSameGeneration(posted.tag, posted.id, "message-a", ":marmot:"))

        val live = manager.activeNotifications.single().notification
        assertNull(live.bigContentView)
        assertFalse(live.extras.containsKey(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES))
        assertFalse(live.extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET))
        assertEquals(2, actions().size)
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(live))
        assertEquals(
            "message-a",
            style.messages
                .last()
                .text
                .toString(),
        )
        assertEquals(
            listOf(":marmot:"),
            live.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.map(CharSequence::toString),
        )
    }

    private fun presenter(choices: List<String>) =
        LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            quickReactionChoices = { choices },
        ).also(LocalNotificationPresenter::ensureChannels)

    private fun postConversationCard(
        presenter: LocalNotificationPresenter,
        messageIdHex: String = "message-a",
    ) {
        runBlocking {
            presenter.show(
                update(messageIdHex),
                previewTextOverride = messageIdHex,
                shortNpub = { "npub1test" },
            )
        }
        assertNotNull(manager.activeNotifications.singleOrNull())
    }

    private fun actions(): List<NotificationCompat.Action> {
        val notification = manager.activeNotifications.single().notification
        return (0 until NotificationCompat.getActionCount(notification)).map {
            checkNotNull(NotificationCompat.getAction(notification, it))
        }
    }

    private fun replyAction() = actions().single { it.title.toString() == context.getString(R.string.reply) }

    private fun defaultChoices() = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

    private fun update(messageIdHex: String) =
        NotificationUpdateFfi(
            notificationKey = "key-$messageIdHex",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "General",
            isDm = false,
            isMention = false,
            messageIdHex = messageIdHex,
            sender = user(displayName = "Alice"),
            receiver = user(accountIdHex = "self", displayName = "Me"),
            previewText = messageIdHex,
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1234,
            isFromSelf = false,
        )

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )
}
