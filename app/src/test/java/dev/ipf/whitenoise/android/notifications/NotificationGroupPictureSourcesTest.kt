package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.toBitmap
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
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * #3635: where a group card's pictures come from. Each member's row shows that member's own picture, private picture
 * or monogram, and the conversation picture prefers MDK's stored copy of the chat list's picture before any URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationGroupPictureSourcesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Starts every scenario from an empty tray and shortcut inventory with notifications allowed. */
    @Before
    fun setUp() {
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A group sender without a picture in hand shows their monogram, never an empty circle or the group picture. */
    @Test
    fun groupSenderWithoutAPictureShowsTheirMonogram() {
        val presenter = presenter()
        show(presenter, group(), conversation = solid(Color.BLUE))

        val card = liveCard()
        assertEquals(Color.BLUE, identityPixel(card))
        val row = pixel(lastMessagePerson(card))
        assertEquals(255, Color.alpha(row))
        assertFalse("the row is not the group picture", row == Color.BLUE)
    }

    /** A member's private contact picture draws on their row in a group, and never replaces the group picture. */
    @Test
    fun privateContactPictureDrawsOnAGroupSenderRowButNeverTheGroupPicture() {
        val privateReads = AtomicInteger()
        val presenter =
            presenter(
                contactAvatar = { _, _ -> (true to solid(Color.RED)).also { privateReads.incrementAndGet() } },
            )
        show(presenter, group(), conversation = solid(Color.BLUE), sender = solid(Color.GREEN))

        val card = liveCard()
        assertTrue("the sender's private choice is read", privateReads.get() > 0)
        assertEquals(Color.RED, pixel(lastMessagePerson(card)))
        assertEquals(Color.BLUE, identityPixel(card))
    }

    /** One enrichment loads both the group picture and the member's own picture, as one silent rewrite. */
    @Test
    fun coldGroupAndSenderPicturesLoadInOneEnrichment() {
        val posts = mutableListOf<Notification>()
        // Both slots resolve concurrently, so the record must be thread-safe.
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        var enrichment: (suspend () -> Unit)? = null
        val groupUrl = "https://example.com/group.png"
        val presenter =
            presenter(
                posts,
                resolver = { url ->
                    fetched += checkNotNull(url)
                    solid(if (url == groupUrl) Color.BLUE else Color.GREEN)
                },
                launcher = { enrichment = it },
            )
        show(presenter, group(), senderUrl = PICTURE_URL, conversationUrl = groupUrl)
        runBlocking { checkNotNull(enrichment).invoke() }

        assertEquals(setOf(groupUrl, PICTURE_URL), fetched.toSet())
        assertEquals(2, posts.size)
        assertEquals(Color.BLUE, identityPixel(liveCard()))
        assertEquals(Color.GREEN, pixel(lastMessagePerson(liveCard())))
        assertTrue(liveCard().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    /**
     * #3635: the late refresh reads MDK's stored copy of the chat list's picture first, so a picture already on the
     * device reaches the card without the URL or the encrypted download.
     */
    @Test
    fun lateIdentityRefreshPrefersTheStoredChatListPicture() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val fetched = mutableListOf<String>()
            val presenter =
                presenter(posts, resolver = { url -> url?.also { fetched += it }?.let { solid(Color.MAGENTA) } })
            show(presenter, group())
            val groupUrl = "https://example.com/group.png"
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    groupUrl,
                    null,
                    null,
                    conversationLoader = { error("a stored picture needs no download") },
                    storedConversationLoader = { solid(Color.GREEN) },
                )

            assertTrue(sources.hasMissingPicture)
            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertTrue("the group URL is never fetched", fetched.isEmpty())
            assertEquals(Color.GREEN, identityPixel(liveCard()))
        }

    /** With no stored copy, the late refresh still falls back to the group's URL picture, through its loader. */
    @Test
    fun lateIdentityRefreshFallsBackToTheUrlWithoutAStoredPicture() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val groupUrl = "https://example.com/group.png"
            val presenter = presenter(posts, resolver = { error("the late refresh fetches only through loaders") })
            show(presenter, group())
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    groupUrl,
                    null,
                    null,
                    conversationLoader = { solid(Color.BLUE) },
                    storedConversationLoader = { null },
                )

            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertEquals(Color.BLUE, identityPixel(liveCard()))
        }

    /**
     * #3635: when the app lock appears while the late refresh's loaders run, they return nothing, and neither slot
     * then fetches its raw URL behind the lock, nor asks a host that just failed a second time.
     */
    @Test
    fun lateIdentityRefreshFetchesNoUrlAfterItsLoadersStopForTheLock() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val fetched = mutableListOf<String>()
            val presenter =
                presenter(posts, resolver = { url -> url?.also { fetched += it }?.let { solid(Color.RED) } })
            show(presenter, group())
            var locked = false
            val stopsForTheLock: suspend () -> Bitmap? = {
                locked = true
                null
            }
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    "https://example.com/group.png",
                    null,
                    PICTURE_URL,
                    conversationLoader = stopsForTheLock,
                    storedConversationLoader = stopsForTheLock,
                    senderLoader = stopsForTheLock,
                )

            assertFalse(refreshLateIdentity(presenter, group(), sources))

            assertTrue("the loaders ran into the lock", locked)
            assertTrue("no URL was fetched after them", fetched.isEmpty())
            assertEquals("the card was not rewritten", 1, posts.size)
        }

    /**
     * #3635: a direct chat first drawn with the contact's published picture, as on an account whose chat list was not
     * loaded, ends on the stored picture the chat list shows, such as a private contact picture.
     */
    @Test
    fun lateIdentityRefreshReplacesAFallbackWithTheStoredPicture() =
        runBlocking {
            val presenter = presenter(resolver = { error("a shown picture is never fetched again") })
            val published = solid(Color.BLUE)
            show(presenter, dm(), conversation = published, sender = published)
            val sources =
                NotificationIdentityPictureSources(
                    published,
                    PICTURE_URL,
                    published,
                    PICTURE_URL,
                    storedConversationLoader = { solid(Color.GREEN) },
                )

            assertTrue(refreshLateIdentity(presenter, dm(), sources))

            assertEquals("the chat list's picture", Color.GREEN, identityPixel(liveCard()))
        }

    /** A stored picture that is missing or has the shown picture's pixels leaves the card as it was posted. */
    @Test
    fun lateIdentityRefreshKeepsTheShownPictureWithoutADifferentStoredOne() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = { error("a shown picture is never fetched again") })
            val published = solid(Color.BLUE)
            show(presenter, dm(), conversation = published, sender = published)
            val shown = NotificationIdentityPictureSources(published, PICTURE_URL, published, PICTURE_URL)
            val noneStored = shown.copy(storedConversationLoader = { null })
            val samePixels = shown.copy(storedConversationLoader = { solid(Color.BLUE) })

            assertFalse(refreshLateIdentity(presenter, dm(), noneStored))
            assertFalse(refreshLateIdentity(presenter, dm(), samePixels))

            assertEquals("no silent rewrite", 1, posts.size)
            assertEquals(Color.BLUE, identityPixel(liveCard()))
        }

    /**
     * #3635: a member's picture that the short URL fetch misses, such as a cold download, still reaches their row
     * through the late refresh's sender loader, and the group picture stays.
     */
    @Test
    fun lateIdentityRefreshLoadsAColdMemberPicture() =
        runBlocking {
            val presenter = presenter(resolver = { null })
            val picture = solid(Color.BLUE)
            show(presenter, group(), conversation = picture)
            val postedAt = lastMessage(liveCard()).timestamp
            val sources =
                NotificationIdentityPictureSources(
                    picture,
                    null,
                    null,
                    PICTURE_URL,
                    senderLoader = { solid(Color.GREEN) },
                )

            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertEquals("the group picture stays", Color.BLUE, identityPixel(liveCard()))
            assertEquals("the member's row gets their picture", Color.GREEN, pixel(lastMessagePerson(liveCard())))
            assertEquals("and moves so SystemUI draws it again", postedAt + 1, lastMessage(liveCard()).timestamp)
        }

    /**
     * #3635: a direct chat MDK reports as a group records its chat-row peer as the card's contact, so the write shows
     * the peer's private picture, and the shortcut is stamped with the peer for a later Save or Clear.
     */
    @Test
    fun directChatReportedAsAGroupShowsItsPeersPrivatePicture() {
        val peer = group().sender.accountIdHex
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        val presenter =
            presenter(
                contactAvatar = { _, contact -> if (contact == peer) true to solid(Color.RED) else false to null },
                shortcutPublisher = { shortcut ->
                    shortcuts += shortcut
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                },
            )
        show(presenter, group(), conversation = solid(Color.BLUE), identityContact = peer)

        val card = liveCard()
        assertEquals(peer, card.recordedIdentityContact())
        assertEquals(Color.RED, identityPixel(card))
        assertTrue("the shortcut follows the peer's picture", contactPictureOwnsConversationIcon(shortcuts.last()))
    }

    /**
     * #3635: a private picture cleared after the shortcut was published and before the card's write never reaches
     * the card. Production records a Clear as a choice with no private picture, so the write applies it instead of
     * keeping the snapshot. With no public or stored picture in memory, the card draws the peer's monogram.
     */
    @Test
    fun privatePictureClearedBeforeTheWriteNeverReachesTheCard() {
        val peer = group().sender.accountIdHex
        var choice: Pair<Boolean, Bitmap?> = true to solid(Color.RED)
        var shortcutWrites = 0
        val presenter =
            presenter(
                contactAvatar = { _, contact -> if (contact == peer) choice else false to null },
                shortcutPublisher = { shortcut ->
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                    shortcutWrites += 1
                    choice = true to null
                },
            )
        show(presenter, group(), conversation = solid(Color.BLUE), identityContact = peer)

        assertTrue("the Clear landed between the shortcut and the card", shortcutWrites > 0)
        val card = liveCard()
        val (_, seed) = checkNotNull(card.recordedIdentityInputs())
        assertEquals("the write applied the Clear", notificationMonogramBackgroundColor(seed), identityPixel(card))
    }

    /**
     * #3635: a card posted without the chat-row peer, as on an account whose chat list was not loaded, is rewritten
     * by the late refresh to record them, with its pictures unchanged, so the write applies their private picture.
     */
    @Test
    fun lateIdentityRefreshRecordsTheRowsPeerOnACardWithoutOne() =
        runBlocking {
            val peer = group().sender.accountIdHex
            val private = solid(Color.RED)
            val presenter =
                presenter(contactAvatar = { _, contact -> if (contact == peer) true to private else false to null })
            val stored = solid(Color.BLUE)
            show(presenter, group(), conversation = stored)
            assertEquals("a group card records no contact", Color.BLUE, identityPixel(liveCard()))
            val sources = NotificationIdentityPictureSources(stored, null, null, null, identityContact = peer)

            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertEquals(peer, liveCard().recordedIdentityContact())
            assertEquals(Color.RED, identityPixel(liveCard()))
            assertFalse(
                "a card that records the peer is not rewritten again",
                refreshLateIdentity(presenter, group(), sources),
            )
        }

    /** Runs one late identity refresh of the fixture group card with [sources]. */
    private suspend fun refreshLateIdentity(
        presenter: LocalNotificationPresenter,
        update: dev.ipf.marmotkit.NotificationUpdateFfi,
        sources: NotificationIdentityPictureSources,
    ): Boolean =
        presenter.refreshLateIdentityImage(
            update = update,
            content =
                checkNotNull(
                    LocalNotificationFormatter.content(
                        update,
                        context,
                        previewTextOverride = "hi",
                        shortNpub = { "npub1test" },
                    ),
                ),
            pictures = sources,
            directShareEligible = false,
            isRefreshCurrent = { true },
        )

    /** Builds a presenter whose posts, avatar resolution and contact pictures the scenario controls. */
    private fun presenter(
        posts: MutableList<Notification> = mutableListOf(),
        resolver: suspend (String?) -> Bitmap? = { null },
        launcher: (suspend () -> Unit) -> Unit = { error("ready pictures must not launch enrichment") },
        contactAvatar: (String, String) -> Pair<Boolean, Bitmap?> = { _, _ -> false to null },
        shortcutPublisher: (ShortcutInfoCompat) -> Unit = { ShortcutManagerCompat.pushDynamicShortcut(context, it) },
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            groupReconciliation = {},
            shortcutPublisher = shortcutPublisher,
            notificationPoster = { notificationManager, tag, id, notification ->
                posts += notification
                notificationManager.notify(tag, id, notification)
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = resolver,
            enrichmentLauncher = launcher,
            contactAvatarOverride = contactAvatar,
        ).also { it.ensureChannels() }

    /** Posts one message with the given ready pictures, cold picture URLs and chat-row peer. */
    private fun show(
        presenter: LocalNotificationPresenter,
        update: dev.ipf.marmotkit.NotificationUpdateFfi,
        conversation: Bitmap? = null,
        sender: Bitmap? = null,
        senderUrl: String? = null,
        conversationUrl: String? = null,
        identityContact: String? = null,
    ) {
        assertTrue(
            runBlocking {
                presenter.show(
                    update,
                    previewTextOverride = "hi",
                    conversationAvatarBitmap = conversation,
                    conversationAvatarUrl = conversationUrl,
                    senderAvatarBitmap = sender,
                    senderAvatarUrl = senderUrl,
                    identityContact = identityContact,
                    shortNpub = { "npub1test" },
                )
            },
        )
    }

    /** The only live message card, ignoring the group summary. */
    private fun liveCard(): Notification =
        manager.activeNotifications
            .single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .notification

    /** The top-left pixel of the card's primary identity picture. */
    private fun identityPixel(card: Notification): Int {
        val drawable = checkNotNull(card.getLargeIcon()?.loadDrawable(context)) { "the card has no identity picture" }
        return drawable.toBitmap().getPixel(0, 0)
    }

    /** The newest message's sender. */
    private fun lastMessagePerson(card: Notification): Person = checkNotNull(lastMessage(card).person)

    /** The newest message in the card's conversation template. */
    private fun lastMessage(card: Notification): NotificationCompat.MessagingStyle.Message =
        checkNotNull(
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(card)
                ?.messages
                ?.last(),
        )

    /** The top-left pixel of a Person icon. */
    private fun pixel(person: Person): Int = checkNotNull(person.icon?.loadDrawable(context)).toBitmap().getPixel(0, 0)

    /** A group message for the fixture account and conversation. */
    private fun group() = notificationUpdate(isDm = false, notificationKey = KEY, conversationKey = CONVERSATION)

    /** A direct-chat message for the fixture account and conversation. */
    private fun dm() = notificationUpdate(isDm = true, notificationKey = KEY, conversationKey = CONVERSATION)

    /** A small single-color bitmap whose pixels identify which picture reached the card. */
    private fun solid(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private companion object {
        const val KEY = "key"
        const val CONVERSATION = "conv"
        const val PICTURE_PX = 8
        const val PICTURE_URL = "https://example.com/alice.png"
    }
}
