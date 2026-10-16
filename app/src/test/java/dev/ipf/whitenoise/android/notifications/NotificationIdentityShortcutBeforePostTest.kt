package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #3311: Android applies the conversation layout and its picture only when the conversation shortcut exists when a
 * card is posted. The identity shortcut is therefore published before the one first post, from pictures already in
 * hand, while redacted and hidden-preview posts keep only the generic shortcut and a failed publication still posts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationIdentityShortcutBeforePostTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** Starts every scenario from an empty tray and shortcut inventory with notifications allowed. */
    @Before
    fun setUp() {
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        InFlightConversationShortcuts.clearForTests()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A brand-new direct chat's shortcut, with the private contact picture, exists before its only post. */
    @Test
    fun brandNewDirectChatPublishesItsIdentityShortcutBeforeItsOnlyPost() {
        val recorder = Recorder()
        val privatePicture = solid(Color.RED)
        val presenter = presenter(recorder, contactAvatar = { _, contact -> (contact == SENDER) to privatePicture })
        post(presenter, update(isDm = true), picture = solid(Color.BLUE))

        assertEquals(listOf("shortcut", "post"), recorder.events)
        val shortcut = recorder.shortcuts.single()
        assertEquals(conversationShortcutId(ACCOUNT, GROUP), shortcut.id)
        assertFalse(isHidden(shortcut))
        assertEquals("the private picture wins over the public one", Color.RED, iconPixel(shortcut))
        assertEquals(Color.RED, checkNotNull(recorder.posts.single().getLargeIcon()).let(::pixel))
    }

    /** A new group shows its picture, or its monogram when it has none, in the shortcut before its first post. */
    @Test
    fun newGroupShortcutCarriesTheGroupPictureOrMonogramBeforeItsFirstPost() {
        val withPicture = Recorder()
        post(presenter(withPicture), update(isDm = false), picture = solid(Color.BLUE), sender = solid(Color.GREEN))
        assertEquals(listOf("shortcut", "post"), withPicture.events)
        assertEquals(Color.BLUE, iconPixel(withPicture.shortcuts.single()))

        setUp()
        val withoutPicture = Recorder()
        post(presenter(withoutPicture), update(isDm = false, groupIdHex = OTHER_GROUP), sender = solid(Color.GREEN))
        val seed = checkNotNull(conversationShortcutId(ACCOUNT, OTHER_GROUP))
        assertEquals(listOf("shortcut", "post"), withoutPicture.events)
        assertEquals(notificationMonogramBackgroundColor(seed), iconPixel(withoutPicture.shortcuts.single()))
    }

    /** An unchanged conversation is not published again, so later first posts add no shortcut work. */
    @Test
    fun unchangedConversationPostsWithoutAnotherPublication() {
        val recorder = Recorder()
        val presenter = presenter(recorder)
        val picture = solid(Color.BLUE)
        post(presenter, update(isDm = false), picture = picture)
        manager.cancelAll()
        post(presenter, update(isDm = false, messageIdHex = "second"), picture = picture)

        assertEquals(listOf("shortcut", "post", "post"), recorder.events)
    }

    /**
     * A conversation removed on this device and joined again under the same group id publishes its shortcut again
     * before its next first card, instead of only reporting use of the shortcut the removal deleted.
     */
    @Test
    fun forgottenConversationPublishesItsShortcutAgainBeforeItsNextFirstPost() {
        val recorder = Recorder()
        val presenter = presenter(recorder)
        val picture = solid(Color.BLUE)
        post(presenter, update(isDm = false), picture = picture)
        manager.cancelAll()
        val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId))
        presenter.forgetConversationShortcut(ACCOUNT, GROUP)

        post(presenter, update(isDm = false, messageIdHex = "after-reset"), picture = picture)

        assertEquals(listOf("shortcut", "post", "shortcut", "post"), recorder.events)
        assertEquals(listOf(shortcutId), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
    }

    /**
     * A shortcut another publisher removed while the presenter still caches its snapshot, as a Direct Share refresh
     * can, is published again before the next card instead of that card naming a missing shortcut.
     */
    @Test
    fun removedShortcutIsPublishedAgainDespiteACachedSnapshot() {
        val recorder = Recorder()
        val presenter = presenter(recorder)
        val picture = solid(Color.BLUE)
        post(presenter, update(isDm = true), picture = picture)
        manager.cancelAll()
        val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId))

        post(presenter, update(isDm = true, messageIdHex = "after-removal"), picture = picture)

        assertEquals(listOf("shortcut", "post", "shortcut", "post"), recorder.events)
        assertEquals(listOf(shortcutId), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
    }

    /** The shortcut published for a card is held while that card is written, and released once it is. */
    @Test
    fun publishedShortcutIsHeldUntilItsCardIsWritten() {
        val recorder = Recorder()
        post(presenter(recorder), update(isDm = true), picture = solid(Color.BLUE))

        val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        assertEquals(listOf(setOf(shortcutId)), recorder.heldAtPost)
        assertTrue(InFlightConversationShortcuts.ids().isEmpty())
    }

    /** A card write cancelled after the shortcut was published still releases that shortcut's hold. */
    @Test
    fun cancelledCardWriteReleasesItsHeldShortcut() {
        val recorder = Recorder(cancelPost = true)
        val cancelled =
            runCatching {
                runBlocking {
                    presenter(recorder).show(
                        update(isDm = true),
                        previewTextOverride = "hi",
                        conversationAvatarBitmap = solid(Color.BLUE),
                        senderAvatarBitmap = solid(Color.BLUE),
                        shortNpub = { "npub" },
                    )
                }
            }.exceptionOrNull()

        assertTrue("the write was cancelled", cancelled is kotlinx.coroutines.CancellationException)
        assertEquals(listOf(setOf(checkNotNull(conversationShortcutId(ACCOUNT, GROUP)))), recorder.heldAtPost)
        assertTrue(InFlightConversationShortcuts.ids().isEmpty())
    }

    /** Forgetting one conversation leaves another conversation's snapshot, so that one is not published again. */
    @Test
    fun forgettingOneConversationKeepsAnotherConversationsSnapshot() {
        val recorder = Recorder()
        val presenter = presenter(recorder)
        val picture = solid(Color.BLUE)
        post(presenter, update(isDm = false, groupIdHex = OTHER_GROUP), picture = picture)
        manager.cancelAll()
        presenter.forgetConversationShortcut(ACCOUNT, GROUP)

        post(presenter, update(isDm = false, groupIdHex = OTHER_GROUP, messageIdHex = "second"), picture = picture)

        assertEquals(listOf("shortcut", "post", "post"), recorder.events)
    }

    /** App lock never publishes a shortcut, and its card carries none. */
    @Test
    fun appLockPublishesNoShortcut() {
        val recorder = Recorder()
        assertTrue(
            runBlocking {
                presenter(recorder).show(
                    update(isDm = true),
                    conversationAvatarBitmap = solid(Color.RED),
                    senderAvatarBitmap = solid(Color.RED),
                    redactContent = true,
                    shortNpub = { "npub" },
                )
            },
        )
        assertEquals(listOf("post"), recorder.events)
        assertEquals(null, recorder.posts.single().shortcutId)
    }

    /** Hidden previews publish only the generic shortcut, with no picture or contact identity. */
    @Test
    fun hiddenPreviewsKeepOnlyTheGenericShortcut() {
        hidePreviews()
        val recorder = Recorder()
        post(presenter(recorder), update(isDm = true), picture = solid(Color.RED))

        assertEquals("no identity shortcut is published for a hidden card", listOf("post"), recorder.events)
        val platform = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        assertTrue(isHidden(platform))
        assertEquals(context.getString(dev.ipf.whitenoise.android.R.string.app_name), platform.shortLabel)
    }

    /** Turning previews back on replaces the generic shortcut with the identity one before the next first post. */
    @Test
    fun allowedPreviewsReplaceTheGenericShortcutBeforeTheNextFirstPost() {
        hidePreviews()
        val recorder = Recorder()
        val presenter = presenter(recorder)
        post(presenter, update(isDm = true), picture = solid(Color.RED))
        manager.cancelAll()
        runBlocking { assertTrue(NotificationPreviewPreferences.setEnabled(context, true)) }

        post(presenter, update(isDm = true, messageIdHex = "after"), picture = solid(Color.RED))

        assertEquals(listOf("post", "shortcut", "post"), recorder.events)
        assertFalse(isHidden(recorder.shortcuts.single()))
        assertEquals(Color.RED, iconPixel(recorder.shortcuts.single()))
    }

    /**
     * A failed publication is contained and the card still posts exactly once with its picture. An unchanged picture
     * does not publish again after the post, so the next message's pre-post publish is the retry.
     */
    @Test
    fun failedPublicationStillPostsOnce() {
        val recorder = Recorder(failPublication = true)
        post(presenter(recorder), update(isDm = true), picture = solid(Color.RED))

        assertEquals(listOf("shortcut", "post"), recorder.events)
        assertEquals(1, recorder.posts.size)
        assertNotNull(recorder.posts.single().getLargeIcon())
    }

    /**
     * A message whose shortcut and picture are unchanged reports the shortcut used exactly once, from the pre-post
     * publish, and the post-write enrichment adds no second report. A late picture is a changed snapshot, so its
     * republish reports use once more, which this case does not cover.
     */
    @Test
    fun unchangedPictureReportsShortcutUseOncePerMessage() {
        val presenter = presenter(Recorder())
        val picture = solid(Color.BLUE)
        post(presenter, update(isDm = false), picture = picture)
        val reportedBefore = reportedShortcutUses().size

        post(presenter, update(isDm = false, messageIdHex = "second"), picture = picture)

        val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
        assertEquals(listOf(shortcutId), reportedShortcutUses().drop(reportedBefore))
    }

    /** Records publication and post order, and can fail the platform shortcut write. */
    private class Recorder(
        val failPublication: Boolean = false,
        val cancelPost: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        val posts = mutableListOf<Notification>()
        val heldAtPost = mutableListOf<Set<String>>()
    }

    /** A presenter whose shortcut and card writes go to [recorder] and then to the platform. */
    private fun presenter(
        recorder: Recorder,
        contactAvatar: (String, String) -> Pair<Boolean, Bitmap?> = { _, _ -> false to null },
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            groupReconciliation = {},
            shortcutPublisher = { shortcut ->
                recorder.events += "shortcut"
                if (recorder.failPublication) error("shortcut service unavailable")
                recorder.shortcuts += shortcut
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            },
            notificationPoster = { notificationManager, tag, id, notification ->
                recorder.events += "post"
                recorder.posts += notification
                recorder.heldAtPost += InFlightConversationShortcuts.ids()
                if (recorder.cancelPost) throw kotlinx.coroutines.CancellationException("post cancelled")
                notificationManager.notify(tag, id, notification)
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = { null },
            enrichmentLauncher = { error("ready pictures must not launch enrichment") },
            contactAvatarOverride = contactAvatar,
        ).also { it.ensureChannels() }

    /** Posts one message with ready pictures and requires that it was written. */
    private fun post(
        presenter: LocalNotificationPresenter,
        update: NotificationUpdateFfi,
        picture: Bitmap? = null,
        sender: Bitmap? = picture,
    ) {
        assertTrue(
            runBlocking {
                presenter.show(
                    update,
                    previewTextOverride = "hi",
                    conversationAvatarBitmap = picture,
                    senderAvatarBitmap = sender,
                    shortNpub = { "npub" },
                )
            },
        )
    }

    /** Shortcut ids the platform was told were used, in order. */
    private fun reportedShortcutUses(): List<String> {
        val shortcuts = Shadows.shadowOf(context.getSystemService(ShortcutManager::class.java))
        return shortcuts.reportedShortcutsUsed.toList()
    }

    /** Turns notification previews off the way the persisted setting does. */
    private fun hidePreviews() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NotificationPreviewPreferences.KEY, false)
            .commit()
    }

    /** True for the generic hidden-preview shortcut. */
    private fun isHidden(shortcut: ShortcutInfoCompat): Boolean {
        val extras = shortcut.extras ?: return false
        return extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN)
    }

    /** The top-left pixel of a published shortcut's icon. */
    private fun iconPixel(shortcut: ShortcutInfoCompat): Int {
        val drawable = checkNotNull(shortcut.icon?.loadDrawable(context)) { "the shortcut has no icon" }
        return drawable.toBitmap().getPixel(0, 0)
    }

    /** The top-left pixel of a card's identity picture. */
    private fun pixel(icon: Icon): Int {
        val drawable = checkNotNull(icon.loadDrawable(context)) { "the card has no identity picture" }
        return drawable.toBitmap().getPixel(0, 0)
    }

    /** A message from the fixture sender in the given conversation. */
    private fun update(
        isDm: Boolean,
        groupIdHex: String = GROUP,
        messageIdHex: String = "first",
    ) = notificationUpdate(
        isDm = isDm,
        groupIdHex = groupIdHex,
        messageIdHex = messageIdHex,
        notificationKey = "key-$groupIdHex",
        conversationKey = "conversation-$groupIdHex",
    )

    /** A small single-color picture whose pixels identify which image reached the shortcut or card. */
    private fun solid(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private companion object {
        const val ACCOUNT = NotificationFixtureDefaults.ACCOUNT_REF
        const val GROUP = NotificationFixtureDefaults.GROUP_ID_HEX
        const val OTHER_GROUP = "group-b"
        const val SENDER = NotificationFixtureDefaults.SENDER_ACCOUNT_ID_HEX
        const val PICTURE_PX = 8
    }
}
