package dev.ipf.whitenoise.android.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.ContactPictureStore
import dev.ipf.whitenoise.android.state.contactPicturePng
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profile/member/reply avatar rendering cannot revive a captured private bitmap after account invalidation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PrivateContactAvatarSurfaceTest {
    @get:Rule val composeRule = createComposeRule()

    /** Missing or unreadable private bytes retain current native public pixels but never the old account's. */
    @Test fun missingPrivatePictureUsesCurrentPublicPixels() = assertPublicFallback(corrupt = false)

    /** A corrupt local selection may use current public pixels without borrowing a retired owner's image. */
    @Test fun corruptPrivatePictureUsesCurrentPublicPixels() = assertPublicFallback(corrupt = true)

    /** Renders the real sender slot after deleting or corrupting its private bytes, then revokes its owner. */
    private fun assertPublicFallback(corrupt: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val root = context.noBackupFilesDir.resolve("fallback-pictures")
        val store = ContactPictureStore(context.getSharedPreferences("fallback-pictures", 0), root)
        var account = "a"
        PrivateContactAvatarLoader.attach(store) { account }
        store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
        val reference = checkNotNull(store.reference("a", "contact"))
        val file = root.resolve(reference.owner).resolve(reference.fileName)
        if (corrupt) file.writeText("unreadable image") else check(file.delete())
        AvatarImageLoader.clearStoredAvatars()
        val source = PrivateContactAvatarLoader.source(reference, null)
        val bytes = contactPicturePng(Color.BLUE)
        val public = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
        composeRule.setContent {
            WhiteNoiseTheme {
                Row(Modifier.testTag("sender-slot")) {
                    MessageSenderAvatarSlot(true, "Maya", "contact", source, true, false, {}, public)
                }
            }
        }

        /** Samples the centre of the actual rendered avatar surface rather than its source bitmap. */
        fun pixel(): Int {
            val bitmap = composeRule.onNodeWithTag("sender-slot").captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
        assertEquals(Color.BLUE, pixel())
        composeRule.runOnIdle {
            account = "b"
            AvatarImageLoader.clearStoredAvatars()
        }
        composeRule.waitForIdle()
        assertNotEquals(Color.BLUE, pixel())
        assertNotEquals(Color.RED, pixel())
    }

    /** Both avatar surfaces prefer current private pixels and drop every captured image after owner invalidation. */
    @Test fun privateImagePrecedesCapturedPixelsAndOldOwnerDisappearsOnNextFrame() {
        val context = RuntimeEnvironment.getApplication()
        val store =
            ContactPictureStore(
                context.getSharedPreferences("surface-pictures", 0),
                context.noBackupFilesDir.resolve("surface-pictures"),
            )
        var account = "a"
        PrivateContactAvatarLoader.attach(store) { account }
        store.save("a", "contact", "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) { true }
        val source = PrivateContactAvatarLoader.source(checkNotNull(store.reference("a", "contact")), null)
        val privateImage = runBlocking { AvatarImageLoader.load(source) }
        val publicBytes = contactPicturePng(Color.BLUE)
        val public = BitmapFactory.decodeByteArray(publicBytes, 0, publicBytes.size).asImageBitmap()
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    Box(Modifier.testTag("identity")) { Avatar("Maya", "contact", 64.dp, source, public) }
                    Row(Modifier.testTag("sender-slot")) {
                        MessageSenderAvatarSlot(true, "Maya", "contact", source, true, false, {}, public)
                    }
                }
            }
        }

        /** Samples the centre of the actual rendered avatar surface rather than its source bitmap. */
        fun pixel(tag: String = "identity"): Int {
            val image = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            return image.getPixel(image.width / 2, image.height / 2)
        }
        assertEquals(Color.RED, pixel())
        assertEquals("The real sender wrapper must preserve private precedence", Color.RED, pixel("sender-slot"))
        assertEquals(Color.RED, checkNotNull(privateImage).asAndroidBitmap().getPixel(0, 0))
        composeRule.runOnIdle {
            account = "b"
            AvatarImageLoader.clearStoredAvatars()
        }
        composeRule.waitForIdle()
        assertNotEquals(Color.RED, pixel())
        assertNotEquals(Color.BLUE, pixel())
        assertNotEquals(Color.RED, pixel("sender-slot"))
        assertNotEquals(Color.BLUE, pixel("sender-slot"))
    }
}
