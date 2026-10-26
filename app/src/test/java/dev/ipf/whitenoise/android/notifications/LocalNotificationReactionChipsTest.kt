package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    /** Shortcode chips show readable names, and tapping one sends its exact reaction through the posted intent. */
    @Test
    fun shortcodeChipsShowReadableNamesAndSendTheirExactReaction() {
        val choices = listOf(":marmot:", ":wn:", "👍", ":party_parrot:")
        postConversationCard(presenter(choices))
        val reply = replyAction()
        val remoteInput = checkNotNull(reply.remoteInputs).single()
        val marmot = context.getString(R.string.emoji_name_marmot)
        val whiteNoise = context.getString(R.string.white_noise)

        assertEquals(
            listOf(marmot, whiteNoise, "👍", "party parrot"),
            remoteInput.choices?.map(CharSequence::toString),
        )

        fun tapped(
            chip: String,
            source: Int = RemoteInput.SOURCE_CHOICE,
        ) = Intent(shadowOf(reply.actionIntent).savedIntent).also { intent ->
            RemoteInput.addResultsToIntent(
                arrayOf(remoteInput),
                intent,
                Bundle().apply { putCharSequence(NotificationActions.KEY_TEXT_REPLY, chip) },
            )
            RemoteInput.setResultsSource(intent, source)
        }

        assertEquals(":marmot:", notificationReactionChoice(tapped(marmot), choices))
        assertEquals(":wn:", notificationReactionChoice(tapped(whiteNoise), choices))
        assertEquals("👍", notificationReactionChoice(tapped("👍"), choices))
        assertEquals(":party_parrot:", notificationReactionChoice(tapped("party parrot"), choices))
        // Typing the same name is still a text reply, and a choice removed since posting is refused.
        assertEquals(
            NotificationActionKind.REPLY,
            NotificationActions.parse(tapped(marmot, RemoteInput.SOURCE_FREE_FORM_INPUT))?.kind,
        )
        assertNull(notificationReactionChoice(tapped(marmot), listOf("👍")))
    }

    /** Boundary underscores and blank names preserve each posted chip's exact reaction. */
    @Test
    fun normalizedChipNamesRoundTripThroughThePostedIntent() {
        val choices = listOf(":_wave_:", ":wave:", ":_party_:", ":__:")
        postConversationCard(presenter(choices))
        val reply = replyAction()
        val remoteInput = checkNotNull(reply.remoteInputs).single()
        val labels = checkNotNull(remoteInput.choices).map(CharSequence::toString)

        assertEquals(listOf(":_wave_:", ":wave:", "party", ":__:"), labels)
        labels.zip(choices).forEach { (label, expected) ->
            val tapped = Intent(shadowOf(reply.actionIntent).savedIntent)
            RemoteInput.addResultsToIntent(
                arrayOf(remoteInput),
                tapped,
                Bundle().apply { putCharSequence(NotificationActions.KEY_TEXT_REPLY, label) },
            )
            RemoteInput.setResultsSource(tapped, RemoteInput.SOURCE_CHOICE)
            assertEquals(expected, notificationReactionChoice(tapped, choices))
        }
    }

    /** After a shortcode chip is handled, the card's reply history repeats the chip's name, not `:marmot:`. */
    @Test
    fun handledShortcodeReactionHistoryRepeatsTheChipName() {
        val presenter = presenter(listOf(":marmot:", "👍"))
        postConversationCard(presenter, "message-a")
        val posted = manager.activeNotifications.single()

        assertTrue(presenter.markReactionHandledIfSameGeneration(posted.tag, posted.id, "message-a", ":marmot:"))

        assertEquals(
            listOf(context.getString(R.string.emoji_name_marmot)),
            manager.activeNotifications
                .single()
                .notification.extras
                .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                ?.map(CharSequence::toString),
        )
    }

    /** Colliding names and blank names retain the same exact shortcode as their configured chips. */
    @Test
    fun handledReactionHistoryPreservesCollisionAndBlankLabelFallbacks() {
        val choices = listOf(":wave:", ":_wave_:", ":___:")
        var configured = choices
        val presenter =
            LocalNotificationPresenter(context, groupReconciliation = {}, quickReactionChoices = { configured })
                .also(LocalNotificationPresenter::ensureChannels)
        postConversationCard(presenter, "message-a")
        assertEquals(
            choices,
            replyAction()
                .remoteInputs
                .single()
                .choices
                ?.map(CharSequence::toString),
        )
        // History must repeat the posted chip even if configuration changes before completion.
        configured = listOf(":wave:")
        val posted = manager.activeNotifications.single()

        choices.forEach { reaction ->
            assertTrue(presenter.markReactionHandledIfSameGeneration(posted.tag, posted.id, "message-a", reaction))
            assertEquals(
                listOf(reaction),
                manager.activeNotifications
                    .single()
                    .notification.extras
                    .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                    ?.map(CharSequence::toString),
            )
        }
    }

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

    @Test
    fun rejectedExpandedChoicesRetryTheSameMessageThroughNativeLayoutOnce() {
        val attempted = mutableListOf<Notification>()
        val presenter =
            LocalNotificationPresenter(
                context,
                groupReconciliation = {},
                quickReactionChoices = { listOf(":marmot:", ":wn:", "👍") },
                notificationPoster = { compat, tag, id, notification ->
                    attempted += notification
                    if (attempted.size == 1) {
                        checkNotNull(notification.bigContentView)
                        throw IllegalArgumentException("Rejected fixture RemoteViews")
                    }
                    compat.notify(tag, id, notification)
                },
            ).also(LocalNotificationPresenter::ensureChannels)
        postConversationCard(presenter)
        assertEquals(2, attempted.size)
        assertNull(attempted.last().bigContentView)
        val style =
            checkNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(attempted.last()),
            )
        assertEquals(
            "message-a",
            style.messages
                .last()
                .text
                .toString(),
        )
        assertEquals(2, attempted.last().actions.size)
    }

    @Test
    fun optionalLayoutRetryRechecksPreviewPrivacyInsteadOfResendingRichPayload() {
        val attempted = mutableListOf<Notification>()
        val presenter =
            LocalNotificationPresenter(
                context,
                groupReconciliation = {},
                quickReactionChoices = { listOf(":marmot:", ":wn:", "👍") },
                notificationPoster = { compat, tag, id, notification ->
                    attempted += notification
                    if (attempted.size == 1) {
                        runBlocking { NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }) }
                        throw IllegalArgumentException("Rejected fixture RemoteViews")
                    }
                    compat.notify(tag, id, notification)
                },
            ).also(LocalNotificationPresenter::ensureChannels)
        try {
            postConversationCard(presenter)
            assertEquals(2, attempted.size)
            val native = attempted.last()
            assertNull(native.bigContentView)
            val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(native))
            assertEquals(
                context.getString(R.string.notification_hidden_content),
                style.messages
                    .single()
                    .text
                    .toString(),
            )
            assertEquals(
                listOf(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ),
                native.actions.map { it.semanticAction },
            )
        } finally {
            runBlocking { NotificationPreviewPreferences.setEnabled(context, true) }
        }
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
