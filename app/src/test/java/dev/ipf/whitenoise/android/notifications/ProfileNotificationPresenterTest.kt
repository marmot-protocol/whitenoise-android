package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Presenter integration verifies actual posted channel IDs and People metadata, not a duplicate routing function. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileNotificationPresenterTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val pattern = ConversationVibrationPattern.SYSTEM_DEFAULT
    private lateinit var presenter: LocalNotificationPresenter

    /** Starts with an empty notification inventory and explicit preview policy so prior tests cannot mask routing. */
    @Before fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        presenter = LocalNotificationPresenter(context, groupReconciliation = {}, enrichmentLauncher = {})
        presenter.ensureChannels()
    }

    /**
     * The real presenter chooses the author override across chats while unrelated senders retain conversation
     * routing.
     */
    @Test fun oneAuthorUsesOneChannelAcrossDmAndGroupsWhileOtherAuthorsKeepChatRoutes() =
        runBlocking {
            val id = customize(alice)
            listOf("dm", "first", "new-group").forEach { group ->
                val update = update(group = group)
                assertTrue(presenter.show(update, senderNameOverride = "Alice", shortNpub = { it }))
                val card = card(update)
                assertEquals(id, card.channelId)
                assertEquals(conversationShortcutId("personal", group), card.shortcutId)
                assertEquals(
                    alice,
                    NotificationCompat.MessagingStyle
                        .extractMessagingStyleFromNotification(card)!!
                        .messages
                        .last()
                        .person!!
                        .key,
                )
            }
            val second = update(author = bob, group = "first", message = "bob-message")
            assertTrue(presenter.show(second, shortNpub = { it }))
            assertNotEquals(id, card(second).channelId)
            assertEquals(1, manager.notificationChannels.count { it.id.startsWith(ProfileNotificationChannels.PREFIX) })
        }

    /** A person mute must not suppress a different author or the same author under another signed-in identity. */
    @Test fun muteSuppressesOnlyAuthoredMessagesAndAnotherAccountIsIndependent() =
        runBlocking {
            ProfileNotificationOverridePreferences(context).set(
                "personal",
                alice,
                ProfileNotificationOverride(ProfileNotificationMode.MUTED),
            )
            listOf("dm", "group-one", "group-two").forEach { group ->
                assertFalse(presenter.show(update(group = group), shortNpub = { it }))
            }
            assertTrue(presenter.show(update(author = bob), shortNpub = { it }))
            assertTrue(presenter.show(update().copy(accountRef = "work"), shortNpub = { it }))
            assertTrue(presenter.show(update().copy(reactionEmoji = "👍"), shortNpub = { it }))
            assertTrue(
                presenter.show(
                    update().copy(trafficClass = NotificationTrafficClassFfi.AGENT_ACTIVITY),
                    shortNpub = { it },
                ),
            )
            assertEquals(0, manager.notificationChannels.count { it.id.startsWith(ProfileNotificationChannels.PREFIX) })
        }

    /** Person-specific sound remains subordinate to per-chat mute, mention-only mode and global category policy. */
    @Test fun profileCustomCannotBypassChatModeOrGlobalEligibility() =
        runBlocking {
            customize(alice)
            for (mode in ChatNotifyMode.entries) {
                for (mention in listOf(false, true)) {
                    for (muted in listOf(false, true)) {
                        val update = update().copy(isMention = mention)
                        val allowed =
                            LocalNotificationPolicy.shouldPost(
                                update,
                                false,
                                null,
                                null,
                                false,
                                conversationNotifyMode = { _, _ -> mode },
                                profileMuted = { _, _ -> muted },
                            )
                        val expected = !muted && mode != ChatNotifyMode.NONE && (mode == ChatNotifyMode.ALL || mention)
                        assertEquals(expected, allowed)
                    }
                }
            }
            assertFalse(presenter.show(update(), isPostStillAllowed = { false }, shortNpub = { it }))
            assertEquals(0, manager.activeNotifications.size)
        }

    /** Reset reroutes future alerts without moving or alerting an already-posted card during silent corrections. */
    @Test fun resetAffectsFuturePostsButSilentCorrectionsKeepTheExistingCardChannel() =
        runBlocking {
            val update = update()
            val id = customize(alice)
            assertTrue(presenter.show(update, shortNpub = { it }))
            ProfileNotificationOverridePreferences(context).set("personal", alice, ProfileNotificationOverride())
            assertTrue(
                presenter.show(
                    update,
                    previewTextOverride = "Corrected",
                    silentUpdate = true,
                    shortNpub = { it },
                ),
            )
            assertEquals(id, card(update).channelId)
            val future = update.copy(messageIdHex = "future", timestampMs = update.timestampMs + 1000)
            assertTrue(presenter.show(future, shortNpub = { it }))
            assertNotEquals(id, card(future).channelId)
            assertNotNull(manager.getNotificationChannel(id))
        }

    /** Locked/redacted notifications retain alert policy while removing identifying names and people metadata. */
    @Test fun redactionKeepsPersonPolicyButExposesNoProfileNameOrPeopleIdentity() =
        runBlocking {
            val id = customize(alice)
            val update = update()
            assertTrue(
                presenter.show(
                    update,
                    senderNameOverride = "Private nickname",
                    redactContent = true,
                    shortNpub = { it },
                ),
            )
            val card = card(update)
            assertEquals(id, card.channelId)
            assertFalse(manager.getNotificationChannel(id).name.contains("Private nickname"))
            assertNull(card.shortcutId)
            assertTrue(
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(card)
                    ?.messages
                    .orEmpty()
                    .none { it.person?.key == alice },
            )
        }

    /** A settings change while a first post is queued wins at the final platform write. */
    @Test
    fun profileMutedAfterRegistrationCannotPublish() =
        runBlocking {
            ConversationCardPostSynchronizer.testHook =
                object : ConversationCardTestHook {
                    override fun onBarrier(
                        op: ConversationCardOp,
                        barrier: ConversationCardBarrier,
                        notificationTag: String,
                        notificationId: Int,
                    ) {
                        if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_REGISTER) {
                            ConversationCardPostSynchronizer.testHook = null
                            ProfileNotificationOverridePreferences(context).set(
                                "personal",
                                alice,
                                ProfileNotificationOverride(ProfileNotificationMode.MUTED),
                            )
                        }
                    }
                }
            try {
                assertFalse(presenter.show(update(), shortNpub = { it }))
                assertEquals(0, manager.activeNotifications.size)
            } finally {
                ConversationCardPostSynchronizer.testHook = null
            }
        }

    /** Rename refreshes channel labels even with no visible card, without touching Android-owned alert settings. */
    @Test
    fun nicknameRefreshChangesChannelLabelsWithoutRepostingOrChangingAlertSettings() =
        runBlocking {
            val id = customize(alice)
            val before = manager.getNotificationChannel(id)
            presenter.refreshContactSenderName("personal", alice, "New private nickname")
            assertTrue(manager.getNotificationChannel(id).name.contains("New private nickname"))
            assertEquals(before.sound, manager.getNotificationChannel(id).sound)
            assertEquals(before.importance, manager.getNotificationChannel(id).importance)
            assertEquals(0, manager.activeNotifications.size)
        }

    /**
     * Self messages, reactions, membership events and agent traffic remain outside person-specific notification
     * policy.
     */
    @Test fun exclusionsNeverResolveAProfileOverride() {
        val normal = update()
        assertEquals(alice, profileNotificationAuthor(normal))
        assertNull(profileNotificationAuthor(normal.copy(isFromSelf = true)))
        assertNull(profileNotificationAuthor(normal.copy(reactionEmoji = "👍")))
        assertNull(profileNotificationAuthor(normal.copy(trigger = NotificationTriggerFfi.GROUP_INVITE)))
        assertNull(profileNotificationAuthor(normal.copy(trigger = NotificationTriggerFfi.REMOVED_FROM_GROUP)))
        assertNull(
            profileNotificationAuthor(
                normal.copy(
                    isMention = true,
                    trafficClass = NotificationTrafficClassFfi.AGENT_ACTIVITY,
                ),
            ),
        )
        assertNull(profileNotificationAuthor(normal.copy(sender = NotificationUserFfi("invalid", null, null))))
    }

    /** Creates a real OS channel plus its persisted routing choice for assertions against posted notifications. */
    private fun customize(author: String): String {
        val id = ProfileNotificationChannels(context).customize("personal", author, "Alice", pattern, pattern)
        check(
            ProfileNotificationOverridePreferences(context).set(
                "personal",
                author,
                ProfileNotificationOverride(ProfileNotificationMode.CUSTOM),
            ),
        )
        return id
    }

    /** Finds the exact account/conversation card rather than accepting any notification in the inventory. */
    private fun card(update: NotificationUpdateFfi) =
        manager.activeNotifications
            .single {
                val key = LocalNotificationFormatter.notificationDismissalKey(update)
                it.tag == key.tag && it.id == key.id
            }.notification

    /**
     * Builds a native notification projection with stable identities while allowing sender, chat and message
     * variation.
     */
    private fun update(
        author: String = alice,
        group: String = "first",
        message: String = "message",
    ) = NotificationUpdateFfi(
        notificationKey = "$group:$message",
        conversationKey = group,
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = "personal",
        accountIdHex = "c".repeat(64),
        groupIdHex = group,
        groupName = "Group",
        isDm = group == "dm",
        isMention = false,
        messageIdHex = message,
        sender = NotificationUserFfi(author, "Alice", null),
        receiver = NotificationUserFfi("c".repeat(64), "Me", null),
        previewText = "Hello",
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1234,
        isFromSelf = false,
    )
}
