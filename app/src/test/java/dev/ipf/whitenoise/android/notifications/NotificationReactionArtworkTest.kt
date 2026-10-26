package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.app.NotificationManagerCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.runBlocking
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
        ConversationCardPostedRegistry.resetForTests()
        manager.cancelAll()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Marmot replaces its shortcode inline, with accessible fallback and unchanged reaction routing. */
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
            assertEquals(Notification.GROUP_ALERT_CHILDREN, card.groupAlertBehavior)
            val revision = card.extras.getString(EXTRA_PAYLOAD_REVISION)
            assertNotNull(revision)
            assertTrue(ConversationCardPostedRegistry.isCurrentPayload(active.tag, active.id, revision))
            assertNotNull(card.contentIntent)
            assertNotNull(card.deleteIntent)
            assertTrue(card.actions.isNullOrEmpty())
            assertPicture(card, ":marmot:", context.getString(R.string.emoji_name_marmot))
            assertNoArtwork(card.publicVersion)
        }

    /** Another bundled custom emoji uses its own artwork, including on Android 11. */
    @Test
    @Config(sdk = [30])
    fun whiteNoiseArtworkWorksOnAndroidEleven() =
        runBlocking {
            val card = post(":wn:")
            assertEquals(
                "android.app.Notification\$DecoratedCustomViewStyle",
                card.extras.getString(Notification.EXTRA_TEMPLATE),
            )
            assertNotNull(card.contentView)
            assertPicture(card, ":wn:", context.getString(R.string.white_noise))
        }

    /** Reinflate the same posted payload in both directions; only the matching full-color image is visible. */
    @Test
    fun postedBodySelectsCurrentThemeWithoutReposting() =
        runBlocking {
            val card = post(":marmot:")
            for (dark in listOf(false, true, false)) {
                val qualifiers = if (dark) "w360dp-h800dp-night-mdpi" else "w360dp-h800dp-notnight-mdpi"
                RuntimeEnvironment.setQualifiers(qualifiers)
                val root = requireNotNull(card.contentView).apply(context, null)
                val light = root.findViewById<ImageView>(R.id.reaction_notification_body_light)
                val night = root.findViewById<ImageView>(R.id.reaction_notification_body_dark)
                assertEquals(if (dark) View.GONE else View.VISIBLE, light.visibility)
                assertEquals(if (dark) View.VISIBLE else View.GONE, night.visibility)
                val lightBitmap = (light.drawable as BitmapDrawable).bitmap
                val darkBitmap = (night.drawable as BitmapDrawable).bitmap
                assertFalse(lightBitmap.sameAs(darkBitmap))
                assertFalse(lightBitmap.isRecycled)
                assertFalse(darkBitmap.isRecycled)
            }
            assertEquals(1, manager.activeNotifications.size)
        }

    /** Unicode reactions stay ordinary text and never look for custom image files. */
    @Test
    fun unicodeKeepsNativeTextWithoutCustomViews() =
        runBlocking {
            val card = post("👍")
            assertNoArtwork(card)
            assertTrue(
                card.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    .toString()
                    .contains("👍"),
            )
        }

    /** Missing received artwork cannot borrow a same-named device-local custom image. */
    @Test
    fun receivedCustomEmojiNeverBorrowsLocalArtwork() =
        runBlocking {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val bytes =
                java.io.ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
            val store = CustomEmojiStore(File(context.filesDir, CustomEmojiStore.DIRECTORY))
            store.save("party", bytes)
            store.save("marmot", bytes)
            try {
                assertNull(notificationBuiltinEmojiBitmap(context, ":party:"))
                val marmot = requireNotNull(notificationBuiltinEmojiBitmap(context, ":marmot:"))
                assertFalse(marmot.getPixel(64, 64) == Color.GREEN)
                val card = post(":party:")
                assertNoArtwork(card)
                assertTrue(
                    card.extras
                        .getCharSequence(Notification.EXTRA_TEXT)
                        .toString()
                        .contains(":party:"),
                )
            } finally {
                File(context.filesDir, "emoji/party.png").delete()
                File(context.filesDir, "emoji/marmot.png").delete()
                bitmap.recycle()
            }
        }

    /**
     * The plain-text line Android shows for grouped cards names a built-in readably, while only the inline artwork
     * keeps the authored shortcode. Other shortcodes stay exact, and redaction drops the authored copy.
     */
    @Test
    fun reactionTextNamesBuiltinsWhileOnlyArtworkKeepsTheShortcode() {
        val marmot = context.getString(R.string.emoji_name_marmot)
        val content =
            requireNotNull(
                LocalNotificationFormatter.content(reactionArtworkUpdate(":marmot:"), context, shortNpub = { it }),
            )
        assertEquals("reacted $marmot to: \"Hello :wn:\"", content.body)
        assertEquals("reacted :marmot: to: \"Hello :wn:\"", content.reactionArtworkBody)
        val partyUpdate = reactionArtworkUpdate(":party:")
        val party = requireNotNull(LocalNotificationFormatter.content(partyUpdate, context, shortNpub = { it }))
        assertEquals("reacted :party: to: \"Hello :wn:\"", party.body)
        assertNull(LocalNotificationFormatter.redactedContent(content, "White Noise", "hidden").reactionArtworkBody)
    }

    /** Built-in tiles crop canvas padding as the in-app reaction chip does, so the Marmot is not drawn small. */
    @Test
    fun builtinArtworkFillsItsTileLikeTheInAppGlyph() {
        for (code in listOf(":marmot:", ":wn:")) {
            val tile = requireNotNull(notificationBuiltinEmojiBitmap(context, code))
            try {
                val opaque =
                    (0 until tile.height).flatMap { y ->
                        (0 until tile.width).filter { x -> Color.alpha(tile.getPixel(x, y)) > 0 }.map { x -> x to y }
                    }
                val edge = 2
                assertTrue(code, opaque.minOf { it.first } <= edge && opaque.minOf { it.second } <= edge)
                assertTrue(
                    code,
                    opaque.maxOf { it.first } >= tile.width - 1 - edge &&
                        opaque.maxOf { it.second } >= tile.height - 1 - edge,
                )
            } finally {
                tile.recycle()
            }
        }
    }

    /** App-lock redaction and the persisted preview opt-out skip optional artwork entirely. */
    @Test
    fun hiddenPreviewsNeverExposeArtwork() =
        runBlocking {
            assertNoArtwork(post(":marmot:", redacted = true))
            NotificationPreviewPreferences.setEnabled(context, false, scrub = { true })
            val card = post(":marmot:")
            assertNoArtwork(card)
            val hiddenText = card.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertFalse(hiddenText.contains(":marmot:"))
            assertFalse(hiddenText.contains(context.getString(R.string.emoji_name_marmot)))
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

    /** Android image rejection gets one text-only retry with the same reaction identity and routing. */
    @Test
    fun rejectedPictureRetriesReadableTextOnce() =
        runBlocking {
            var attempts = 0
            val card =
                post(
                    ":marmot:",
                    poster = { target, tag, id, notification ->
                        attempts++
                        if (attempts == 1) {
                            assertPicture(notification, ":marmot:", context.getString(R.string.emoji_name_marmot))
                            throw IllegalArgumentException("picture rejected")
                        }
                        assertNoArtwork(notification)
                        target.notify(tag, id, notification)
                    },
                )
            assertEquals(2, attempts)
            assertNoArtwork(card)
            assertEquals(
                "reacted ${context.getString(R.string.emoji_name_marmot)} to: \"Hello\"",
                card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
            )
            assertEquals(NotificationChannelSpec.REACTIONS.id, card.channelId)
            assertEquals(UserEventNotificationGroup.KEY, card.group)
            assertTrue(card.actions.isNullOrEmpty())
        }

    /** A live silent correction may retry after its own cancellation without losing dismissal safeguards. */
    @Test
    fun rejectedPictureCorrectionRetriesAfterItsOwnCancel() =
        runBlocking {
            post(":marmot:")
            var attempts = 0
            val presenter =
                presenter(
                    poster = { target, tag, id, card ->
                        attempts++
                        if (attempts == 1) throw IllegalArgumentException("picture rejected")
                        target.notify(tag, id, card)
                    },
                )
            assertTrue(
                presenter.show(
                    reactionArtworkUpdate(":marmot:"),
                    silentUpdate = true,
                    replaceCurrentMessage = true,
                    shortNpub = { "npub1fixture" },
                ),
            )
            assertEquals(2, attempts)
            assertNoArtwork(manager.activeNotifications.single().notification)
        }

    /** A second platform failure cancels the card; retry cannot become an unbounded loop. */
    @Test
    fun failedTextRetryStopsAfterTwoAttempts() =
        runBlocking {
            var attempts = 0
            val presenter =
                presenter(
                    poster = { _, _, _, _ ->
                        attempts++
                        throw IllegalArgumentException("post rejected")
                    },
                )
            presenter.ensureChannels()
            assertFalse(presenter.show(reactionArtworkUpdate(":marmot:"), shortNpub = { "npub1fixture" }))
            assertEquals(2, attempts)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** Dismissal during a failed platform attempt invalidates the text retry as well. */
    @Test
    fun dismissalAfterPictureFailurePreventsRetry() =
        runBlocking {
            var attempts = 0
            val presenter =
                presenter(
                    poster = { _, tag, id, _ ->
                        attempts++
                        ConversationCardPostSynchronizer.markDismissed(tag, id)
                        throw IllegalArgumentException("picture rejected")
                    },
                )
            presenter.ensureChannels()
            assertFalse(presenter.show(reactionArtworkUpdate(":marmot:"), shortNpub = { "npub1fixture" }))
            assertEquals(1, attempts)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** Inject platform failures while retaining the actual bundled artwork rendering. */
    private fun presenter(
        poster: (NotificationManagerCompat, String, Int, Notification) -> Unit = { target, tag, id, card ->
            target.notify(tag, id, card)
        },
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context,
            groupReconciliation = {},
            enrichmentLauncher = {},
            emojiArtworkTimeoutMs = 5_000L,
            notificationPoster = poster,
        )

    /** Return the actual card written by the presenter. */
    private suspend fun post(
        emoji: String,
        redacted: Boolean = false,
        poster: (NotificationManagerCompat, String, Int, Notification) -> Unit = { target, tag, id, card ->
            target.notify(tag, id, card)
        },
    ): Notification {
        manager.cancelAll()
        val presenter = presenter(poster)
        presenter.ensureChannels()
        assertTrue(
            presenter.show(
                reactionArtworkUpdate(emoji).copy(reactedToPreview = "Hello"),
                redactContent = redacted,
                shortNpub = { "npub1fixture" },
            ),
        )
        return manager.activeNotifications.single().notification
    }

    /** Quoted bundled emoji resolve too, but unavailable and code-literal shortcodes stay authored text. */
    @Test
    fun quotedArtworkUsesOnlySafeBundledImages() {
        val images = reactionBodyArtwork(context, "Hello :wn: :party: `:marmot:`")
        assertEquals(setOf(":wn:"), images.keys)
        images.values.forEach { it.recycle() }
    }

    /** Quoted artwork also gets the bounded text retry when the reaction has no available bitmap. */
    @Test
    fun rejectedQuotedArtworkRetriesForUnicodeAndUnavailableReactions() =
        runBlocking {
            for (emoji in listOf("👍", ":party:")) {
                manager.cancelAll()
                var attempts = 0
                val presenter =
                    presenter(
                        poster = { target, tag, id, card ->
                            attempts++
                            if (card.contentView != null) throw IllegalArgumentException("inline rejected")
                            target.notify(tag, id, card)
                        },
                    )
                presenter.ensureChannels()
                assertTrue(presenter.show(reactionArtworkUpdate(emoji), shortNpub = { "npub1fixture" }))
                assertEquals(2, attempts)
                assertNoArtwork(manager.activeNotifications.single().notification)
            }
        }

    /** Verify artwork survives the platform round trip, while every textual representation names it readably. */
    private fun assertPicture(
        card: Notification,
        shortcode: String,
        name: String,
    ) {
        assertEquals(
            "android.app.Notification\$DecoratedCustomViewStyle",
            card.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertNull(card.getLargeIcon())
        assertFalse(card.extras.containsKey(Notification.EXTRA_PICTURE))
        val root = requireNotNull(card.contentView).apply(context, null)
        val icon = root.findViewById<ImageView>(R.id.reaction_notification_body_light)
        assertNotNull(icon.drawable)
        assertFalse((icon.drawable as BitmapDrawable).bitmap.isRecycled)
        val texts =
            listOfNotNull(
                root.contentDescription,
                card.extras.getCharSequence(Notification.EXTRA_TEXT),
                card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ).map(CharSequence::toString)
        texts.forEach { text ->
            assertTrue(text, text.contains(name))
            assertFalse(text, text.contains(shortcode))
        }
    }

    /** No private artwork may remain in hidden or public payloads. */
    private fun assertNoArtwork(card: Notification) {
        assertNull(card.getLargeIcon())
        assertNull(card.contentView)
        assertNull(card.bigContentView)
        assertNull(card.headsUpContentView)
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
