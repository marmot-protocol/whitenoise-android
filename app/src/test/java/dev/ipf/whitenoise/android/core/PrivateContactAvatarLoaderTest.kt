package dev.ipf.whitenoise.android.core

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.ContactPictureStore
import dev.ipf.whitenoise.android.state.contactPicturePng
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Private display handles can resolve locally but never reach the native public-URL fetch boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PrivateContactAvatarLoaderTest {
    /** Private bytes win locally; an unreadable file falls back publicly without sending its handle to MDK. */
    @Test fun privatePrecedenceAndPublishedFallbackNeverFetchThePrivateHandle() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val root = File(context.noBackupFilesDir, "loader-pictures")
            val store = ContactPictureStore(context.getSharedPreferences("loader", Context.MODE_PRIVATE), root)
            PrivateContactAvatarLoader.attach(store)
            AvatarImageLoader.clear()
            var fetches = 0
            AvatarImageLoader.attachProfileImageFetcher { url, _ ->
                assertFalse(PrivateContactAvatarLoader.isPrivate(url))
                fetches++
                contactPicturePng(Color.BLUE)
            }
            store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
            val ref = checkNotNull(store.reference("a", "contact"))
            val source = PrivateContactAvatarLoader.source(ref, "https://example.com/avatar.png")
            assertNull(ProfileSanitizer.protocolImageUrl(source))
            assertFalse(source.contains(root.absolutePath))
            assertEquals(Color.RED, checkNotNull(AvatarImageLoader.load(source)).asAndroidBitmap().getPixel(0, 0))
            assertEquals(0, fetches)
            assertEquals(AvatarByteFetchResult.Failed, AvatarImageLoader.fetchBytes(source, 1024))
            File(File(root, ref.owner), ref.fileName).writeBytes(byteArrayOf(1))
            AvatarImageLoader.clear()
            assertEquals(Color.BLUE, checkNotNull(AvatarImageLoader.load(source)).asAndroidBitmap().getPixel(0, 0))
            assertEquals(1, fetches)
            assertEquals(ref, store.reference("a", "contact"))
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }

    /** The same contact under different owners has distinct cache identities, and clearing A cannot revoke B. */
    @Test fun distinctAccountHandlesCannotBorrowCachedPrivatePixels() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val store =
                ContactPictureStore(
                    context.getSharedPreferences("owners", Context.MODE_PRIVATE),
                    File(context.noBackupFilesDir, "owners"),
                )
            PrivateContactAvatarLoader.attach(store)
            AvatarImageLoader.clear()
            store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
            store.save("b", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.BLUE))) { true }
            val first = PrivateContactAvatarLoader.source(checkNotNull(store.reference("a", "contact")), null)
            val second = PrivateContactAvatarLoader.source(checkNotNull(store.reference("b", "contact")), null)
            assertNotEquals(first, second)
            AvatarImageLoader.load(first)
            assertNull(AvatarImageLoader.peek(second))
            assertEquals(Color.BLUE, checkNotNull(AvatarImageLoader.load(second)).asAndroidBitmap().getPixel(0, 0))
            store.clearAccount("a")
            AvatarImageLoader.clearStoredAvatars()
            assertNull(AvatarImageLoader.load(first))
            assertEquals(Color.BLUE, checkNotNull(AvatarImageLoader.load(second)).asAndroidBitmap().getPixel(0, 0))
            AvatarImageLoader.clear()
        }

    /** Explicit picker ownership can read B without relaxing the active-account boundary for other avatars. */
    @Test fun displayedOwnerReadsOnlyItsOwnPrivateRecord() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val store =
                ContactPictureStore(
                    context.getSharedPreferences("scoped-owners", 0),
                    File(context.noBackupFilesDir, "scoped-owners"),
                )
            PrivateContactAvatarLoader.attach(store) { "a" }
            AvatarImageLoader.clear()
            store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
            store.save("b", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.BLUE))) { true }
            val sourceA = PrivateContactAvatarLoader.source(checkNotNull(store.reference("a", "contact")), null)
            val sourceB = PrivateContactAvatarLoader.source(checkNotNull(store.reference("b", "contact")), null)
            AvatarImageLoader.load(sourceA)
            assertFalse(PrivateContactAvatarLoader.belongsToAccount(sourceA, "b"))
            assertNull(PrivateContactAvatarLoader.peek(sourceA, "b"))
            assertNull(PrivateContactAvatarLoader.load(sourceA, "b"))
            assertEquals(Color.BLUE, checkNotNull(PrivateContactAvatarLoader.load(sourceB, "b")).asAndroidBitmap().getPixel(0, 0))
            assertNull(AvatarImageLoader.peek(sourceB))
            assertNull(AvatarImageLoader.load(sourceB))
            store.clearAccount("b")
            assertNull(PrivateContactAvatarLoader.load(sourceB, "b"))
            AvatarImageLoader.clear()
        }

}
