package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private const val BURST_STEP_MS = 2_000L
private const val PAST_BURST_WINDOW_MS = 11_000L
private const val QUIET_SORT_KEY = "2"
private const val ALERTING_SORT_KEY = "1"

/**
 * Pins the alert flags of every same-key write (#2412).
 *
 * SystemUI withdraws a heads-up banner when an update of its card moves a child to GROUP_ALERT_SUMMARY, which
 * AndroidX `setSilent(true)` does, so a write over a live card must keep GROUP_ALERT_CHILDREN and rank and rely
 * on `FLAG_ONLY_ALERT_ONCE` alone. A write with no live card keeps `setSilent` so a swiped card cannot ring twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass") // One probe fixture drives every quiet-write path, so the matrix stays together.
class LocalNotificationHeadsUpFlagsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private lateinit var probe: PostProbe
    private lateinit var presenter: LocalNotificationPresenter

    /** Starts every case with no cards, a fresh probe clock and a presenter wired to the probe's seams. */
    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        probe = PostProbe()
        presenter = newPresenter()
        presenter.ensureChannels()
    }

    /** A second message inside the burst window joins the live card without leaving the alerting group. */
    @Test
    @Config(sdk = [30, 36])
    fun burstSecondMessageKeepsTheLiveCardsHeadsUpEligibilityAndRank() {
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(messageIdHex = "second"), body = "second"))

        val (first, second) = probe.posts
        assertAlerting(first)
        assertQuietOverLiveCard(first, second)
        assertEquals(listOf("hi", "second"), carriedTexts(manager.activeNotifications.single().notification))
    }

    /** Only one write rings: a message past the burst window alerts again, so ring counts stay as they were. */
    @Test
    @Config(sdk = [30, 36])
    fun messageAfterTheBurstWindowAlertsAgainAndRingCountIsUnchanged() {
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(messageIdHex = "second"), body = "second"))
        probe.clock.advanceBy(PAST_BURST_WINDOW_MS)
        assertTrue(show(notificationUpdate(messageIdHex = "third"), body = "third"))

        val (first, second, third) = probe.posts
        assertAlerting(first)
        assertQuietOverLiveCard(first, second)
        assertAlerting(third)
        val ringing = probe.posts.filterNot { it.onlyAlertOnce }.map { it.sequence }
        assertEquals(listOf(first.sequence, third.sequence), ringing)
    }

    /** A quiet first post for a conversation with no card keeps `setSilent`, as the burst policy intends. */
    @Test
    @Config(sdk = [30, 36])
    fun burstPostForAnotherConversationWithNoLiveCardStillUsesSetSilent() {
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(groupIdHex = "group-b", messageIdHex = "other"), body = "other"))

        val other = probe.posts.last()
        assertSilenced(other)
        assertEquals(1, probe.posts.count { !it.onlyAlertOnce })
    }

    /** A card the user swiped away must not be treated as live, or the quiet write could ring a second time. */
    @Test
    @Config(sdk = [30, 36])
    fun burstMessageAfterTheCardWasSwipedAwayStillUsesSetSilent() {
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        val key = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
        manager.cancel(key.tag, key.id)
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(messageIdHex = "second"), body = "second"))

        assertSilenced(probe.posts.last())
    }

    /** A card that joined its group silently never showed a banner, so a later quiet write keeps it silent. */
    @Test
    @Config(sdk = [30, 36])
    fun quietWriteOverACardThatJoinedSilentlyStaysSilent() {
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(groupIdHex = "group-b", messageIdHex = "b-first"), body = "b-first"))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(groupIdHex = "group-b", messageIdHex = "b-second"), body = "b-second"))

        val (_, silentJoin, laterWrite) = probe.posts
        assertSilenced(silentJoin)
        assertSilenced(laterWrite)
        assertEquals(silentJoin.sortKey, laterWrite.sortKey)
    }

    /** The late text correction replaces the message on the live card without re-ranking it or silencing its group. */
    @Test
    @Config(sdk = [30, 36])
    fun lateCorrectionKeepsTheLiveCardsRankAndGroupAlertBehavior() {
        val update = notificationUpdate(messageIdHex = "same-message")
        assertTrue(show(update))
        assertTrue(
            show(
                update,
                body = "corrected",
                senderName = "Alice Corrected",
                silentUpdate = true,
                replaceCurrentMessage = true,
            ),
        )

        val (first, correction) = probe.posts
        assertAlerting(first)
        assertQuietOverLiveCard(first, correction)
        assertEquals(listOf("corrected"), carriedTexts(manager.activeNotifications.single().notification))
    }

    /** Cold avatar enrichment rewrites the live card in place, keeping its group alerting, rank and channel. */
    @Test
    @Config(sdk = [30, 36])
    fun coldAvatarEnrichmentKeepsTheLiveCardsHeadsUpEligibilityAndRank() {
        val avatar = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        presenter =
            LocalNotificationPresenter(
                context = context,
                groupReconciliation = {},
                nowMillis = probe.nowMillis,
                notificationPoster = probe.notificationPoster,
                cachedAvatarBitmap = { null },
                avatarBitmapResolver = { avatar },
                enrichmentLauncher = probe.enrichmentLauncher,
            )
        presenter.ensureChannels()

        runBlocking {
            presenter.show(
                notificationUpdate(),
                previewTextOverride = "hi",
                senderAvatarUrl = "https://example.com/alice.png",
                shortNpub = { "npub1test" },
            )
            assertEquals(1, probe.enrichment.releaseAll())
        }

        val (first, enriched) = probe.posts
        assertAlerting(first)
        assertQuietOverLiveCard(first, enriched)
    }

    /** The invite identity refresh passes `silentUpdate` without `replaceCurrentMessage`, yet must not end a banner. */
    @Test
    @Config(sdk = [30, 36])
    fun inviteIdentityRefreshKeepsTheLiveInvitesHeadsUpEligibilityAndRank() {
        val invite = groupInviteUpdate()
        assertTrue(show(invite))
        assertTrue(show(invite, senderName = "Alice Resolved", silentUpdate = true))

        val (first, refresh) = probe.posts
        assertAlerting(first)
        assertEquals(invite.notificationKey, first.tag)
        assertEquals(LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID, first.id)
        assertQuietOverLiveCard(first, refresh)
    }

    /** An invite refresh with no live invite card is a fresh quiet write, so it keeps `setSilent`. */
    @Test
    @Config(sdk = [30, 36])
    fun inviteIdentityRefreshOfASwipedInviteStillUsesSetSilent() {
        val invite = groupInviteUpdate()
        assertTrue(show(invite))
        manager.cancel(invite.notificationKey, LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
        assertTrue(show(invite, senderName = "Alice Resolved", silentUpdate = true))

        assertSilenced(probe.posts.last())
    }

    /** With previews hidden, a quiet write over a live card keeps the generic card alert-eligible and ranked. */
    @Test
    @Config(sdk = [30, 36])
    fun hiddenPreviewRewriteOfALiveCardKeepsHeadsUpEligibilityAndRank() {
        hidePreviews()
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(messageIdHex = "second"), body = "second"))

        val (first, second) = probe.posts
        assertGeneric(first)
        assertGeneric(second)
        assertAlerting(first)
        assertQuietOverLiveCard(first, second)
    }

    /** With previews hidden, a quiet first post with no live card is still silenced, so it cannot ring. */
    @Test
    @Config(sdk = [30, 36])
    fun hiddenPreviewQuietPostWithNoLiveCardStillUsesSetSilent() {
        hidePreviews()
        assertTrue(show(notificationUpdate(messageIdHex = "first")))
        probe.clock.advanceBy(BURST_STEP_MS)
        assertTrue(show(notificationUpdate(groupIdHex = "group-b", messageIdHex = "other"), body = "other"))

        val other = probe.posts.last()
        assertGeneric(other)
        assertSilenced(other)
    }

    /** The generic rewrite copies a live write's only-alert-once flag without also silencing the group. */
    @Test
    fun genericRewriteSeparatesOnlyAlertOnceFromSilent() {
        val onlyAlertOnce = original(silent = false).also { it.flags = it.flags or Notification.FLAG_ONLY_ALERT_ONCE }

        val hidden = notificationWithoutPreview(context, onlyAlertOnce)

        assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, hidden.groupAlertBehavior)
        assertFalse(hidden.wasBuiltSilent())
    }

    /** A silenced original stays silenced through the generic rewrite, which is what keeps a swiped card quiet. */
    @Test
    fun genericRewriteOfASilencedCardStaysSilent() {
        val hidden = notificationWithoutPreview(context, original(silent = true))

        assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(hidden.wasBuiltSilent())
        assertEquals(Notification.GROUP_ALERT_SUMMARY, hidden.groupAlertBehavior)
    }

    /** The preview-toggle scrub and legacy adoption ask for `silent = true` and keep silencing the group. */
    @Test
    fun explicitSilentGenericRewriteStillSilencesTheGroup() {
        val hidden = notificationWithoutPreview(context, original(silent = false), silent = true)

        assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(hidden.wasBuiltSilent())
    }

    /** Builds a presenter whose clock, poster and enrichment launcher are the probe's. */
    private fun newPresenter(): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            groupReconciliation = {},
            nowMillis = probe.nowMillis,
            notificationPoster = probe.notificationPoster,
            enrichmentLauncher = probe.enrichmentLauncher,
        )

    /** Shows one update through the shared presenter and reports whether a card was written. */
    private fun show(
        update: NotificationUpdateFfi,
        body: String? = "hi",
        senderName: String? = null,
        silentUpdate: Boolean = false,
        replaceCurrentMessage: Boolean = false,
    ): Boolean =
        runBlocking {
            presenter.show(
                update,
                senderNameOverride = senderName,
                previewTextOverride = body,
                silentUpdate = silentUpdate,
                replaceCurrentMessage = replaceCurrentMessage,
                shortNpub = { "npub1test" },
            )
        }

    /** Turns the device-wide preview preference off before any card is written. */
    private fun hidePreviews() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NotificationPreviewPreferences.KEY, false)
            .commit()
    }

    /** Builds a child card the way the presenter does, with or without `setSilent`. */
    private fun original(silent: Boolean): Notification =
        NotificationCompat
            .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setGroup(UserEventNotificationGroup.KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSortKey(ALERTING_SORT_KEY)
            .setSilent(silent)
            .build()

    /** The message texts a conversation card carries, oldest first. */
    private fun carriedTexts(card: Notification): List<String> =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))
            .messages
            .map { it.text.toString() }

    /** A ringing first post: no once-only flag, children alert, and the top attention rank. */
    private fun assertAlerting(post: RecordedPost) {
        assertFalse(post.onlyAlertOnce)
        assertFalse(post.silent)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, post.groupAlertBehavior)
        assertEquals(ALERTING_SORT_KEY, post.sortKey)
        assertEquals(UserEventNotificationGroup.KEY, post.group)
        assertTrue(post.carriesNoPerPostSoundOrVibration)
    }

    /** A write over a live card that can show a banner: once-only, never silenced, group and rank unchanged. */
    private fun assertQuietOverLiveCard(
        live: RecordedPost,
        write: RecordedPost,
    ) {
        assertEquals(live.tag, write.tag)
        assertEquals(live.id, write.id)
        assertTrue(write.onlyAlertOnce)
        assertTrue(write.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertFalse(write.silent)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, write.groupAlertBehavior)
        assertEquals(live.group, write.group)
        assertEquals(live.sortKey, write.sortKey)
        assertEquals(live.channelId, write.channelId)
        assertTrue(write.carriesNoPerPostSoundOrVibration)
    }

    /** A write that must not ring: once-only, silenced into the summary-alerting group, ranked last. */
    private fun assertSilenced(post: RecordedPost) {
        assertTrue(post.onlyAlertOnce)
        assertTrue(post.silent)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, post.groupAlertBehavior)
        assertEquals(QUIET_SORT_KEY, post.sortKey)
        assertTrue(post.carriesNoPerPostSoundOrVibration)
    }

    /** The hidden-preview payload shows only the app name, never the conversation's own text. */
    private fun assertGeneric(post: RecordedPost) {
        assertEquals(
            context.getString(R.string.app_name),
            post.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertTrue(post.notification.extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
    }
}
