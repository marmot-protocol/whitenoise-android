package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import org.robolectric.util.ReflectionHelpers

/** Exercises the platform payload, rather than only a privacy-policy helper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreviewPrivacyTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Each fixture begins with a granted permission and the persisted privacy opt-out. */
    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationGroupReconciler.shared(context).close()
        manager.cancelAll()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("show_notification_previews", false)
            .commit()
    }

    /** Even direct presenter callers must respect device-wide privacy before the OS write. */
    @Test
    fun hiddenPreviewsDoNotPublishPrivateContentOrActions() {
        val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
        presenter.ensureChannels()
        assertTrue(
            runBlocking {
                presenter.show(
                    update(),
                    recipientAccountSubtext = "Private account",
                    directShareEligible = true,
                    shortNpub = { "Private sender" },
                )
            },
        )
        val card =
            manager.activeNotifications
                .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                .notification
        assertEquals(context.getString(R.string.app_name), card.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(
            context.getString(R.string.notification_hidden_content),
            card.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertNull(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNotNull(card.shortcutId)
        assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))
        assertGenericShortcut(card)
        assertSafeActions(card)
        assertGenericConversation(card)
    }

    /** All event categories and both lock modes use the same scrubbed Android payload. */
    @Test
    fun everyUserEventIsGenericWithOrWithoutAppLock() {
        val events = NotificationTriggerFfi.entries
        events.forEach { trigger ->
            listOf(false, true).forEach { locked ->
                manager.cancelAll()
                val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
                presenter.ensureChannels()
                assertTrue(
                    runBlocking {
                        presenter.show(
                            update().copy(
                                trigger = trigger,
                                messageIdHex = "msg-$trigger",
                                reactionEmoji = if (trigger == NotificationTriggerFfi.NEW_MESSAGE) "❤️" else null,
                            ),
                            recipientAccountSubtext = "Private account",
                            redactContent = locked,
                            shortNpub = { "Private sender" },
                        )
                    },
                )
                val card =
                    manager.activeNotifications
                        .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                        .notification
                assertEquals(
                    context.getString(R.string.app_name),
                    card.extras.getCharSequence(Notification.EXTRA_TITLE),
                )
                assertNull(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                assertGenericShortcut(card)
                assertNull(card.largeIcon)
                assertNull(card.locusId)
                assertGenericConversation(card)
                assertSafeActions(card)
                assertEquals(
                    context.getString(R.string.notification_hidden_content),
                    card.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT),
                )
            }
        }
    }

    /** The explicit default-on preference never bypasses mandatory app-lock payload sanitization. */
    @Test
    fun appLockHidesMetadataWithPreviewsEnabled() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NotificationPreviewPreferences.KEY, true)
            .commit()
        NotificationTriggerFfi.entries.forEach { trigger ->
            manager.cancelAll()
            val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
            presenter.ensureChannels()
            assertTrue(
                runBlocking {
                    presenter.show(
                        update().copy(trigger = trigger),
                        recipientAccountSubtext = "Private account",
                        redactContent = true,
                        shortNpub = { "Private sender" },
                    )
                },
            )
            val card =
                manager.activeNotifications
                    .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                    .notification
            assertEquals(
                context.getString(R.string.app_name),
                card.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            assertNull(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            assertGenericShortcut(card)
            assertNull(card.largeIcon)
            assertGenericConversation(card)
        }
    }

    /** One device preference applies even to independent presenters for different accounts. */
    @Test
    fun previewOptOutCoversEveryAccount() {
        listOf("account-a", "account-b").forEach { account ->
            val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
            presenter.ensureChannels()
            assertTrue(
                runBlocking {
                    presenter.show(
                        update().copy(accountRef = account, accountIdHex = account),
                        recipientAccountSubtext = "Private $account",
                        shortNpub = { "Private sender" },
                    )
                },
            )
        }
        assertEquals(2, manager.activeNotifications.count { it.tag != UserEventNotificationGroup.SUMMARY_TAG })
        manager.activeNotifications.filterNot { it.tag == UserEventNotificationGroup.SUMMARY_TAG }.forEach { posted ->
            assertEquals(
                context.getString(R.string.app_name),
                posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            assertNull(posted.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            assertGenericConversation(posted.notification)
        }
    }

    /** Conversation/DND classification survives, without exporting its previous names or avatar. */
    @Test
    fun genericConversationKeepsOpaqueShortcutAndExistingChannel() {
        val id = checkNotNull(conversationShortcutId("account-a", "group-a"))
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, id)
                .setShortLabel("Private sender")
                .setLongLabel("Private group")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .setLongLived(true)
                .setPerson(
                    Person
                        .Builder()
                        .setName("Private sender")
                        .setUri("private:sender")
                        .build(),
                ).build()
        assertTrue(ShortcutManagerCompat.addDynamicShortcuts(context, listOf(shortcut)))
        val original =
            NotificationCompat
                .Builder(context, "existing-conversation-channel")
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
                .setShortcutId(id)
                .setStyle(
                    NotificationCompat
                        .MessagingStyle(Person.Builder().setName("Private account").build())
                        .setConversationTitle("Private group")
                        .addMessage("Private message", 123L, Person.Builder().setName("Private sender").build()),
                ).build()
        val inventoryShortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == id }
        val genericShortcut = genericNotificationShortcut(context, inventoryShortcut)
        assertTrue(
            ReflectionHelpers.callInstanceMethod<Boolean>(genericShortcut.toShortcutInfo(), "isLongLived"),
        )
        val hidden = notificationWithoutPreview(context, original)
        assertEquals(original.channelId, hidden.channelId)
        assertEquals(id, hidden.shortcutId)
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(hidden))
        assertNull(style.conversationTitle)
        assertEquals(context.getString(R.string.app_name), style.user.name)
        assertNull(style.user.uri)
        assertNull(style.user.icon)
        assertGenericConversation(hidden)
        val published = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == id }
        assertEquals(context.getString(R.string.app_name), published.shortLabel)
        assertEquals(context.getString(R.string.app_name), published.longLabel)
    }

    /** Prepared Settings shortcuts cannot restore private labels across an off/on transition. */
    @Test
    fun conversationSettingsPublisherFencesPreparedPrivateShortcut() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NotificationPreviewPreferences.KEY, true)
            .commit()
        val id = checkNotNull(conversationShortcutId("account-a", "group-a"))
        val prepared = conversationSettingsShortcut(context, id, "account-a", "group-a", "Private group", null)
        runBlocking {
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
            AndroidConversationNotificationSettingsPlatform.publishShortcut(context, prepared, existing = false)
        }
        val published = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == id }
        assertEquals(context.getString(R.string.app_name), published.longLabel)
    }

    private fun assertSafeActions(card: Notification) {
        val markRead = NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
        assertTrue(card.actions.orEmpty().all { it.semanticAction == markRead })
    }

    private fun assertGenericConversation(card: Notification) {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card) ?: return
        assertEquals(context.getString(R.string.app_name), style.user.name)
        style.messages.forEach { message ->
            assertEquals(context.getString(R.string.notification_hidden_content), message.text)
            assertEquals(context.getString(R.string.app_name), message.person?.name)
            assertNull(message.person?.uri)
            assertNull(message.person?.icon)
            assertNull(message.dataUri)
        }
    }

    /** Delayed image/shortcut enrichment must never export the private sender or title. */
    @Test
    fun hiddenCardsNeverPublishConversationShortcuts() {
        val enrichment = mutableListOf<suspend () -> Unit>()
        var shortcuts = 0
        val presenter =
            LocalNotificationPresenter(
                context,
                groupReconciliation = {},
                shortcutPublisher = { shortcuts++ },
                enrichmentLauncher = { enrichment.add(it) },
            )
        presenter.ensureChannels()
        runBlocking {
            assertTrue(presenter.show(update(), directShareEligible = true, shortNpub = { "Private sender" }))
            enrichment.forEach { it() }
        }
        assertEquals(0, shortcuts)
    }

    /** Old prepared posts remain generic even when both toggles finish before the OS write. */
    @Test
    fun offThenOnFencesAnAlreadyPreparedInitialPost() {
        runBlocking { assertTrue(NotificationPreviewPreferences.setEnabled(context, true)) }
        val prepared = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (barrier == ConversationCardBarrier.BEFORE_PLATFORM_WRITE) {
                        prepared.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
        val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
        presenter.ensureChannels()
        val thread =
            Thread {
                try {
                    runBlocking { assertTrue(presenter.show(update(), shortNpub = { "Private sender" })) }
                } catch (
                    error: Throwable,
                ) {
                    failure.set(error)
                } finally {
                    completed.countDown()
                }
            }
        thread.start()
        try {
            assertTrue(prepared.await(10, java.util.concurrent.TimeUnit.SECONDS))
            runBlocking {
                assertTrue(NotificationPreviewPreferences.setEnabled(context, false))
                assertTrue(NotificationPreviewPreferences.setEnabled(context, true))
            }
        } finally {
            release.countDown()
            assertTrue(completed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            ConversationCardPostSynchronizer.testHook = null
        }
        failure.get()?.let { throw it }
        assertEquals(
            context.getString(R.string.app_name),
            manager.activeNotifications
                .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                .notification.extras
                .getCharSequence(Notification.EXTRA_TITLE),
        )
        showFreshMessage(presenter)
        val fresh =
            manager.activeNotifications
                .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                .notification
        assertTrue(
            fresh.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                .toString()
                .contains("Private"),
        )
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(fresh)?.messages
        assertTrue(messages == null || messages.none { it.text.toString().contains("Private text") })
    }

    /** Nickname corrections after re-enabling cannot reconstruct already hidden content. */
    @Test
    fun silentCorrectionDoesNotRestoreAnExistingHiddenCard() {
        val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
        presenter.ensureChannels()
        runBlocking {
            assertTrue(presenter.show(update(), shortNpub = { "Private sender" }))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true))
            assertTrue(
                presenter.show(
                    update(),
                    silentUpdate = true,
                    replaceCurrentMessage = true,
                    shortNpub = { "Private sender" },
                ),
            )
        }
        assertEquals(
            context.getString(R.string.app_name),
            manager.activeNotifications
                .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                .notification.extras
                .getCharSequence(Notification.EXTRA_TITLE),
        )
    }

    /** A new silent generation after enabling is new content; the older hidden line stays absent. */
    @Test
    fun newSilentMessageAfterEnablingDoesNotCarryHiddenHistory() =
        runBlocking {
            val presenter = LocalNotificationPresenter(context, enrichmentLauncher = {}, groupReconciliation = {})
            presenter.ensureChannels()
            assertTrue(presenter.show(update(), shortNpub = { "sender" }))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true))
            assertTrue(
                presenter.show(
                    update().copy(messageIdHex = "new", previewText = "New text"),
                    silentUpdate = true,
                    shortNpub = { "sender" },
                ),
            )
            val card =
                manager.activeNotifications
                    .single { it.tag != UserEventNotificationGroup.SUMMARY_TAG }
                    .notification
            assertTrue(!card.extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card)!!
            assertEquals(listOf("New text"), style.messages.map { it.text.toString() })
            assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    private fun showFreshMessage(presenter: LocalNotificationPresenter) =
        runBlocking {
            assertTrue(
                presenter.show(
                    update().copy(messageIdHex = "fresh", previewText = "Fresh text"),
                    shortNpub = { "Private sender" },
                ),
            )
        }

    private fun assertGenericShortcut(card: Notification) {
        card.shortcutId?.let { id ->
            assertTrue(isConversationShortcutId(id))
            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == id }
            assertEquals(context.getString(R.string.app_name), shortcut.longLabel)
            val framework =
                context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.single { it.id == id }
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(framework, "isLongLived"))
            assertTrue(shortcut.categories.isNullOrEmpty())
            assertTrue(shortcut.extras!!.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
        }
    }

    private fun update() =
        NotificationUpdateFfi(
            notificationKey = "key",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "Private group",
            isDm = false,
            isMention = false,
            messageIdHex = "msg-a",
            sender = NotificationUserFfi("sender", "Private sender", null),
            receiver = NotificationUserFfi("self", "Private self", null),
            previewText = "Private text",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1000L,
            isFromSelf = false,
        )
}
