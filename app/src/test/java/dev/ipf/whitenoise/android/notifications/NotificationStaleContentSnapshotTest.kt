package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
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

/** Regression fixtures hold Android's snapshot behind accepted production writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationStaleContentSnapshotTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private var visible: Array<StatusBarNotification>? = null
    private var failNextCancel = false
    private var cancelAttempts = 0
    private val writes = mutableListOf<Notification>()
    private val accepted = mutableMapOf<Pair<String, Int>, Notification>()
    private val presenter =
        LocalNotificationPresenter(
            context,
            enrichmentLauncher = {},
            groupReconciliation = {},
            activeNotificationsProvider = { visible ?: it.activeNotifications },
            notificationCanceller = { compat, tag, id ->
                cancelAttempts += 1
                if (failNextCancel) {
                    failNextCancel = false
                    throw IllegalStateException("fixture cancellation failure")
                }
                accepted.remove(tag to id)
                if (visible == null) compat.cancel(tag, id)
            },
            notificationPoster = { compat, tag, id, card ->
                writes += card
                accepted[tag to id] = card
                if (visible == null) compat.notify(tag, id, card)
            },
        )

    /** Every fixture starts with posting allowed and no platform cards. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        presenter.ensureChannels()
    }

    /** Clears the fixture tray. */
    @After
    fun tearDown() {
        manager.cancelAll()
    }

    /** A stale A/B snapshot must not bring A back when C follows its removal. */
    @Test
    fun removedEntryCannotReturnThroughStaleCarriedHistory() {
        post(message("old-id", "older secret text"))
        post(message("new-id", "newer valid text"))
        visible = manager.activeNotifications
        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "old-id" })
        val afterCleanup = writes.size
        post(message("fresh-id", "fresh valid text"))
        assertEquals(listOf("newer valid text", "fresh valid text"), messageTexts(requireNotNull(card(ACCOUNT))))
        writes.drop(afterCleanup).forEach { payload ->
            assertFalse(allText(payload.extras).any { text -> "older secret text" in text })
        }
    }

    /** Read reconciliation uses the same boundary and cannot restore a read older entry. */
    @Test
    fun readEntryCannotReturnThroughStaleCarriedHistory() {
        post(message("read-id", "read secret text"))
        post(message("unread-id", "unread sibling text"))
        visible = manager.activeNotifications
        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, false) { id, _ -> id == "unread-id" })
        post(message("fresh-id", "fresh valid text"))
        assertEquals(listOf("unread sibling text", "fresh valid text"), messageTexts(requireNotNull(card(ACCOUNT))))
        assertFalse(allText(requireNotNull(card(ACCOUNT)).extras).any { "read secret text" in it })
    }

    /** An accepted but platform-absent card must be cancelled, without touching the other account. */
    @Test
    fun absentRecentPostCannotEscapeCleanup() {
        post(message("old-id", "older secret text"))
        post(message("other-id", "other account text", account = OTHER_ACCOUNT))
        manager.cancelAll()
        visible = emptyArray()
        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { _, _ -> false })
        assertNull(card(ACCOUNT))
        assertEquals(listOf("other account text"), messageTexts(requireNotNull(card(OTHER_ACCOUNT))))
    }

    /** A failed cancellation remains pending even while Android continues omitting the accepted card. */
    @Test
    fun absentPostCancellationRetriesAfterTheFirstCancelThrows() {
        post(message("old-id", "older secret text"))
        manager.cancelAll()
        visible = emptyArray()
        failNextCancel = true
        assertFalse(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { _, _ -> false })
        assertEquals(1, cancelAttempts)
        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { _, _ -> false })
        assertEquals(2, cancelAttempts)
        assertNull(card(ACCOUNT))
    }

    /** A full pending registry refuses a new card before writing instead of evicting an unresolved cancel. */
    @Test
    fun pendingCancellationSurvivesCapacityPressure() {
        post(message("old-id", "older secret text"))
        manager.cancelAll()
        visible = emptyArray()
        failNextCancel = true
        assertFalse(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { _, _ -> false })
        try {
            repeat(255) {
                assertTrue(ConversationCardPostedRegistry.prepareCancellation("pending-fixture-$it", 1, true))
            }
            val writtenBefore = writes.size
            assertFalse(
                runBlocking {
                    presenter.show(message("other-id", "other text", OTHER_ACCOUNT), shortNpub = { "npub1sender" })
                },
            )
            assertEquals(writtenBefore, writes.size)
            assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { _, _ -> false })
            assertEquals(2, cancelAttempts)
            assertNull(card(ACCOUNT))
        } finally {
            repeat(255) { ConversationCardPostedRegistry.clearPosted("pending-fixture-$it", 1) }
        }
    }

    /** Successfully cancelling a prior-process card must retire its stale legacy payload before fresh C arrives. */
    @Test
    fun cancelledPreviousProcessCardCannotReturnThroughStaleHistory() {
        post(message("old-id", "older secret text"))
        val original = requireNotNull(card(ACCOUNT))
        val legacy =
            NotificationCompat
                .Builder(context, original)
                .apply {
                    extras.remove("dev.ipf.whitenoise.android.notification.PAYLOAD_REVISION")
                }.build()
        // A different key models a card with no accepted-write metadata in this process.
        val legacyAccount = "previous-process-account"
        val legacyKey = LocalNotificationFormatter.conversationDismissalKey(legacyAccount, GROUP)
        manager.notify(legacyKey.tag, legacyKey.id, legacy)
        accepted[legacyKey.tag to legacyKey.id] = legacy
        visible = manager.activeNotifications
        assertTrue(presenter.reconcileConversationContent(legacyAccount, GROUP, true) { _, _ -> false })
        assertNull(card(legacyAccount))
        // Android's returned snapshot remains the pre-cancel card; no new app-side mark is fabricated.
        post(message("fresh-id", "fresh valid text", account = legacyAccount))
        assertEquals(listOf("fresh valid text"), messageTexts(requireNotNull(card(legacyAccount))))
        assertFalse(allText(requireNotNull(card(legacyAccount)).extras).any { "older secret text" in it })
    }

    /** An older platform generation cannot overwrite a newer accepted sibling during cleanup. */
    @Test
    fun staleCleanupCannotOverwriteANewerSibling() {
        post(message("old-id", "older secret text"))
        post(message("middle-id", "middle valid text"))
        val old = manager.activeNotifications
        post(message("new-id", "newest valid text"))
        visible = old
        assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "old-id" })
        // Android has not supplied the new sibling's payload: cancelling is the safe fallback.
        val current = card(ACCOUNT)
        assertTrue(current == null || messageTexts(current).contains("newest valid text"))
        assertFalse(allText(current?.extras).any { "older secret text" in it })
    }

    /** Same-generation delayed artwork must not restore a pre-cleanup payload. */
    @Test
    fun lateArtworkCannotRestoreAnOlderRemovedEntry() =
        runBlocking {
            post(message("old-id", "older secret text"))
            val update = message("new-id", "newer valid text")
            post(update)
            visible = manager.activeNotifications
            assertTrue(presenter.reconcileConversationContent(ACCOUNT, GROUP, true) { id, _ -> id != "old-id" })
            val afterCleanup = writes.size
            val refreshed =
                presenter.refreshLateIdentityImage(
                    update,
                    requireNotNull(LocalNotificationFormatter.content(update, context, shortNpub = { "npub1sender" })),
                    NotificationIdentityPictureSources(null, null, null, null, conversationLoader = {
                        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    }),
                    directShareEligible = false,
                    isRefreshCurrent = { true },
                )
            assertFalse(refreshed)
            assertEquals(afterCleanup, writes.size)
            assertEquals(listOf("newer valid text"), messageTexts(requireNotNull(card(ACCOUNT))))
        }

    /** Legacy adoption stamps its final redacted payload, so hidden cards keep matching accepted metadata. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun redactedLegacyAdoptionPreservesOnlyItsPayloadRevision() =
        runTest {
            post(message("old-id", "older secret text"))
            val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, GROUP)
            val original = requireNotNull(card(ACCOUNT))
            val extras =
                Bundle(original.extras).apply {
                    remove(UserEventNotificationGroup.EXTRA_CHILD)
                    remove(UserEventNotificationGroup.EXTRA_GENERATION)
                }
            val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            val previous = preferences.getBoolean(NotificationPreviewPreferences.KEY, true)
            preferences.edit().putBoolean(NotificationPreviewPreferences.KEY, false).commit()
            val coordinator =
                NotificationGroupReconciler(
                    context,
                    backgroundScope,
                    NotificationPostPacer(burstCapacity = 20, nowMillis = { 0L }, sleep = {}),
                )
            try {
                // Any grouping observer must see previews disabled before the legacy card becomes visible.
                manager.notify(
                    key.tag,
                    key.id,
                    NotificationCompat
                        .Builder(context, original)
                        .setGroup(null)
                        .setExtras(extras)
                        .build(),
                )
                coordinator.request()
                advanceTimeBy(2_000L)
                runCurrent()
                val adopted = manager.activeNotifications.single { it.tag == key.tag && it.id == key.id }.notification
                assertNotNull(
                    UserEventNotificationGroup.child(
                        manager.activeNotifications.single {
                            it.tag == key.tag && it.id == key.id
                        },
                    ),
                )
                val revision = adopted.extras.getString(EXTRA_PAYLOAD_REVISION)
                assertNotNull(revision)
                assertNotEquals(original.extras.getString(EXTRA_PAYLOAD_REVISION), revision)
                assertTrue(ConversationCardPostedRegistry.isCurrentPayload(key.tag, key.id, revision))
                assertTrue(adopted.extras.getBoolean(EXTRA_CONTENT_REDACTED))
                assertFalse(allText(adopted.extras).any { "older secret text" in it })
            } finally {
                coordinator.close()
                preferences.edit().putBoolean(NotificationPreviewPreferences.KEY, previous).commit()
            }
        }

    /** Posts through the shipped show entry point. */
    private fun post(update: NotificationUpdateFfi) {
        assertTrue(runBlocking { presenter.show(update, shortNpub = { "npub1sender" }) })
    }

    /** Reads the actual accepted payload, independently of the deliberately stale read seam. */
    private fun card(account: String): Notification? {
        val key = LocalNotificationFormatter.conversationDismissalKey(account, GROUP)
        return accepted[key.tag to key.id]
    }

    /** The visible MessagingStyle entries of [card]. */
    private fun messageTexts(card: Notification): List<String> =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(card)
            ?.messages
            .orEmpty()
            .map { it.text.toString() }

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
    }
}
