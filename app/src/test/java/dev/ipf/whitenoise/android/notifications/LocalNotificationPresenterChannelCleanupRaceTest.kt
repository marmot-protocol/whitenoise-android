package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Guards that a notification racing local conversation deletion cannot recreate its named `:conv:` channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationPresenterChannelCleanupRaceTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private lateinit var presenter: LocalNotificationPresenter

    /** Builds a presenter that publishes shortcuts to the platform, with notification permission granted. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.SYSTEM_DEFAULT,
        )
        presenter =
            LocalNotificationPresenter(
                context = context,
                groupReconciliation = {},
                shortcutPublisher = { shortcut -> ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * A hydrated notification that passes the initial post check cannot materialize a named `:conv:`
     * child channel if the conversation is deleted or invalidated before channel routing acquires the lock.
     */
    @Test
    fun localDeletionAfterInitialPostCheckPreventsRoutingFromCreatingConversationChannel() {
        presenter.ensureChannels()
        val ownerPrefs = context.getSharedPreferences("whitenoise.conversation_channel_owners", Context.MODE_PRIVATE)
        ownerPrefs.edit().clear().commit()

        var checks = 0
        val postedAfterRevocation =
            runBlocking {
                presenter.show(
                    update(isMention = false),
                    conversationTitleOverride = "Deleted Secret Group",
                    previewTextOverride = "hi",
                    shortNpub = { "npub1test" },
                    isPostStillAllowed = {
                        checks += 1
                        checks <= 3
                    },
                )
            }
        assertFalse(postedAfterRevocation)
        assertTrue(checks >= 4)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(manager.notificationChannels.none { it.id.contains(":conv:") })
        assertTrue(ownerPrefs.all.isEmpty())

        var cleanupChecks = 0
        val postedAfterChannelCleanup =
            runBlocking {
                presenter.show(
                    update(isMention = false, messageIdHex = "second"),
                    conversationTitleOverride = "Deleted Secret Group",
                    previewTextOverride = "hi",
                    shortNpub = { "npub1test" },
                    isPostStillAllowed = {
                        cleanupChecks += 1
                        if (cleanupChecks == 2) {
                            deleteConversationNotificationChannels(context, "account-a", "group-a")
                        }
                        true
                    },
                )
            }
        assertFalse(postedAfterChannelCleanup)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(manager.notificationChannels.none { it.id.contains(":conv:") })
        assertTrue(ownerPrefs.all.isEmpty())
    }

    /** Builds a group message update for `account-a` / `group-a` with the given [messageIdHex]. */
    private fun update(
        isMention: Boolean,
        messageIdHex: String = "message",
    ) = notificationUpdate(
        accountRef = "account-a",
        isMention = isMention,
        messageIdHex = messageIdHex,
        notificationKey = "key",
        conversationKey = "conversation",
        timestampMs = 1234,
    )
}
