package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Card-level coverage for removing deleted or hidden content from shown conversation cards (#3323): newest and
 * older entries, long first messages, reaction quotes, identity-less entries, other accounts and the silent rewrite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationDeletedContentTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val reconciliations = AtomicInteger(0)
    private val presenter =
        LocalNotificationPresenter(context, groupReconciliation = { reconciliations.incrementAndGet() })

    /** Grants posting and starts each case from an empty tray. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        presenter.ensureChannels()
    }

    /** Clears every fixture card. */
    @After
    fun tearDown() {
        manager.cancelAll()
    }

    /** Deleting an older entry keeps the newer valid sibling, and no payload field still carries the removed text. */
    @Test
    fun deletingAnOlderEntryKeepsTheNewerSiblingWithoutItsText() {
        post(message("old-id", "older secret text"))
        post(message("new-id", "newer valid text"))
        val originalActions = actionTargets(requireNotNull(card(ACCOUNT)))
        assertEquals(
            setOf(NotificationActionKind.REPLY, NotificationActionKind.MARK_READ),
            originalActions.map { it.kind }.toSet(),
        )
        assertTrue(originalActions.all { it.target.messageIdHex == "new-id" })

        val reconciled = presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "old-id" }
        assertTrue(reconciled)

        val card = requireNotNull(card(ACCOUNT))
        assertEquals(listOf("newer valid text"), messageTexts(card))
        assertFalse(allText(card.extras).any { it.contains("older secret text") })
        assertEquals("new-id", card.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
        assertEquals(originalActions, actionTargets(card))
    }

    /** Deleting the newest entry keeps its older sibling while removing every action bound to the removed message. */
    @Test
    fun deletingTheNewestEntryKeepsTheOlderValidEntry() {
        post(message("old-id", "older valid text"))
        post(message("new-id", "newest secret text"))
        val originalActions = actionTargets(requireNotNull(card(ACCOUNT)))
        assertEquals(
            setOf(NotificationActionKind.REPLY, NotificationActionKind.MARK_READ),
            originalActions.map { it.kind }.toSet(),
        )
        assertTrue(originalActions.all { it.target.messageIdHex == "new-id" })

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != null && id != "new-id" }

        val card = requireNotNull(card(ACCOUNT))
        assertEquals(listOf("older valid text"), messageTexts(card))
        assertFalse(allText(card.extras).any { it.contains("newest secret text") })
        assertEquals("old-id", card.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
        assertTrue(card.actions.isNullOrEmpty())
    }

    /** A fresh incoming message supplies current actions after a reconciliation removed stale ones. */
    @Test
    fun aNewMessageRestoresActionsAfterTheNewestEntryWasRemoved() {
        post(message("old-id", "older valid text"))
        post(message("removed-id", "newest secret text"))
        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "removed-id" }
        assertTrue(requireNotNull(card(ACCOUNT)).actions.isNullOrEmpty())

        post(message("fresh-id", "fresh valid text"))

        val card = requireNotNull(card(ACCOUNT))
        assertEquals(listOf("older valid text", "fresh valid text"), messageTexts(card))
        val actions = actionTargets(card)
        assertEquals(
            setOf(NotificationActionKind.REPLY, NotificationActionKind.MARK_READ),
            actions.map { it.kind }.toSet(),
        )
        assertTrue(actions.all { it.target.messageIdHex == "fresh-id" })
    }

    /** The rewrite never alerts again and asks the group reconciler to release artwork no card references. */
    @Test
    fun rewriteIsSilentAndReleasesUnreferencedArtwork() {
        post(message("old-id", "older secret text"))
        post(message("new-id", "newer valid text"))
        val before = reconciliations.get()

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "old-id" }

        val card = requireNotNull(card(ACCOUNT))
        assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue("artwork cleanup must run after the rewrite", reconciliations.get() > before)
    }

    /** A silent partial rewrite remains eligible for a captured cleanup even before the platform lists it. */
    @Test
    fun aSilentRewritePreservesTheCapturedDismissalBoundary() {
        post(message("old-id", "older secret text"))
        post(message("new-id", "newer valid text"))
        val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, GROUP)
        val live = manager.activeNotifications.single { it.tag == key.tag && it.id == key.id }
        val original =
            StatusBarNotification(
                context.packageName,
                context.packageName,
                key.id,
                key.tag,
                1_000,
                0,
                0,
                live.notification,
                Process.myUserHandle(),
                1_000L,
            )
        val rewriter = LocalNotificationPresenter(context, activeNotificationsProvider = { arrayOf(original) })
        val cleanup =
            LocalNotificationPresenter(context, nowMillis = { 2_000L }, activeNotificationsProvider = { emptyArray() })
        val request = requireNotNull(cleanup.captureConversationDismissal(ACCOUNT, GROUP))

        assertTrue(rewriter.reconcileConversationContent(ACCOUNT, GROUP, false) { id, _ -> id != "old-id" })
        assertEquals(listOf("newer valid text"), messageTexts(requireNotNull(card(ACCOUNT))))
        assertTrue(runBlocking { cleanup.dismissConversationMessages(request) })

        assertNull("a rewrite is not a newer message beyond the captured cutoff", card(ACCOUNT))
    }

    /** A card whose only entry is deleted is cancelled, as is a long first message shown as expandable text. */
    @Test
    fun aCardLeftWithNothingIsCancelled() {
        post(message("long-id", "x".repeat(LONG_BODY_CODE_POINTS)))
        requireNotNull(card(ACCOUNT))

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "long-id" }

        assertNull(card(ACCOUNT))
    }

    /** A long first message that is still valid is left exactly as it was. */
    @Test
    fun aValidLongFirstMessageIsUnchanged() {
        post(message("long-id", "y".repeat(LONG_BODY_CODE_POINTS)))
        val posted = requireNotNull(card(ACCOUNT)).`when`

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "other-id" }

        assertEquals(posted, requireNotNull(card(ACCOUNT)).`when`)
    }

    /** Another account's card for the same conversation, and the membership card, are untouched. */
    @Test
    fun anotherAccountsCardStaysIntact() {
        post(message("a-id", "account a secret"))
        post(message("b-id", "account b text", account = OTHER_ACCOUNT))

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "a-id" }

        assertNull(card(ACCOUNT))
        assertEquals(listOf("account b text"), messageTexts(requireNotNull(card(OTHER_ACCOUNT))))
    }

    /** Plain cards stamped with the deleted message leave only for the target account and conversation. */
    @Test
    fun plainCardsLeaveOnlyForTheTargetAccountAndConversation() {
        val target = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, GROUP)
        val otherAccount = LocalNotificationFormatter.conversationDismissalKey(OTHER_ACCOUNT, GROUP)
        val otherGroup = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, "ef56")
        val stamped =
            Bundle().apply { putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, "deleted") }
        val channel = NotificationChannel(PLAIN_CHANNEL, "Plain", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        listOf(target, otherAccount, otherGroup).forEach { key ->
            val card =
                NotificationCompat
                    .Builder(context, PLAIN_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Plain")
                    .addExtras(stamped)
                    .build()
            manager.notify(key.tag, key.id, card)
        }

        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "deleted" })

        val remaining = manager.activeNotifications.map { it.tag to it.id }
        assertFalse(target.tag to target.id in remaining)
        assertTrue(otherAccount.tag to otherAccount.id in remaining)
        assertTrue(otherGroup.tag to otherGroup.id in remaining)
    }

    /** A reaction card quotes a target it does not identify, so a deletion in its conversation cancels it. */
    @Test
    fun reactionCardQuotingAnUnidentifiedTargetIsCancelled() {
        post(message("reaction-id", "", reaction = "👍", quoted = "quoted secret text"))
        val key = LocalNotificationFormatter.reactionDismissalKey(ACCOUNT, GROUP)
        assertTrue(manager.activeNotifications.any { it.tag == key.tag && it.id == key.id })

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "deleted-id" }

        assertFalse(manager.activeNotifications.any { it.tag == key.tag && it.id == key.id })
    }

    /** An entry carrying no identity cannot be verified, so it is dropped while identified entries stay. */
    @Test
    fun anEntryWithoutIdentityIsDropped() {
        post(message(null, "unidentified text"))
        post(message("new-id", "identified text"))

        presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != null }

        assertEquals(listOf("identified text"), messageTexts(requireNotNull(card(ACCOUNT))))
    }

    /** Every reconciled card lists each retained entry's own message id for restart reconciliation. */
    @Test
    fun activeCardsListTheirEntryIdentities() {
        post(message("old-id", "older"))
        post(message("new-id", "newer"))

        val card = presenter.activeConversationCards().single { it.isMessageCard }

        assertEquals(listOf("old-id", "new-id"), card.entryMessageIds)
        assertEquals("new-id", card.messageIdHex)
    }

    /** Posts one update through the production presenter. */
    private fun post(update: NotificationUpdateFfi) {
        assertTrue(runBlocking { presenter.show(update, shortNpub = { "npub1sender" }) })
    }

    /** The account's conversation message card, if shown. */
    private fun card(account: String): Notification? {
        val key = LocalNotificationFormatter.conversationDismissalKey(account, GROUP)
        return manager.activeNotifications.firstOrNull { it.tag == key.tag && it.id == key.id }?.notification
    }

    /** The visible MessagingStyle entries of [card]. */
    private fun messageTexts(card: Notification): List<String> =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(card)
            ?.messages
            .orEmpty()
            .map { it.text.toString() }

    /** Parses the actual PendingIntent payloads so action assertions cover their message targets. */
    private fun actionTargets(card: Notification): List<NotificationAction> =
        card.actions.orEmpty().map { action ->
            requireNotNull(NotificationActions.parse(Shadows.shadowOf(action.actionIntent).savedIntent))
        }

    /** Every string anywhere in [bundle], including nested message bundles. */
    private fun allText(bundle: Bundle?): List<String> =
        bundle?.keySet().orEmpty().flatMap { key ->
            @Suppress("DEPRECATION")
            when (val value = bundle?.get(key)) {
                is CharSequence -> listOf(value.toString())
                is Bundle -> allText(value)
                is Array<*> -> value.flatMap { item -> if (item is Bundle) allText(item) else listOf(item.toString()) }
                is Parcelable -> listOf(value.toString())
                else -> listOf(value.toString())
            }
        }

    /** One incoming message, or a reaction quoting [quoted] when [reaction] is set. */
    private fun message(
        id: String?,
        text: String,
        account: String = ACCOUNT,
        reaction: String? = null,
        quoted: String? = null,
    ) = NotificationUpdateFfi(
        notificationKey = "key:$account:$id",
        conversationKey = "conversation:$account",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = account,
        accountIdHex = account,
        groupIdHex = GROUP,
        groupName = "General",
        isDm = false,
        isMention = false,
        messageIdHex = id,
        sender = NotificationUserFfi(accountIdHex = SENDER, displayName = "Alice", pictureUrl = null),
        receiver = NotificationUserFfi(accountIdHex = "self", displayName = "Me", pictureUrl = null),
        previewText = text,
        reactionEmoji = reaction,
        reactedToPreview = quoted,
        timestampMs = 1_000L,
        isFromSelf = false,
    )

    private companion object {
        const val ACCOUNT = "account-a"
        const val OTHER_ACCOUNT = "account-b"
        const val GROUP = "ab12cd34"
        const val SENDER = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val LONG_BODY_CODE_POINTS = 200
        const val PLAIN_CHANNEL = "deleted-content-plain"
    }
}
