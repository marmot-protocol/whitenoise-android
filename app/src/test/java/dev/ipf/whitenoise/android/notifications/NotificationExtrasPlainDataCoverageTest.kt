package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Invariant: a posted card's `Notification.extras` holds plain data only, never a `RemoteViews`. When Android removes
 * a card (a tap, a swipe, a cancel), `system_server` reads every extras value on its own while it lightens the payload;
 * a nested `RemoteViews` read that way crashed `system_server` on a Pixel 9a (#4253). A custom layout travels only in
 * the content views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationExtrasPlainDataCoverageTest {
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

    /** A card with custom-emoji quick reactions keeps its reaction rows while its extras hold no RemoteViews. */
    @Test
    fun customEmojiReactionCardKeepsRowsWithoutRemoteViewsInExtras() {
        postConversationCard(presenter(), "message-a")
        val live = manager.activeNotifications.single().notification

        assertNotNull("the reaction rows are drawn", live.bigContentView)
        assertEquals(emptyList<String>(), remoteViewsKeys(live.extras))
    }

    /** A rewrite of the posted card, here a handled reaction, keeps the rows and keeps RemoteViews out of extras. */
    @Test
    fun rewriteKeepsTheRowsAndPlainExtras() {
        val presenter = presenter()
        postConversationCard(presenter, "message-a")
        val posted = manager.activeNotifications.single()

        assertEquals(
            true,
            presenter.markReactionHandledIfSameGeneration(posted.tag, posted.id, "message-a", ":marmot:"),
        )

        val live = manager.activeNotifications.single().notification
        assertNotNull("the rewrite keeps the reaction rows", live.bigContentView)
        assertEquals(emptyList<String>(), remoteViewsKeys(live.extras))
    }

    /** A card posted by a build that kept the rows in extras loses that extra on its next write. */
    @Test
    fun aCardFromTheEarlierBuildDropsItsRowsExtraOnTheNextWrite() {
        val presenter = presenter()
        postConversationCard(presenter, "message-a")
        val posted = manager.activeNotifications.single()
        val earlier =
            posted.notification.clone().apply {
                extras.putParcelable(
                    LEGACY_EXTRA_QUICK_REACTION_ROWS,
                    RemoteViews(context.packageName, R.layout.notification_quick_reaction_row),
                )
            }
        manager.notify(posted.tag, posted.id, earlier)

        presenter.markReactionHandledIfSameGeneration(posted.tag, posted.id, "message-a", ":marmot:")

        val live = manager.activeNotifications.single().notification
        assertFalse(live.extras.containsKey(LEGACY_EXTRA_QUICK_REACTION_ROWS))
        assertEquals(emptyList<String>(), remoteViewsKeys(live.extras))
    }

    /** The extras keys, nested bundles included, whose value is a RemoteViews. */
    private fun remoteViewsKeys(
        extras: Bundle,
        prefix: String = "",
    ): List<String> =
        extras.keySet().flatMap { key ->
            when (val value = readAlone(extras, key)) {
                is RemoteViews -> listOf(prefix + key)
                is Bundle -> remoteViewsKeys(value, "$prefix$key/")
                else -> emptyList()
            }
        }

    /** Reads one extras value the way Notification.lightenPayload does. */
    @Suppress("DEPRECATION")
    private fun readAlone(
        extras: Bundle,
        key: String,
    ): Any? = extras.get(key)

    private fun presenter() =
        LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            quickReactionChoices = { listOf(":marmot:", ":wn:", "👍") },
        ).also(LocalNotificationPresenter::ensureChannels)

    private fun postConversationCard(
        presenter: LocalNotificationPresenter,
        messageIdHex: String,
    ) {
        runBlocking {
            presenter.show(update(messageIdHex), previewTextOverride = messageIdHex, shortNpub = { "npub1test" })
        }
        assertNotNull(manager.activeNotifications.singleOrNull())
    }

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
            sender = NotificationUserFfi(accountIdHex = "a".repeat(64), displayName = "Alice", pictureUrl = null),
            receiver = NotificationUserFfi(accountIdHex = "self", displayName = "Me", pictureUrl = null),
            previewText = messageIdHex,
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1234,
            isFromSelf = false,
            sticker = null,
        )
}

/** The extras key the earlier build kept the reaction rows under; cards it posted may still carry it. */
private const val LEGACY_EXTRA_QUICK_REACTION_ROWS = "dev.ipf.whitenoise.android.notification.QUICK_REACTION_ROWS"
