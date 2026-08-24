package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
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
    fun reactOffersSixChoicesWithoutFreeFormInput() {
        val choices = listOf("🥳", "🔥", "😂", "👍", "😮", "😢")
        postConversationCard(presenter(choices))

        val remoteInput = checkNotNull(reactAction().remoteInputs).single()

        assertEquals(NotificationActions.KEY_REACTION_CHOICE, remoteInput.resultKey)
        assertEquals(choices, remoteInput.choices?.map(CharSequence::toString))
        assertFalse(remoteInput.allowFreeFormInput)
        assertEquals(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED, remoteInput.editChoicesBeforeSending)
    }

    @Test
    fun rowStaysReplyReactAndMarkReadAndReactUsesAMutableBroadcast() {
        postConversationCard(presenter(defaultChoices()))

        assertEquals(
            listOf(
                context.getString(R.string.reply),
                context.getString(R.string.message_react),
                context.getString(R.string.chat_row_action_mark_read),
            ),
            actions().map { it.title.toString() },
        )
        val react = reactAction()
        val pendingIntent = shadowOf(react.actionIntent)
        assertTrue(pendingIntent.isBroadcast)
        assertFalse(pendingIntent.isImmutable)
        assertFalse(react.showsUserInterface)
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP, react.semanticAction)
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

    private fun presenter(choices: List<String>) =
        LocalNotificationPresenter(
            context,
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

    private fun reactAction() = actions().single { it.title.toString() == context.getString(R.string.message_react) }

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
