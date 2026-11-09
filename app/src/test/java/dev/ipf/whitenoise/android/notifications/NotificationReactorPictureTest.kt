package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.marmotkit.NotificationUpdateFfi
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

/** #3635: a reaction card shows who reacted, first from a ready picture or their monogram, then from a late picture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationReactorPictureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Starts each case with an empty tray, enabled previews and no recorded card writes. */
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

    /** The native row carries one reaction with the reactor's picture and its own shortcut and channel. */
    @Test
    fun reactionCardShowsTheReactorsReadyPicture() =
        runBlocking {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()
            val presenter = presenter(shortcutPublisher = { shortcuts.add(it) })
            val update = reaction("👍")
            assertTrue(show(presenter, update, picture = solid(Color.RED)))

            val card = liveCard()
            val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))
            val line = style.messages.single()
            val shortcut = shortcuts.single()
            assertEquals(content(update).body, line.text.toString())
            assertEquals(update.sender.accountIdHex, line.person?.key)
            val avatar = line.person?.icon?.loadDrawable(context)
            assertEquals(Color.RED, avatar?.toBitmap()?.getPixel(0, 0))
            assertEquals(content(update).title, line.person?.name)
            assertEquals(content(update).title, shortcut.longLabel)
            assertNull(style.conversationTitle)
            assertFalse(style.isGroupConversation)
            assertEquals(shortcut.id, card.shortcutId)
            assertEquals(reactionConversationShortcutId(update.accountRef, update.groupIdHex), shortcut.id)
            assertTrue(shortcut.categories.isNullOrEmpty())
            assertEquals(NotificationChannelSpec.REACTIONS.id, card.channelId)
            assertTrue(card.actions.isNullOrEmpty())
            assertNull(card.getLargeIcon())
            assertNotNull("a late picture can still redraw it", card.recordedIdentityInputs())
        }

    /**
     * A reactor with a private contact picture is shown with it, as on their group rows, and the card keeps its
     * reaction artwork through the final write that applies it.
     */
    @Test
    fun reactionCardShowsTheReactorsPrivatePictureAndKeepsItsArtwork() =
        runBlocking {
            val update = reaction(":marmot:")
            val reactor = update.sender.accountIdHex
            val presenter = presenter(contactAvatar = { _, contact -> (contact == reactor) to solid(Color.GREEN) })
            assertTrue(show(presenter, update, picture = solid(Color.RED)))

            val card = liveCard()
            assertEquals("the private picture", Color.GREEN, largeIconPixel(card))
            assertNotNull("the artwork survives", card.contentView)
            assertNotNull(card.bigContentView)
            assertEquals(NotificationChannelSpec.REACTIONS.id, card.channelId)
        }

    /** Without a picture in hand, the reaction card shows the reactor's monogram. */
    @Test
    fun reactionCardWithoutAPictureShowsTheReactorsMonogram() =
        runBlocking {
            val presenter = presenter()
            val update = reaction("👍")
            assertTrue(show(presenter, update))

            val content = content(update)
            val monogram = notificationMonogramBitmap(content.senderName, content.senderKey).getPixel(0, 0)
            assertEquals(monogram, largeIconPixel(liveCard()))
        }

    /** A redacted reaction card shows no picture of anyone. */
    @Test
    fun redactedReactionCardShowsNoPicture() =
        runBlocking {
            val presenter = presenter()
            assertTrue(show(presenter, reaction("👍"), picture = solid(Color.RED), redacted = true))

            val card = liveCard()
            assertNull(card.getLargeIcon())
            assertNull(card.recordedIdentityInputs())
        }

    /** A late picture rewrites the reaction card once, silently, and keeps its custom reaction artwork. */
    @Test
    fun latePictureRewritesTheReactionCardAndKeepsItsArtwork() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val fetched = mutableListOf<String>()
            val presenter =
                presenter(posts) { url -> url?.also { fetched += it }?.let { solid(Color.MAGENTA) } }
            val update = reaction(":marmot:")
            assertTrue(show(presenter, update))
            assertNotNull("the artwork is drawn", liveCard().contentView)
            val before = posts.size

            val refreshed =
                presenter.refreshLateIdentityImage(
                    update = update,
                    content = content(update),
                    pictures =
                        NotificationIdentityPictureSources(
                            null,
                            null,
                            null,
                            PICTURE_URL,
                            conversationLoader = {
                                fetched += "group photo"
                                solid(Color.BLUE)
                            },
                        ),
                    directShareEligible = false,
                    isRefreshCurrent = { true },
                )

            assertTrue(refreshed)
            assertEquals(listOf(PICTURE_URL), fetched)
            assertEquals(before + 1, posts.size)
            val card = liveCard()
            assertEquals(Color.MAGENTA, largeIconPixel(card))
            assertNotNull("the artwork survives the rewrite", card.contentView)
            assertNotNull(card.bigContentView)
            assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(NotificationChannelSpec.REACTIONS.id, card.channelId)
        }

    /** A group photo never rewrites a reaction card, whose only picture belongs to the actor. */
    @Test
    fun lateGroupPhotoLeavesTheReactionCardAlone() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val shortcuts = mutableListOf<ShortcutInfoCompat>()
            val presenter = presenter(posts, shortcutPublisher = { shortcuts.add(it) })
            val update = reaction("👍")
            val actorPicture = solid(Color.RED)
            var groupPictureRequested = false
            assertTrue(show(presenter, update, picture = actorPicture))

            assertFalse(
                presenter.refreshLateIdentityImage(
                    update,
                    content(update),
                    NotificationIdentityPictureSources(
                        null,
                        null,
                        actorPicture,
                        null,
                        conversationLoader = {
                            groupPictureRequested = true
                            solid(Color.BLUE)
                        },
                    ),
                    directShareEligible = true,
                    isRefreshCurrent = { true },
                ),
            )

            assertFalse(groupPictureRequested)
            assertEquals(1, posts.size)
            assertEquals(1, shortcuts.size)
        }

    /** A late picture never reaches a reaction card that was dismissed meanwhile. */
    @Test
    fun latePictureLeavesAGoneReactionCardAlone() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts) { url -> url?.let { solid(Color.MAGENTA) } }
            val update = reaction("👍")
            assertTrue(show(presenter, update))
            manager.cancelAll()

            val refreshed =
                presenter.refreshLateIdentityImage(
                    update = update,
                    content = content(update),
                    pictures = NotificationIdentityPictureSources(null, null, null, PICTURE_URL),
                    directShareEligible = false,
                    isRefreshCurrent = { true },
                )

            assertFalse(refreshed)
            assertEquals(1, posts.size)
        }

    /** Builds a presenter whose posts, late picture resolution and private contact pictures the scenario controls. */
    private fun presenter(
        posts: MutableList<Notification> = mutableListOf(),
        contactAvatar: (String, String) -> Pair<Boolean, Bitmap?> = { _, _ -> false to null },
        shortcutPublisher: (ShortcutInfoCompat) -> Unit = { error("reaction shortcut unavailable") },
        resolver: suspend (String?) -> Bitmap? = { null },
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context,
            shortcutPublisher = shortcutPublisher,
            groupReconciliation = {},
            enrichmentLauncher = {},
            emojiArtworkTimeoutMs = 5_000L,
            notificationPoster = { target, tag, id, card ->
                posts += card
                target.notify(tag, id, card)
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = resolver,
            contactAvatarOverride = contactAvatar,
        ).also { it.ensureChannels() }

    /** Posts one reaction with an optional ready reactor picture. */
    private suspend fun show(
        presenter: LocalNotificationPresenter,
        update: NotificationUpdateFfi,
        picture: Bitmap? = null,
        redacted: Boolean = false,
    ): Boolean =
        presenter.show(
            update,
            senderAvatarBitmap = picture,
            redactContent = redacted,
            shortNpub = { "npub1fixture" },
        )

    /** The presentation content the presenter formats for [update]. */
    private fun content(update: NotificationUpdateFfi): LocalNotificationContent =
        checkNotNull(LocalNotificationFormatter.content(update, context, shortNpub = { "npub1fixture" }))

    /** A reaction fixture whose quoted message is plain text. */
    private fun reaction(emoji: String) = reactionArtworkUpdate(emoji).copy(reactedToPreview = "Hello")

    /** The only live reaction card, ignoring the group summary. */
    private fun liveCard(): Notification =
        manager.activeNotifications
            .single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .notification

    /** The top-left pixel of the card's picture. */
    private fun largeIconPixel(card: Notification): Int =
        checkNotNull(card.getLargeIcon()?.loadDrawable(context)) { "the card has no picture" }
            .toBitmap()
            .getPixel(0, 0)

    /** A small single-color bitmap whose pixels identify which picture reached the card. */
    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    private companion object {
        const val PICTURE_PX = 8
        const val PICTURE_URL = "https://example.com/alice.png"
    }
}
