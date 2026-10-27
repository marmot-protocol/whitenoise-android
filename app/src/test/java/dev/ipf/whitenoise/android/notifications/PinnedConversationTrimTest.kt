package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarCacheChanges
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.media.PresentationMemoryTrim
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ProfilePresentation
import dev.ipf.whitenoise.android.state.ScopedCache
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.contactPicturePng
import dev.ipf.whitenoise.android.ui.share.group
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.spec.SecretKeySpec

/** Launcher-owned pictures survive trim without surviving source removal or privacy revocation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedConversationTrimTest {
    /**
     * Runs production encrypted persistence with a deterministic test key instead of an unavailable Android
     * Keystore.
     */
    @Before fun installTestKey() {
        setPinnedConversationTestKey(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    }

    /** Prevents the fixture's key from leaking into another sandbox or provider-failure scenario. */
    @After fun releaseTestKey() {
        setPinnedConversationTestKey(null)
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val tokens get() = PinnedConversationTokens.create(context)
    private val capability get() = requireNotNull(tokens.issue(ACCOUNT, GROUP))

    /** An unrelated publication after trim keeps the launcher's current photo, but a changed selection clears it. */
    @Test
    fun trimmedGroupPictureSurvivesUnrelatedPublicationButNotReplacement() {
        val platform = PinnedShortcutTestPlatform(context)
        val owner = PinnedConversationShortcuts(context, platform)
        val app = pinApp()
        val url = "https://group.example/trim.png"
        val row = ChatListItem(group(GROUP).copy(avatarUrl = url), null, null, 3, null)
        val picture = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        AvatarImageLoader.putCachedAnimated(url, picture.asImageBitmap(), byteArrayOf(1))
        val presentation = app.pinnedConversationPresentation(ACCOUNT, row, "Group")
        owner.request(capability, "Group", null, presentation = presentation) { true }
        platform.approve(platform.requests.single())
        try {
            AvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
            assertNull(AvatarImageLoader.peek(url))
            val revision = AvatarCacheChanges.revision.value
            AvatarImageLoader.putCachedAnimated("https://other.example/avatar", picture.asImageBitmap(), byteArrayOf(1))
            assertNotSame(revision, AvatarCacheChanges.revision.value)
            val renamed = app.pinnedConversationPresentation(ACCOUNT, row, "Renamed")
            assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to renamed)))
            val retained = platform.updates.last().single()
            assertNull("omit icon to retain the platform-owned photo", retained.icon)
            assertEquals("Renamed", retained.longLabel)
            assertEquals(
                platform.requests
                    .single()
                    .intent.data,
                retained.intent.data,
            )

            val replacement = row.copy(group = row.group.copy(avatarUrl = "https://group.example/replaced.png"))
            val replaced = app.pinnedConversationPresentation(ACCOUNT, replacement, "Group")
            assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to replaced)))
            val changed = platform.updates.last().single()
            val icon = changed.icon!!.loadDrawable(context) as BitmapDrawable
            assertNotEquals(Color.MAGENTA, icon.bitmap.getPixel(0, 0))
        } finally {
            AvatarImageLoader.clear()
        }
    }

    /** A trimmed private photo remains the owner even when the peer publishes a new, cached public fallback. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun privatePinSurvivesTrimAndPublicFallbackChangeUntilPrivateChoiceIsCleared() =
        runBlocking {
            val app = pinApp()
            val bytes = contactPicturePng(Color.MAGENTA)
            val replacement = ContactPictureChange.Replace(bytes)
            assertTrue(app.contactPictureStore.save(ACCOUNT, "peer", "", "", replacement) { true })
            val ref = checkNotNull(app.contactPictureStore.reference(ACCOUNT, "peer"))
            val picture = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            AvatarImageLoader.putCachedAnimated(ref.cacheKey, picture.asImageBitmap(), byteArrayOf(1))
            app.setPinProfile("https://peer.example/old.png")
            val row = ChatListItem(group(GROUP), null, "peer", 2, null)
            val platform = PinnedShortcutTestPlatform(context)
            val owner = PinnedConversationShortcuts(context, platform)
            val presentation = app.pinnedConversationPresentation(ACCOUNT, row, "Peer")
            owner.request(capability, "Peer", null, presentation = presentation) { true }
            platform.approve(platform.requests.single())
            try {
                AvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
                app.setPinProfile("https://peer.example/new.png")
                val fallback = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                AvatarImageLoader.putCachedAnimated(
                    "https://peer.example/new.png",
                    fallback.asImageBitmap(),
                    byteArrayOf(1),
                )
                assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
                val retained = platform.updates.last().single()
                assertNull("a cached fallback cannot replace the selected private photo", retained.icon)
                assertTrue(app.contactPictureStore.save(ACCOUNT, "peer", "", "", ContactPictureChange.Clear) { true })
                assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
                val cleared =
                    platform.updates
                        .last()
                        .single()
                        .icon!!
                        .loadDrawable(context) as BitmapDrawable
                assertEquals(Color.BLUE, cleared.bitmap.getPixel(0, 0))
            } finally {
                AvatarImageLoader.clear()
            }
        }

    /** Group-only eviction cannot promote a cached peer fallback until the group-owned image is actually removed. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun trimmedEncryptedPinRetainsPrimaryOverCachedPeerFallback() =
        runBlocking {
            val app = pinApp()
            app.setPinProfile("https://peer.example/fallback.png")
            val row = ChatListItem(group(GROUP).copy(imageHashHex = "12".repeat(32)), null, "peer", 2, null)
            val key = checkNotNull(encryptedGroupAvatarCacheKey(ACCOUNT, row.group))
            checkNotNull(GroupAvatarImageLoader.load(key) { contactPicturePng(Color.MAGENTA) })
            val fallback = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            AvatarImageLoader.putCachedAnimated(
                "https://peer.example/fallback.png",
                fallback.asImageBitmap(),
                byteArrayOf(1),
            )
            val platform = PinnedShortcutTestPlatform(context)
            val owner = PinnedConversationShortcuts(context, platform)
            val presentation = app.pinnedConversationPresentation(ACCOUNT, row, "Group")
            owner.request(capability, "Group", null, presentation = presentation) { true }
            platform.approve(platform.requests.single())
            try {
                GroupAvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
                assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
                assertNull(
                    "a cached peer cannot replace the group-owned photo",
                    platform.updates
                        .last()
                        .single()
                        .icon,
                )
                val removed = row.copy(group = row.group.copy(imageHashHex = null))
                val current = app.pinnedConversationPresentation(ACCOUNT, removed, "Peer")
                assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to current)))
                val cleared =
                    platform.updates
                        .last()
                        .single()
                        .icon!!
                        .loadDrawable(context) as BitmapDrawable
                assertEquals(Color.BLUE, cleared.bitmap.getPixel(0, 0))
            } finally {
                AvatarImageLoader.clear()
                GroupAvatarImageLoader.clear()
            }
        }

    /** Seeds only the existing presentation map; no native profile fetch or account creation runs. */
    @Suppress("UNCHECKED_CAST")
    private fun WhiteNoiseAppState.setPinProfile(url: String) {
        val field =
            WhiteNoiseAppState::class.java.getDeclaredField("profilePresentations").apply { isAccessible = true }
        val profiles = field.get(this) as ScopedCache<String, ProfilePresentation>
        profiles.put("peer", ProfilePresentation("Peer", url))
    }

    /** A matching cache-miss selector cannot retain platform pixels across a notification privacy epoch change. */
    @Test
    fun trimmedPictureCannotSurvivePrivacyRevocation() =
        runBlocking {
            val platform = PinnedShortcutTestPlatform(context)
            val owner = PinnedConversationShortcuts(context, platform)
            var picture = PinnedConversationPicture(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888), "same-source")
            val presentation = PinnedConversationPresentation("Group", currentPicture = { picture })
            owner.request(capability, "Group", null, presentation = presentation) { true }
            platform.approve(platform.requests.single())
            picture = picture.copy(bitmap = null, missingIdentities = setOf(checkNotNull(picture.identity)))
            NotificationPreviewPreferences.setEnabled(context, false) { true }
            try {
                assertTrue(owner.refresh(ACCOUNT, mapOf(GROUP to presentation)))
                val hidden = platform.updates.last().single()
                assertEquals(context.getString(R.string.app_name), hidden.longLabel)
                assertEquals(IconCompat.TYPE_RESOURCE, hidden.icon!!.type)
            } finally {
                NotificationPreviewPreferences.setEnabled(context, true) { true }
            }
        }

    /** A signed-in synthetic owner resolves production picture selection without acquiring any native identity. */
    private fun pinApp() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi(ACCOUNT, "self", true, false, false, true)),
            activeAccountRef = ACCOUNT,
        )

    private companion object {
        const val ACCOUNT = "test-account"

        /** The 32-hex shape MDK emits for a 16-byte group ID. */
        val GROUP = "ab".repeat(16)
    }
}
