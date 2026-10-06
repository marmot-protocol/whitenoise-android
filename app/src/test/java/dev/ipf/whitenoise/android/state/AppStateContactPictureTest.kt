package dev.ipf.whitenoise.android.state

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** App-level policy keeps local pictures out of public identity and rejects obsolete editors. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AppStateContactPictureTest {
    @Test fun selfAndExpiredEditorsCannotWritePrivateFields() =
        runBlocking {
            val app = app()
            assertFalse(app.saveContactPrivateDetails("a", "self-a", "Private", "Note", picture(Color.RED)) { true })
            assertFalse(app.saveContactPrivateDetails("a", "self-b", "Private", "Note", picture(Color.RED)) { true })
            assertFalse(app.saveContactPrivateDetails("b", "contact", "Private", "Note", picture(Color.RED)) { true })
            assertFalse(app.saveContactPrivateDetails("a", "contact", "Private", "Note", picture(Color.RED)) { false })
            assertNull(app.contactNickname("contact"))
            assertNull(app.contactNotes("contact"))
            assertNull(app.contactPictureStore.reference("a", "contact"))
        }

    @Test fun switchingAccountsImmediatelyRevokesOldPixelsAndPublicIdentityStaysPublic() =
        runBlocking {
            val app = app()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "Note", picture(Color.RED)) { true })
            val first = checkNotNull(app.contactAvatarSource("contact"))
            assertTrue(PrivateContactAvatarLoader.isPrivate(first))
            assertEquals(Color.RED, checkNotNull(AvatarImageLoader.peek(first)).asAndroidBitmap().getPixel(0, 0))
            assertNull(app.avatarUrl("contact"))
            app.contactPictureStore.save("b", "contact", "Other", "", picture(Color.BLUE)) { true }
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setActiveAccountRef", String::class.java)
                .apply { isAccessible = true }
                .invoke(app, "b")
            assertNull(AvatarImageLoader.peek(first))
            val second = checkNotNull(app.contactAvatarSource("contact"))
            assertEquals(Color.BLUE, checkNotNull(AvatarImageLoader.load(second)).asAndroidBitmap().getPixel(0, 0))
            assertEquals("Other", app.contactNickname("contact"))
            assertNull(app.avatarUrl("contact"))
            assertFalse(
                app.saveContactPrivateDetails("a", "contact", "Late", "Late", ContactPictureChange.Clear) { true },
            )
            assertEquals(
                "Local",
                ContactNicknamePreferences.readNickname(
                    RuntimeEnvironment.getApplication().getSharedPreferences("whitenoise", 0),
                    "a",
                    "contact",
                ),
            )
        }

    private fun picture(color: Int) = ContactPictureChange.Replace(contactPicturePng(color))

    private fun app() =
        WhiteNoiseAppState(
            context = RuntimeEnvironment.getApplication(),
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { null },
            accounts = listOf(account("a"), account("b")),
            activeAccountRef = "a",
        )

    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "self-$label",
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )
}
