package dev.ipf.whitenoise.android.share

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.notifications.InFlightConversationShortcuts
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.state.cacheKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #3635: Android draws a conversation card's picture from its shortcut, so a Direct Share refresh that rebuilds a
 * card's shortcut as a recent chat keeps the stored picture the chat row shows, and has the card's next post publish
 * a picture it had to drop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareShortcutStoredPictureTest {
    /** Leaves no platform shortcut, kept publication or decoded picture behind for the next scenario. */
    @After
    fun tearDown() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(RuntimeEnvironment.getApplication())
        InFlightConversationShortcuts.clearForTests()
        AvatarImageLoader.clear()
    }

    /** A recent chat's target carries the decoded stored picture its chat row shows, for its account only. */
    @Test
    fun targetCarriesTheRowsDecodedStoredPicture() {
        val image = solid(Color.BLUE)
        val item = chat("g1", pending = false).copy(selectedAvatarAsset = decodedStoredPicture(image))

        assertSame(image, selectShareShortcutTargets(ACCOUNT, listOf(item)) { it.group.name }.single().avatarBitmap)
        assertNull(selectShareShortcutTargets("other", listOf(item)) { it.group.name }.single().avatarBitmap)
    }

    /** A live card's shortcut that is also a recent chat is rebuilt with its stored picture, not the monogram. */
    @Test
    fun liveCardsTargetShortcutKeepsItsStoredPicture() {
        val live = cardShortcut("g1")
        var published = emptyList<ShortcutInfoCompat>()
        val publisher = publisher(live) { published = it }
        InFlightConversationShortcuts.rememberPublished(live.id, live, pictured = true)
        val item = chat("g1", pending = false).copy(selectedAvatarAsset = decodedStoredPicture(solid(Color.BLUE)))

        publisher.publish(ACCOUNT, listOf(item)) { it.group.name }

        assertEquals("the stored picture", Color.BLUE, iconPixel(published.single { it.id == live.id }))
        assertFalse("no picture was lost", InFlightConversationShortcuts.consumeIdentityLost(live.id))
    }

    /**
     * A refresh that has to rebuild a card's target shortcut with the monogram marks it lost only when the presenter's
     * publication carried a picture, so the next post restores it and a conversation without one is left alone.
     */
    @Test
    fun targetRebuiltWithoutThePresentersPictureIsPublishedAgain() {
        val live = cardShortcut("g1")
        val publisher = publisher(live) {}
        val chats = listOf(chat("g1", pending = false))

        InFlightConversationShortcuts.rememberPublished(live.id, live, pictured = false)
        publisher.publish(ACCOUNT, chats) { it.group.name }
        assertFalse("a monogram publication loses nothing", InFlightConversationShortcuts.consumeIdentityLost(live.id))

        InFlightConversationShortcuts.rememberPublished(live.id, live, pictured = true)
        publisher.publish(ACCOUNT, chats) { it.group.name }
        assertTrue("a pictured one is published again", InFlightConversationShortcuts.consumeIdentityLost(live.id))
    }

    /** A notification-published shortcut for [groupId] as the platform reads it back, with no icon. */
    private fun cardShortcut(groupId: String): ShortcutInfoCompat {
        val context = RuntimeEnvironment.getApplication()
        val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, groupId))
        return ShortcutInfoCompat
            .Builder(context, shortcutId)
            .setShortLabel(groupId)
            .setLongLabel(groupId)
            .setIntent(buildShareShortcutIntent(context))
            .setLocusId(LocusIdCompat(shortcutId))
            .setLongLived(true)
            .build()
    }

    /** A publisher whose platform inventory holds [live], which backs a live card. */
    private fun publisher(
        live: ShortcutInfoCompat,
        setDynamicShortcuts: (List<ShortcutInfoCompat>) -> Unit,
    ) = ShareShortcutPublisher(
        context = RuntimeEnvironment.getApplication(),
        maxShortcutCount = { 8 },
        setDynamicShortcuts = setDynamicShortcuts,
        existingShortcuts = { listOf(live) },
        dynamicShortcuts = { listOf(live) },
        cardShortcutIds = { setOf(live.id) },
    )

    /** A ready stored avatar whose pixels the chat list has already decoded as [image] for [ACCOUNT]. */
    private fun decodedStoredPicture(image: Bitmap): AvatarAssetFfi {
        val asset =
            AvatarAssetFfi(
                "group-picture",
                "stored-group-picture",
                AvatarAvailabilityFfi.READY,
                AvatarAcquisitionStateFfi.IDLE,
                1uL,
                1_024uL,
            )
        AvatarImageLoader.putCached(checkNotNull(asset.cacheKey(ACCOUNT)), image.asImageBitmap())
        return asset
    }

    /** A small single-color picture whose pixels identify which image reached a shortcut. */
    private fun solid(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    /** The top-left pixel of a shortcut's icon. */
    private fun iconPixel(shortcut: ShortcutInfoCompat): Int {
        val drawable = checkNotNull(shortcut.icon?.loadDrawable(RuntimeEnvironment.getApplication()))
        return drawable.toBitmap().getPixel(0, 0)
    }

    private companion object {
        const val ACCOUNT = "acct"
        const val PICTURE_PX = 4
    }
}
