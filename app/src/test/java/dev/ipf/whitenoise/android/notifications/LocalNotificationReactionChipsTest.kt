package dev.ipf.whitenoise.android.notifications

import android.Manifest
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
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
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
        RecentEmojiPreferences.resetQuickReactions(context)
    }

    @Test
    fun reactOffersEveryConfiguredQuickReactionAsAChipWithoutFreeFormInput() {
        RecentEmojiPreferences.saveQuickReactions(context, listOf("🥳", "🔥", "😂", "👍", "😮", "😢"))
        postConversationCard(presenter())

        val remoteInput = reactRemoteInput()

        assertEquals(NotificationActions.KEY_REACTION_CHOICE, remoteInput.resultKey)
        assertEquals(
            listOf("🥳", "🔥", "😂", "👍", "😮", "😢"),
            remoteInput.choices?.map { it.toString() },
        )
        assertFalse(remoteInput.allowFreeFormInput)
        assertEquals(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED, remoteInput.editChoicesBeforeSending)
        assertEquals(context.getString(R.string.message_react), remoteInput.label.toString())
    }

    @Test
    fun theDefaultRowStaysReplyReactAndMarkRead() {
        postConversationCard(presenter())

        assertEquals(
            listOf(
                context.getString(R.string.reply),
                context.getString(R.string.message_react),
                context.getString(R.string.chat_row_action_mark_read),
            ),
            actions().map { it.title.toString() },
        )
    }

    @Test
    fun reactFiresAMutableBroadcastWithoutShowingUserInterface() {
        postConversationCard(presenter())

        val react = reactAction()
        val pendingIntent = shadowOf(react.actionIntent)

        assertTrue(pendingIntent.isBroadcast)
        // Android rejects posting a RemoteInput action on an immutable intent.
        assertFalse(pendingIntent.isImmutable)
        assertFalse(react.showsUserInterface)
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_THUMBS_UP, react.semanticAction)
        val parsed = NotificationActions.parse(pendingIntent.savedIntent)
        assertEquals(NotificationActionKind.REACT, parsed?.kind)
        assertEquals("message-a", parsed?.target?.messageIdHex)
    }

    @Test
    fun theChipTheShadeSendsBackArrivesThroughTheActionsOwnRemoteInput() {
        postConversationCard(presenter())

        val delivered =
            Intent().also {
                RemoteInput.addResultsToIntent(
                    reactAction().remoteInputs!!,
                    it,
                    Bundle().apply { putCharSequence(NotificationActions.KEY_REACTION_CHOICE, " 🔥 ") },
                )
            }

        assertEquals("🔥", notificationReactionChoice(delivered, allowedChoices = listOf("🥳", "🔥")))
    }

    @Test
    fun aTapWithNoChipResultYieldsNothingToSend() {
        assertNull(notificationReactionChoice(Intent(), allowedChoices = listOf("🔥")))
    }

    @Test
    fun aMutablePendingIntentCannotInjectAnUnconfiguredReaction() {
        postConversationCard(presenter(listOf("🥳", "🔥")))
        val delivered =
            Intent().also {
                RemoteInput.addResultsToIntent(
                    reactAction().remoteInputs!!,
                    it,
                    Bundle().apply { putCharSequence(NotificationActions.KEY_REACTION_CHOICE, "👾") },
                )
            }

        assertNull(notificationReactionChoice(delivered, allowedChoices = listOf("🥳", "🔥")))
    }

    @Test
    fun fewerConfiguredFavouritesYieldFewerChips() {
        postConversationCard(presenter(listOf("🥳", "🔥")))

        assertEquals(listOf("🥳", "🔥"), reactRemoteInput().choices?.map { it.toString() })
    }

    @Test
    fun chipsAreCappedAtTheQuickReactionLimit() {
        postConversationCard(presenter(listOf("🥳", "🔥", "😂", "👍", "😮", "😢", "🎉", "🙏")))

        assertEquals(MAX_NOTIFICATION_QUICK_REACTIONS, reactRemoteInput().choices?.size)
        assertEquals(listOf("🥳", "🔥", "😂", "👍", "😮", "😢"), reactRemoteInput().choices?.map { it.toString() })
    }

    @Test
    fun blankAndOversizedFavouritesNeverReachTheShade() {
        postConversationCard(presenter(listOf("🥳", "   ", "😀".repeat(33), "🔥", "🔥")))

        assertEquals(listOf("🥳", "🔥"), reactRemoteInput().choices?.map { it.toString() })
    }

    @Test
    fun reactIsOmittedRatherThanPostedWithNothingToChoose() {
        postConversationCard(presenter(emptyList()))

        assertEquals(
            listOf(
                context.getString(R.string.reply),
                context.getString(R.string.chat_row_action_mark_read),
            ),
            actions().map { it.title.toString() },
        )
    }

    @Test
    fun reactingClearsTheDirectReplyLifetimeExtensionBeforeCancellingTheCard() {
        val presenter = presenter()
        postConversationCard(presenter)
        val posted = manager.activeNotifications.single()
        val target = NotificationTarget("account-a", "group-a", "message-a", NotificationTargetKind.MESSAGE)
        val ops = mutableListOf<ConversationCardOp>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onLockAcquired(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    ops += op
                }
            }

        try {
            runBlocking {
                dismissReactedNotification(
                    presenter = presenter,
                    action =
                        NotificationAction(
                            kind = NotificationActionKind.REACT,
                            target = target,
                            notificationTag = posted.tag.orEmpty(),
                            notificationId = posted.id,
                        ),
                    reaction = "🔥",
                    dismissalBaselineMs = System.currentTimeMillis(),
                )
            }
        } finally {
            ConversationCardPostSynchronizer.testHook = null
        }

        assertEquals(
            listOf(ConversationCardOp.MARK_REPLY_HANDLED, ConversationCardOp.CANCEL_IF_SAME_GENERATION),
            ops.filter {
                it == ConversationCardOp.MARK_REPLY_HANDLED || it == ConversationCardOp.CANCEL_IF_SAME_GENERATION
            },
        )
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun presenter(choices: List<String>? = null): LocalNotificationPresenter {
        val presenter =
            if (choices == null) {
                LocalNotificationPresenter(context)
            } else {
                LocalNotificationPresenter(context, quickReactionChoices = { choices })
            }
        presenter.ensureChannels()
        return presenter
    }

    private fun postConversationCard(presenter: LocalNotificationPresenter) {
        runBlocking { presenter.show(update(), previewTextOverride = "hi", shortNpub = { "npub1test" }) }
        assertNotNull(manager.activeNotifications.singleOrNull())
    }

    private fun actions(): List<NotificationCompat.Action> {
        val notification = manager.activeNotifications.single().notification
        return (0 until NotificationCompat.getActionCount(notification)).map {
            checkNotNull(NotificationCompat.getAction(notification, it))
        }
    }

    private fun reactAction() = actions().single { it.title.toString() == context.getString(R.string.message_react) }

    private fun reactRemoteInput(): RemoteInput = reactAction().remoteInputs!!.single()

    private fun update(messageIdHex: String = "message-a") =
        NotificationUpdateFfi(
            notificationKey = "key",
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
            previewText = "hi",
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
