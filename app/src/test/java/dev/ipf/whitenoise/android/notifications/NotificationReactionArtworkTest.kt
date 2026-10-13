package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Reaction rendering exercises the actual presenter and platform payload, including privacy races. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationReactionArtworkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Start each case with an empty tray and enabled previews. */
    @Before
    fun setUp() {
        manager.cancelAll()
        context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Marmot is a picture with readable fallback and description, while all reaction routing stays intact. */
    @Test
    fun marmotArtworkKeepsReactionIdentityAndActions() =
        runBlocking {
            val card = post(":marmot:")
            val expected = LocalNotificationFormatter.reactionDismissalKey("account", "group")
            val active = manager.activeNotifications.single()
            assertEquals(expected.tag, active.tag)
            assertEquals(expected.id, active.id)
            assertEquals(NotificationChannelSpec.REACTIONS.id, card.channelId)
            assertEquals(UserEventNotificationGroup.KEY, card.group)
            assertNotNull(card.contentIntent)
            assertNotNull(card.deleteIntent)
            assertTrue(card.actions.isNullOrEmpty())
            assertPicture(card, ":marmot:")
            assertNoArtwork(card.publicVersion)
        }

    /** Another bundled custom emoji uses its own artwork, including on Android 11. */
    @Test
    @Config(sdk = [30])
    fun whiteNoiseArtworkWorksOnAndroidEleven() =
        runBlocking {
            val card = post(":wn:")
            assertEquals(
                "android.app.Notification\$BigPictureStyle",
                card.extras.getString(Notification.EXTRA_TEMPLATE),
            )
            assertNotNull(card.getLargeIcon())
            assertTrue(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(":wn:"))
        }

    /** Unicode reactions stay ordinary text and never look for custom image files. */
    @Test
    fun unicodeDoesNotPrepareArtworkFromTheQuotedMessage() =
        runBlocking {
            var calls = 0
            val card =
                post(
                    "👍",
                    prepare = {
                        calls++
                        null
                    },
                )
            assertEquals(0, calls)
            assertNoArtwork(card)
            assertTrue(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("👍"))
        }

    /** Missing received artwork cannot borrow a same-named device-local custom image. */
    @Test
    fun receivedCustomEmojiNeverBorrowsLocalArtwork() =
        runBlocking {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val bytes = java.io.ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
            val store = CustomEmojiStore(File(context.filesDir, CustomEmojiStore.DIRECTORY))
            store.save("party", bytes)
            store.save("marmot", bytes)
            try {
                assertNull(notificationReactionEmojiArtwork(context, ":party:"))
                val marmot = requireNotNull(notificationReactionEmojiArtwork(context, ":marmot:"))
                assertFalse(marmot.getPixel(64, 64) == Color.GREEN)
                val card = post(":party:")
                assertNoArtwork(card)
                assertTrue(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(":party:"))
            } finally {
                File(context.filesDir, "emoji/party.png").delete()
                File(context.filesDir, "emoji/marmot.png").delete()
                bitmap.recycle()
            }
        }

    /** Optional preparation errors and missing results both preserve accessible reaction text. */
    @Test
    fun unavailableArtworkKeepsTheReactionReadable() =
        runBlocking {
            val preparers =
                listOf<suspend (String) -> Bitmap?>(
                    { null },
                    { throw IllegalStateException("image unavailable") },
                )
            for (prepare in preparers) {
                val card = post(":marmot:", prepare = prepare)
                assertNoArtwork(card)
                assertTrue(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(":marmot:"))
            }
        }

    /** App-lock redaction and the persisted preview opt-out skip preparation entirely. */
    @Test
    fun hiddenPreviewsNeverPrepareOrExposeArtwork() =
        runBlocking {
            var calls = 0
            val prepare: suspend (String) -> Bitmap? = {
                calls++
                null
            }
            assertNoArtwork(post(":marmot:", redacted = true, prepare = prepare))
            assertEquals(0, calls)
            NotificationPreviewPreferences.setEnabled(context, false, scrub = { true })
            val card = post(":marmot:", prepare = prepare)
            assertEquals(0, calls)
            assertNoArtwork(card)
            assertFalse(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(":marmot:"))
        }

    /** An opt-out that wins while preparation is suspended scrubs the picture, thumbnail and description. */
    @Test
    fun previewsDisabledDuringPreparationCannotLeakArtwork() =
        runBlocking {
            val card =
                post(":marmot:", prepare = {
                    val image = notificationReactionEmojiArtwork(context, it)
                    NotificationPreviewPreferences.setEnabled(context, false, scrub = { true })
                    image
                })
            assertNoArtwork(card)
            assertNoArtwork(card.publicVersion)
            assertFalse(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(":marmot:"))
        }

    /** Already posted picture cards use the existing allowlisted redactor, with routing unchanged. */
    @Test
    fun activePictureCanBeRedactedWithoutChangingRouting() =
        runBlocking {
            val card = post(":marmot:")
            val hidden = notificationWithoutPreview(context, card)
            assertNoArtwork(hidden)
            assertEquals(card.channelId, hidden.channelId)
            assertEquals(card.group, hidden.group)
            assertEquals(card.contentIntent, hidden.contentIntent)
            assertEquals(card.deleteIntent, hidden.deleteIntent)
            assertTrue(hidden.actions.isNullOrEmpty())
        }

    /** Dismissal ownership is captured before image I/O, preventing a late reaction from replaying. */
    @Test
    fun dismissalDuringPreparationRejectsThePost() =
        runBlocking {
            val key = LocalNotificationFormatter.reactionDismissalKey("account", "group")
            val presenter = presenter {
                ConversationCardPostSynchronizer.markDismissed(key.tag, key.id)
                notificationReactionEmojiArtwork(context, it)
            }
            presenter.ensureChannels()
            assertFalse(presenter.show(reactionArtworkUpdate(":marmot:"), shortNpub = { "npub1fixture" }))
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** A timeout discards and releases even a non-cancellable result; cancellation remains cancellation. */
    @Test
    fun timeoutAndCancellationKeepTheirDistinctSemantics() =
        runBlocking {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            assertNull(
                prepareNotificationReactionArtwork(
                    prepare = { withContext(NonCancellable) { delay(40); bitmap } },
                    shortcode = ":marmot:",
                    timeoutMs = 5,
                ),
            )
            assertTrue(bitmap.isRecycled)
            var cancelled = false
            try {
                post(":marmot:", prepare = { throw CancellationException("caller cancelled") })
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** Only a whole reaction shortcode qualifies, never a shortcode inside its target preview. */
    @Test
    fun formatterQualifiesOnlyTheExactReactionToken() {
        assertEquals(":marmot:", LocalNotificationFormatter.reactionArtworkShortcode(reactionArtworkUpdate(":marmot:")))
        assertNull(LocalNotificationFormatter.reactionArtworkShortcode(reactionArtworkUpdate("👍")))
        assertNull(LocalNotificationFormatter.reactionArtworkShortcode(reactionArtworkUpdate("hello :marmot:")))
        assertNull(LocalNotificationFormatter.reactionArtworkShortcode(reactionArtworkUpdate("`:marmot:`")))
    }

    /** Inject optional failures without replacing the real platform posting path. */
    private fun presenter(prepare: suspend (String) -> Bitmap?): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            enrichmentLauncher = {},
            emojiArtworkTimeoutMs = 5_000L,
            reactionArtworkPreparer = prepare,
        )

    /** Return the actual card written by the presenter. */
    private suspend fun post(
        emoji: String,
        redacted: Boolean = false,
        prepare: suspend (String) -> Bitmap? = { notificationReactionEmojiArtwork(context, it) },
    ): Notification {
        manager.cancelAll()
        val presenter = presenter(prepare)
        presenter.ensureChannels()
        assertTrue(
            presenter.show(reactionArtworkUpdate(emoji), redactContent = redacted, shortNpub = { "npub1fixture" }),
        )
        return manager.activeNotifications.single().notification
    }

    /** Verify artwork and every textual representation survive the platform round trip. */
    private fun assertPicture(card: Notification, shortcode: String) {
        assertEquals("android.app.Notification\$BigPictureStyle", card.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNotNull(card.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertNotNull(card.getLargeIcon())
        val textKeys =
            listOf(
                Notification.EXTRA_TEXT,
                Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION,
            )
        for (key in textKeys) {
            assertTrue(card.extras.getCharSequence(key).toString().contains(shortcode))
        }
    }

    /** No private artwork may remain in hidden or public payloads. */
    private fun assertNoArtwork(card: Notification) {
        assertNull(card.getLargeIcon())
        assertFalse(card.extras.containsKey(Notification.EXTRA_PICTURE))
        assertFalse(card.extras.containsKey(Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION))
        assertFalse(card.extras.containsKey(Notification.EXTRA_PICTURE_ICON))
    }
}

/** A kind-7 fixture whose quoted target contains another shortcode to catch accidental preview resolution. */
internal fun reactionArtworkUpdate(emoji: String): NotificationUpdateFfi {
    val user = NotificationUserFfi("1".repeat(64), "Alice", null)
    return NotificationUpdateFfi(
        notificationKey = "reaction-artwork",
        conversationKey = "conversation",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = "account",
        accountIdHex = "2".repeat(64),
        groupIdHex = "group",
        groupName = "Team",
        isDm = false,
        messageIdHex = "3".repeat(64),
        sender = user,
        receiver = user.copy(accountIdHex = "2".repeat(64), displayName = "Me"),
        previewText = emoji,
        timestampMs = 0L,
        isFromSelf = false,
        isMention = false,
        reactionEmoji = emoji,
        reactedToPreview = "Hello :wn:",
    )
}
