package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * #3311: the bitmap each rich message card carries in its primary identity slot, across the conversation template,
 * the first long message, accumulated history, late enrichment, private contact pictures and live-card rewrites.
 * Builder extras alone cannot prove SystemUI's rendering, which `NotificationConversationAvatarAndroidTest` covers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationConversationIdentityImageTest {
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

    /** A warm direct chat shows the peer's picture in its first card, not only through a later shortcut. */
    @Test
    fun dmFirstCardCarriesTheWarmPeerPictureAsItsIdentityImage() {
        val posts = mutableListOf<Notification>()
        val presenter = presenter(posts)
        show(presenter, dm(), conversation = solid(Color.RED), sender = solid(Color.RED))

        assertEquals("the first payload already carries the picture", 1, posts.size)
        assertEquals(Color.RED, identityPixel(posts.single()))
        assertEquals(DEFAULT_SENDER, identityExtras(posts.single())?.getString("contact"))
    }

    /** A group card shows its own picture and the sender name without sender photo or initial pixels. */
    @Test
    fun groupCardShowsOnlyTheGroupPictureAndKeepsTheSenderName() {
        val presenter = presenter()
        show(presenter, group(), conversation = solid(Color.BLUE), sender = solid(Color.GREEN))

        val card = liveCard()
        assertEquals(Color.BLUE, identityPixel(card))
        assertEquals(0, Color.alpha(pixel(lastMessagePerson(card))))
        assertEquals(DEFAULT_SENDER, lastMessagePerson(card).key)
        assertNull("a group identity is never a member's private contact", identityExtras(card)?.getString("contact"))
    }

    /** Hidden member pictures do not schedule enrichment, consult private overrides or churn a group shortcut. */
    @Test
    fun readyGroupPictureIgnoresColdAndChangedSenderPictures() {
        val posts = mutableListOf<Notification>()
        val publications = AtomicInteger()
        val privateReads = AtomicInteger()
        val resolverCalls = AtomicInteger()
        val fetched = mutableListOf<String>()
        val presenter =
            presenter(
                posts,
                resolver = { url -> resolverCalls.incrementAndGet(); url?.let { fetched += it; solid(Color.GREEN) } },
                contactAvatar = { _, _ -> privateReads.incrementAndGet(); true to solid(Color.RED) },
                shortcutPublications = publications,
            )
        val picture = solid(Color.BLUE)
        show(presenter, group(), conversation = picture, senderUrl = PICTURE_URL)
        show(presenter, group().copy(messageIdHex = "next"), conversation = picture, senderUrl = "https://example.com/changed.png")

        assertTrue(fetched.isEmpty())
        assertEquals(0, privateReads.get())
        assertEquals(0, resolverCalls.get())
        assertEquals(1, publications.get())
        assertEquals(2, posts.size)
        assertEquals(Color.BLUE, identityPixel(liveCard()))
        assertEquals(DEFAULT_SENDER, lastMessagePerson(liveCard()).key)
        assertEquals(0, Color.alpha(pixel(lastMessagePerson(liveCard()))))
    }

    /** A missing group URL picture still enriches the same card without fetching its member's image. */
    @Test
    fun groupPictureUrlStillLoadsWithoutTheHiddenSenderUrl() {
        val posts = mutableListOf<Notification>()
        val fetched = mutableListOf<String>()
        var enrichment: (suspend () -> Unit)? = null
        val groupUrl = "https://example.com/group.png"
        val presenter =
            presenter(
                posts,
                resolver = { url -> fetched += checkNotNull(url); solid(Color.BLUE) },
                launcher = { enrichment = it },
            )
        show(presenter, group(), senderUrl = PICTURE_URL, conversationUrl = groupUrl)
        runBlocking { checkNotNull(enrichment).invoke() }

        assertEquals(listOf(groupUrl), fetched)
        assertEquals(2, posts.size)
        assertEquals(Color.BLUE, identityPixel(liveCard()))
        assertTrue(liveCard().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, Color.alpha(pixel(lastMessagePerson(liveCard()))))
    }

    /** Missing pictures fall back to the conversation shortcut's monogram, never the app icon or a member's face. */
    @Test
    fun missingPicturesFallBackToTheConversationMonogram() {
        val presenter = presenter()
        show(presenter, group(), conversation = null, sender = solid(Color.GREEN))
        val seed = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        assertEquals(notificationMonogramBackgroundColor(seed), identityPixel(liveCard()))

        manager.cancelAll()
        show(presenter, dm(), conversation = null, sender = null)
        assertEquals(notificationMonogramBackgroundColor(seed), identityPixel(liveCard()))
    }

    /** A long first message and its later history share the same conversation template and picture. */
    @Test
    fun longFirstMessageAndTheFollowingHistoryKeepTheIdentityImage() {
        val presenter = presenter()
        val long = LongBodies.plain(160 * 2)
        val red = solid(Color.RED)
        show(presenter, dm(messageId = "first"), conversation = red, sender = red, body = long)
        val firstCard = liveCard()
        assertNull(firstCard.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(
            long,
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(firstCard)
                ?.messages
                ?.single()
                ?.text
                .toString(),
        )
        assertEquals(Color.RED, identityPixel(firstCard))

        show(presenter, dm(messageId = "second"), conversation = red, sender = red)
        val history = liveCard()
        assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(history))
        assertEquals(Color.RED, identityPixel(history))
    }

    /** A late picture silently updates a long message without changing its conversation template or text. */
    @Test
    fun latePictureReplacesTheMonogramSilentlyOnTheLongConversationCard() {
        val posts = mutableListOf<Notification>()
        var enrichment: (suspend () -> Unit)? = null
        val presenter =
            presenter(
                posts,
                resolver = ::latePicture,
                launcher = { enrichment = it },
            )
        val long = LongBodies.plain(160 * 2)
        show(presenter, dm(), senderUrl = "https://example.com/alice.png", body = long)
        val seed = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        assertEquals(notificationMonogramBackgroundColor(seed), identityPixel(posts.single()))

        runBlocking { checkNotNull(enrichment).invoke() }

        assertEquals(2, posts.size)
        val enriched = posts.last()
        assertEquals(Color.MAGENTA, identityPixel(enriched))
        assertTrue(enriched.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(enriched.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(
            long,
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(enriched)
                ?.messages
                ?.single()
                ?.text
                .toString(),
        )
    }

    /** A dismissal while the picture loads keeps the long-message card dismissed. */
    @Test
    fun dismissedLongConversationCardIsNotResurrectedByALatePicture() {
        val posts = mutableListOf<Notification>()
        var enrichment: (suspend () -> Unit)? = null
        val presenter = presenter(posts, resolver = ::latePicture, launcher = { enrichment = it })
        val long = LongBodies.plain(160 * 2)
        show(presenter, dm(), senderUrl = "https://example.com/alice.png", body = long)
        runBlocking {
            presenter.dismissConversationMessages(ACCOUNT, GROUP)
            checkNotNull(enrichment).invoke()
        }

        assertEquals(1, posts.size)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** A pre-upgrade long-text card retains its body and legacy sender metadata through late image enrichment. */
    @Test
    fun legacyTextCardKeepsBodyAndSenderWhenItsPictureArrives() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = ::latePicture)
            val body = "A retained pre-upgrade message. ".repeat(12).trim()
            show(presenter, dm(), body = body)
            val legacy = postLegacyTextCard(body)
            val before = posts.size

            assertTrue(refreshLateIdentity(presenter, dm()))

            val enriched = liveCard()
            assertEquals(before + 1, posts.size)
            assertEquals(Color.MAGENTA, identityPixel(enriched))
            assertTrue(enriched.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(enriched))
            assertEquals(body, enriched.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            assertEquals(legacy.`when`, enriched.`when`)
            val sender = Person.fromBundle(checkNotNull(enriched.extras.getBundle(LEGACY_SENDER)))
            assertEquals(DEFAULT_SENDER, sender.key)
            assertEquals(Color.MAGENTA, pixel(sender))
            assertEquals(body, enriched.extras.getCharSequence(LEGACY_BODY)?.toString())
        }

    /** A picture loader cannot bring back a dismissed pre-upgrade long-text card. */
    @Test
    fun dismissedLegacyTextCardIsNotResurrectedByALatePicture() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = ::latePicture)
            val body = "A dismissed pre-upgrade message. ".repeat(12).trim()
            show(presenter, dm(), body = body)
            postLegacyTextCard(body)
            val before = posts.size
            presenter.dismissConversationMessages(ACCOUNT, GROUP)

            assertFalse(refreshLateIdentity(presenter, dm()))

            assertEquals(before, posts.size)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** Recreates the posted pre-upgrade payload without losing its generation, privacy or identity stamps. */
    private fun postLegacyTextCard(body: String): Notification {
        val active = manager.activeNotifications.single()
        val sender = lastMessagePerson(active.notification)
        val extras =
            Bundle(active.notification.extras).apply {
                remove(Notification.EXTRA_MESSAGES)
                remove(Notification.EXTRA_MESSAGING_PERSON)
                putCharSequence(LEGACY_BODY, body)
                putLong("dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_TIMESTAMP", active.notification.`when`)
                putBundle(LEGACY_SENDER, sender.toBundle())
            }
        val legacy =
            NotificationCompat
                .Builder(context, active.notification)
                .setExtras(extras)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .build()
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(legacy))
        manager.notify(active.tag, active.id, legacy)
        return legacy
    }

    /** The account-scoped private picture wins at the final write, and Clear falls back to the monogram. */
    @Test
    fun privateContactPictureWinsAndClearFallsBackToTheMonogram() =
        runBlocking {
            var pixels: Bitmap? = solid(Color.RED)
            val scopes = mutableSetOf<String>()
            val presenter =
                presenter(
                    contactAvatar = { scope, contact ->
                        scopes += scope
                        if (contact == DEFAULT_SENDER) true to pixels else false to null
                    },
                )
            show(presenter, dm(), conversation = solid(Color.BLUE), sender = solid(Color.BLUE))
            assertEquals(Color.RED, identityPixel(liveCard()))

            pixels = null
            presenter.refreshContactSenderName(ACCOUNT, DEFAULT_SENDER, "Maya")
            val seed = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
            assertEquals(notificationMonogramBackgroundColor(seed), identityPixel(liveCard()))
            assertEquals("only this card's own account scope is consulted", setOf(scope(ACCOUNT)), scopes)
        }

    /** Rewriting a live group card for a member's private picture keeps the group's own identity image. */
    @Test
    fun liveGroupRewriteKeepsTheGroupIdentityImage() =
        runBlocking {
            val presenter = presenter(contactAvatar = { _, contact -> (contact == DEFAULT_SENDER) to solid(Color.RED) })
            show(presenter, group(), conversation = solid(Color.BLUE), sender = solid(Color.GREEN))
            presenter.refreshContactSenderName(ACCOUNT, DEFAULT_SENDER, "Maya")

            val card = liveCard()
            assertEquals(Color.BLUE, identityPixel(card))
            assertEquals(0, Color.alpha(pixel(lastMessagePerson(card))))
            assertEquals("Maya", lastMessagePerson(card).name.toString())
        }

    /** App-lock redaction never carries an identity picture or its contact key. */
    @Test
    fun redactedCardCarriesNoIdentityImage() {
        val presenter = presenter()
        runBlocking {
            presenter.show(
                dm(),
                conversationAvatarBitmap = solid(Color.RED),
                senderAvatarBitmap = solid(Color.RED),
                redactContent = true,
                shortNpub = { "npub1test" },
            )
        }
        val card = liveCard()
        assertNull(card.getLargeIcon())
        assertNull(identityExtras(card))
        assertFalse(card.extras.keySet().any { it.contains("identity") })
    }

    /**
     * A picture that arrives after the post rewrites the live card once and silently, and the card keeps the sender
     * name it shows now rather than the one it was first posted with.
     */
    @Test
    fun lateIdentityRefreshRewritesTheLiveCardAndKeepsItsCurrentSenderName() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = ::latePicture)
            show(presenter, dm())
            presenter.refreshContactSenderName(ACCOUNT, DEFAULT_SENDER, "Maya")
            val before = posts.size

            assertTrue(refreshLateIdentity(presenter, dm()))

            assertEquals(before + 1, posts.size)
            val card = liveCard()
            assertEquals(Color.MAGENTA, identityPixel(card))
            assertEquals(Color.MAGENTA, pixel(lastMessagePerson(card)))
            assertEquals("Maya", lastMessagePerson(card).name.toString())
            assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    /** An unused late member picture causes no fetch or rewrite and keeps the current sender name. */
    @Test
    fun lateGroupSenderPictureKeepsTheCurrentNameAndNoSenderPixels() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val fetched = mutableListOf<String>()
            val presenter = presenter(posts, resolver = { url -> url?.let { fetched += it; solid(Color.MAGENTA) } })
            val picture = solid(Color.BLUE)
            show(presenter, group(), conversation = picture)
            presenter.refreshContactSenderName(ACCOUNT, DEFAULT_SENDER, "Maya")
            val before = posts.size
            val sources = NotificationIdentityPictureSources(picture, null, null, PICTURE_URL)

            assertFalse(refreshLateIdentity(presenter, group(), sources))

            assertTrue(fetched.isEmpty())
            assertEquals(before, posts.size)
            val card = liveCard()
            assertEquals(Color.BLUE, identityPixel(card))
            assertEquals("Maya", lastMessagePerson(card).name.toString())
            assertEquals(0, Color.alpha(pixel(lastMessagePerson(card))))
            assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    /**
     * A group whose picture is a Marmot encrypted image has no URL, so its card is first drawn with the lettered circle
     * and the conversation loader supplies the picture as one silent rewrite of the same card.
     */
    @Test
    fun lateIdentityRefreshGivesAGroupItsEncryptedPictureFromTheLoader() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts)
            show(presenter, group())
            val before = posts.size
            val sources =
                NotificationIdentityPictureSources(null, null, null, null, conversationLoader = { solid(Color.GREEN) })

            assertTrue(sources.hasMissingPicture)
            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertEquals(before + 1, posts.size)
            val card = liveCard()
            assertEquals(Color.GREEN, identityPixel(card))
            assertTrue(card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    /**
     * A group picture whose encrypted download outlasts a URL picture's 2.5 s bound, as on a slow network or a busy
     * device, still reaches the card, because the late refresh gives the loader its own longer bound.
     */
    @Test
    fun lateIdentityRefreshWaitsPastTheUrlBoundForASlowGroupPicture() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts)
            show(presenter, group())
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    null,
                    null,
                    null,
                    conversationLoader = {
                        delay(SLOW_GROUP_PICTURE_MS)
                        solid(Color.BLUE)
                    },
                )

            assertTrue(refreshLateIdentity(presenter, group(), sources))

            assertEquals(Color.BLUE, identityPixel(liveCard()))
        }

    /** A failed group-picture load never fetches an unused sender image or rewrites the card. */
    @Test
    fun lateIdentityRefreshKeepsSenderPixelsHiddenWhenTheGroupLoaderFails() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val fetched = mutableListOf<String>()
            val presenter = presenter(posts, resolver = { url -> url?.let { fetched += it; solid(Color.MAGENTA) } })
            show(presenter, group())
            val before = posts.size
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    null,
                    null,
                    PICTURE_URL,
                    conversationLoader = { error("the group picture download failed") },
                )

            assertFalse(refreshLateIdentity(presenter, group(), sources))

            assertTrue(fetched.isEmpty())
            assertEquals("no visible picture arrived, so no rewrite is needed", before, posts.size)
            assertEquals(0, Color.alpha(pixel(lastMessagePerson(liveCard()))))
        }

    /** A redacted group card never asks the loader for its encrypted picture, and nothing is written. */
    @Test
    fun lateIdentityRefreshNeverLoadsAGroupPictureForARedactedCard() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts)
            presenter.show(group(), redactContent = true, shortNpub = { "npub1test" })
            val sources =
                NotificationIdentityPictureSources(
                    null,
                    null,
                    null,
                    null,
                    conversationLoader = { error("a redacted card never loads a group picture") },
                )

            assertFalse(refreshLateIdentity(presenter, group(), sources))

            assertEquals(1, posts.size)
            assertNull(liveCard().getLargeIcon())
        }

    /** A redacted card is never given a late picture, and nothing is written. */
    @Test
    fun lateIdentityRefreshLeavesARedactedCardAlone() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = ::latePicture)
            presenter.show(dm(), redactContent = true, shortNpub = { "npub1test" })

            assertFalse(refreshLateIdentity(presenter, dm()))

            assertEquals(1, posts.size)
            assertNull(liveCard().getLargeIcon())
        }

    /** A card that now shows a newer message is not rewritten for the older message's picture. */
    @Test
    fun lateIdentityRefreshLeavesANewerMessageAlone() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = ::latePicture)
            show(presenter, dm(messageId = "first"))
            show(presenter, dm(messageId = "second"))

            assertFalse(refreshLateIdentity(presenter, dm(messageId = "first")))

            assertEquals(2, posts.size)
        }

    /** A card whose pictures were all in hand at its post is never refreshed or fetched for again. */
    @Test
    fun lateIdentityRefreshSkipsACardThatAlreadyShowedItsPictures() =
        runBlocking {
            val posts = mutableListOf<Notification>()
            val presenter = presenter(posts, resolver = { error("a shown picture is never fetched again") })
            show(presenter, dm(), conversation = solid(Color.RED), sender = solid(Color.RED))
            val shown = solid(Color.RED)
            val sources = NotificationIdentityPictureSources(shown, PICTURE_URL, shown, PICTURE_URL)

            assertFalse(refreshLateIdentity(presenter, dm(), sources))

            assertEquals(1, posts.size)
        }

    /** A late picture for a card that is gone by the time it arrives takes no pacer slot. */
    @Test
    fun lateIdentityRefreshForAGoneCardTakesNoPacerSlot() =
        runBlocking {
            val slots = AtomicInteger()
            val presenter = presenter(resolver = ::latePicture, pacer = countingPacer(slots))
            show(presenter, dm())
            val afterPost = slots.get()
            manager.cancelAll()

            assertFalse(refreshLateIdentity(presenter, dm()))

            assertEquals(afterPost, slots.get())
        }

    /** A picture that resolves after its card left the platform takes no pacer slot on the enrichment path either. */
    @Test
    fun enrichmentForAGoneCardTakesNoPacerSlot() {
        val slots = AtomicInteger()
        var enrichment: (suspend () -> Unit)? = null
        val presenter =
            presenter(resolver = ::latePicture, launcher = { enrichment = it }, pacer = countingPacer(slots))
        show(presenter, dm(), senderUrl = PICTURE_URL)
        val afterPost = slots.get()
        manager.cancelAll()

        runBlocking { checkNotNull(enrichment).invoke() }

        assertEquals(afterPost, slots.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** Runs the late-picture refresh for [update] as AppState does, with every caller fence open. */
    private suspend fun refreshLateIdentity(
        presenter: LocalNotificationPresenter,
        update: dev.ipf.marmotkit.NotificationUpdateFfi,
        sources: NotificationIdentityPictureSources =
            NotificationIdentityPictureSources(null, PICTURE_URL, null, PICTURE_URL),
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
        pacer: NotificationPostPacer = NotificationPostPacer(),
        shortcutPublications: AtomicInteger? = null,
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            groupReconciliation = {},
            shortcutPublisher = {
                shortcutPublications?.incrementAndGet()
                ShortcutManagerCompat.pushDynamicShortcut(context, it)
            },
            notificationPoster = { notificationManager, tag, id, notification ->
                posts += notification
                notificationManager.notify(tag, id, notification)
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = resolver,
            enrichmentLauncher = launcher,
            contactAvatarOverride = contactAvatar,
            postPacer = pacer,
        ).also { it.ensureChannels() }

    /** A pacer that counts the write slots taken, one clock read per slot. */
    private fun countingPacer(slots: AtomicInteger) =
        NotificationPostPacer(
            nowMillis = {
                slots.incrementAndGet()
                0L
            },
        )

    /** Posts one message with the given ready pictures, cold picture URL and body. */
    @Suppress("LongParameterList") // Mirrors the presenter's optional picture inputs by name.
    private fun show(
        presenter: LocalNotificationPresenter,
        update: dev.ipf.marmotkit.NotificationUpdateFfi,
        conversation: Bitmap? = null,
        sender: Bitmap? = null,
        senderUrl: String? = null,
        conversationUrl: String? = null,
        body: String = "hi",
    ) {
        assertTrue(
            runBlocking {
                presenter.show(
                    update,
                    previewTextOverride = body,
                    conversationAvatarBitmap = conversation,
                    conversationAvatarUrl = conversationUrl,
                    senderAvatarBitmap = sender,
                    senderAvatarUrl = senderUrl,
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

    /** The identity snapshot's recorded inputs, or null when the card carries none. */
    private fun identityExtras(card: Notification) = card.extras.getBundle(EXTRA_IDENTITY_IMAGE)

    /** The top-left pixel of the card's primary identity picture. */
    private fun identityPixel(card: Notification): Int {
        val drawable = checkNotNull(card.getLargeIcon()?.loadDrawable(context)) { "the card has no identity picture" }
        return drawable.toBitmap().getPixel(0, 0)
    }

    /** Resolves any requested picture URL to the late magenta picture, and nothing for an absent URL. */
    private suspend fun latePicture(url: String?): Bitmap? = url?.let { solid(Color.MAGENTA) }

    /** The newest message's sender. */
    private fun lastMessagePerson(card: Notification): Person =
        checkNotNull(
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(card)
                ?.messages
                ?.last()
                ?.person,
        )

    /** The top-left pixel of a Person icon. */
    private fun pixel(person: Person): Int = checkNotNull(person.icon?.loadDrawable(context)).toBitmap().getPixel(0, 0)

    /** A direct-chat message for the fixture account and conversation. */
    private fun dm(messageId: String = "message") =
        notificationUpdate(isDm = true, messageIdHex = messageId, notificationKey = KEY, conversationKey = CONVERSATION)

    /** A group message for the fixture account and conversation. */
    private fun group() = notificationUpdate(isDm = false, notificationKey = KEY, conversationKey = CONVERSATION)

    /** A small single-color bitmap whose pixels identify which picture reached the card. */
    private fun solid(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    /** The opaque account scope the presenter hands to the contact-picture lookup. */
    private fun scope(account: String): String = checkNotNull(conversationShortcutAccountScope(account))

    private companion object {
        const val LEGACY_BODY = "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_BODY"
        const val LEGACY_SENDER = "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_SENDER"
        const val ACCOUNT = NotificationFixtureDefaults.ACCOUNT_REF
        const val GROUP = NotificationFixtureDefaults.GROUP_ID_HEX
        const val DEFAULT_SENDER = NotificationFixtureDefaults.SENDER_ACCOUNT_ID_HEX
        const val KEY = "key"
        const val CONVERSATION = "conv"
        const val PICTURE_PX = 8
        const val PICTURE_URL = "https://example.com/alice.png"
    }
}

/** Longer than a URL picture's 2.5 s bound and well inside the group picture's own. */
private const val SLOW_GROUP_PICTURE_MS = 3_000L
