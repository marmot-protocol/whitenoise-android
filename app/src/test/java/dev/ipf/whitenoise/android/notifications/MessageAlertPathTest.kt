package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * The message alert path for #1717, which stays an OS-channel policy: DM, group and mention cards are
 * routed to their channels, the app adds no sound or vibration of its own, the user's channel audio
 * choices stay authoritative, and existing channel customisations survive re-creation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageAlertPathTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
    private val posted = mutableListOf<Notification>()
    private var now = 1_700_000_000_000L
    private lateinit var presenter: LocalNotificationPresenter

    /** Grants posting, clears every channel and builds a presenter whose writes are recorded. */
    @Before
    fun setUp() {
        manager.cancelAll()
        manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
        posted.clear()
        presenter =
            LocalNotificationPresenter(
                context = context,
                groupReconciliation = {},
                nowMillis = { now },
                alertBudget = NotificationAlertBudget(NotificationCatchUpWindow(clock = { 0L })),
                notificationPoster = { notificationManager, tag, id, notification ->
                    posted += notification
                    notificationManager.notify(tag, id, notification)
                },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** DM and group alerts use their conversation child of the typed parent; a mention uses its own channel. */
    @Test
    fun eachMessageRoutePostsThroughItsOsChannel() =
        runBlocking {
            presenter.ensureChannels()
            val dm = post(dmUpdate("dm-1", groupIdHex = "group-dm"))
            val group = post(groupUpdate("group-1", groupIdHex = "group-g"))
            val mention = post(groupUpdate("mention-1", groupIdHex = "group-m", isMention = true))

            assertChildOf(NotificationChannelSpec.DIRECT_MESSAGES, dm.channelId)
            assertChildOf(NotificationChannelSpec.GROUP_MESSAGES, group.channelId)
            assertEquals(NotificationChannelSpec.MENTIONS.id, mention.channelId)
        }

    /** No message card carries an app-owned sound, vibration or default alert — the channel is the only path. */
    @Test
    fun noMessageCardCarriesAnAppOwnedAlert() =
        runBlocking {
            presenter.ensureChannels()
            post(dmUpdate("dm-1"))
            post(groupUpdate("group-1"))
            post(groupUpdate("mention-1", isMention = true))
            posted.forEach { card -> assertNoAppOwnedAlert(card) }
        }

    /** A fully muted parent stays muted and still in its conversation child, with no app fallback sound. */
    @Test
    fun mutedChannelGetsNoFallbackSound() = assertParentAudioChoiceRespected(vibrate = false)

    /**
     * A vibration-only parent stays soundless in its conversation child, with no app fallback sound, and a
     * parent vibrating with the system default waveform (no explicit pattern) keeps vibrating in its child.
     */
    @Test
    fun vibrationOnlyChannelGetsNoFallbackSound() = assertParentAudioChoiceRespected(vibrate = true)

    /** Posts a DM under a soundless parent and checks the child and card follow the parent's choice. */
    private fun assertParentAudioChoiceRespected(vibrate: Boolean) =
        runBlocking {
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelSpec.DIRECT_MESSAGES.id,
                    "Direct messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(vibrate)
                },
            )
            presenter.ensureChannels()
            val card = post(dmUpdate("dm-1"))
            val child = manager.getNotificationChannel(card.channelId)
            assertEquals(NotificationChannelSpec.DIRECT_MESSAGES.id, child.parentChannelId)
            assertNull("a muted channel must stay muted", child.sound)
            assertEquals(vibrate, child.shouldVibrate())
            assertNoAppOwnedAlert(card)
        }

    /** A channel the user turned down to silent keeps that importance and the card adds no alert of its own. */
    @Test
    fun silentChannelStaysSilentWithoutAppFallback() =
        runBlocking {
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelSpec.MENTIONS.id,
                    "Mentions",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            presenter.ensureChannels()
            val card = post(groupUpdate("mention-quiet", isMention = true))
            assertEquals(NotificationChannelSpec.MENTIONS.id, card.channelId)
            assertEquals(
                NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(NotificationChannelSpec.MENTIONS.id).importance,
            )
            assertNoAppOwnedAlert(card)
        }

    /** A rapid burst rings once: the following cards are written with only-alert-once. */
    @Test
    fun burstAlertsOnceAcrossRoutes() =
        runBlocking {
            presenter.ensureChannels()
            post(dmUpdate("dm-1"))
            now += 1_000
            post(dmUpdate("dm-2"))
            now += 1_000
            post(groupUpdate("group-1"))
            assertEquals(listOf(false, true, true), posted.map { it.isOnlyAlertOnce() })
        }

    /** A user-customised parent and conversation child keep their frozen ids and settings across re-creation. */
    @Test
    fun customisedChannelsSurviveReCreation() =
        runBlocking {
            val sound = Uri.parse("content://test/user-chosen-sound")
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelSpec.GROUP_MESSAGES.id,
                    "Group messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(sound, attributes) },
            )
            presenter.ensureChannels()
            val first = post(groupUpdate("group-1"))
            presenter.ensureChannels()
            NotificationChannels.ensureChannels(context)
            val second = post(groupUpdate("group-2"))

            assertEquals("the conversation child id is frozen", first.channelId, second.channelId)
            assertEquals(sound, manager.getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id).sound)
            assertEquals(sound, manager.getNotificationChannel(second.channelId).sound)
        }

    /** The diagnostic line names roles and the OS-channel policy, never an id or any content. */
    @Test
    fun diagnosticLineIsPrivacySafe() =
        runBlocking {
            presenter.ensureChannels()
            val card = post(dmUpdate("dm-1"))
            val line =
                messageAlertDiagnostic(
                    context,
                    NotificationChannelSpec.DIRECT_MESSAGES,
                    card.channelId,
                    existingCardChannel = null,
                    silentWrite = false,
                ).line()
            assertEquals(
                "alert_path route=messages_dm channel=conversation_child policy=os_channel silent_write=false " +
                    "importance=4 channel_sound=on channel_vibration=on other_media=idle",
                line,
            )
            assertFalse(line.contains(card.channelId))
            assertFalse(line.contains("group-a"))
            assertFalse(line.contains("account-a"))
            assertEquals(
                MessageAlertChannelKind.PARENT,
                messageAlertChannelKind(NotificationChannelSpec.MENTIONS, NotificationChannelSpec.MENTIONS.id, null),
            )
            assertEquals(
                MessageAlertChannelKind.EXISTING_CARD,
                messageAlertChannelKind(NotificationChannelSpec.DIRECT_MESSAGES, card.channelId, card.channelId),
            )
        }

    /** The notification package never plays audio or takes audio focus itself, so no second alert can exist. */
    @Test
    fun notificationCodeOwnsNoAudioPlayback() {
        val dir =
            listOf(
                File("app/src/main/java/dev/ipf/whitenoise/android/notifications"),
                File("src/main/java/dev/ipf/whitenoise/android/notifications"),
            ).first(File::isDirectory)
        val forbidden = listOf("MediaPlayer", "SoundPool", "RingtoneManager", "requestAudioFocus", "ToneGenerator")
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            forbidden.forEach { api -> assertFalse("${file.name} uses $api", source.contains(api)) }
            assertFalse("${file.name} sets a builder sound", Regex("""\.setSound\((?!null)""").containsMatchIn(source))
        }
    }

    /** Posts [update] and returns the card that reached the notification manager. */
    private suspend fun post(update: NotificationUpdateFfi): Notification {
        val before = posted.size
        assertTrue(presenter.show(update, shortNpub = { it }))
        assertEquals(before + 1, posted.size)
        return posted.last()
    }

    /** Asserts [channelId] is a conversation child linked to [parent], never the bare parent. */
    private fun assertChildOf(
        parent: NotificationChannelSpec,
        channelId: String,
    ) {
        assertNotEquals(parent.id, channelId)
        val child = manager.getNotificationChannel(channelId)
        assertEquals(parent.id, child.parentChannelId)
        assertTrue(child.conversationId?.isNotBlank() == true)
    }

    /** Asserts the card adds no sound, vibration or default alert on top of its channel. */
    @Suppress("DEPRECATION") // The legacy per-card fields are exactly what an app-owned alert would set.
    private fun assertNoAppOwnedAlert(card: Notification) {
        assertNull(card.sound)
        assertNull(card.vibrate)
        assertEquals(0, card.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
    }

    /** One synthetic DM message. */
    private fun dmUpdate(
        messageIdHex: String,
        groupIdHex: String = "group-a",
    ) = alertBudgetUpdate(messageIdHex, now, groupIdHex = groupIdHex).copy(isDm = true)

    /** One synthetic group message, optionally mentioning the local user. */
    private fun groupUpdate(
        messageIdHex: String,
        groupIdHex: String = "group-a",
        isMention: Boolean = false,
    ) = alertBudgetUpdate(messageIdHex, now, isMention = isMention, groupIdHex = groupIdHex)
}
