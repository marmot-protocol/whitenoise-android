package dev.ipf.whitenoise.android.state

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.ui.share.group
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
    /** Self identities and obsolete editor owners cannot write nickname, notes or picture state. */
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

    /** An account switch revokes old private pixels while public profile/export identity remains unchanged. */
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

    /** A nickname-only Save leaves every decoded avatar in place instead of retiring the whole cache lifetime. */
    @Test fun nicknameOnlySaveKeepsCachedAvatars() =
        runBlocking {
            val app = app()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "Note", picture(Color.RED)) { true })
            val source = checkNotNull(app.contactAvatarSource("contact"))
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Renamed", "", ContactPictureChange.Keep) { true })
            assertEquals(lifetime, AvatarImageLoader.currentCacheLifetime())
            assertEquals(source, app.contactAvatarSource("contact"))
            assertEquals(Color.RED, checkNotNull(AvatarImageLoader.peek(source)).asAndroidBitmap().getPixel(0, 0))
            assertEquals("Renamed", app.contactNickname("contact"))
            assertNull(app.contactNotes("contact"))
        }

    /** Pin snapshots resolve current private pixels and never replace a group-owned image with a peer picture. */
    @Test fun pinPresentationRevalidatesPrivatePicturesAndPreservesGroupOwnership() =
        runBlocking {
            val app = app()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.RED)) { true })
            val dm = ChatListItem(group("dm"), null, "contact", 2, null)
            val captured = app.pinnedConversationPresentation("a", dm, "Local")
            assertEquals("contact", captured.contact)
            assertEquals(Color.RED, checkNotNull(captured.currentAvatar?.invoke()).getPixel(0, 0))
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.BLUE)) { true })
            assertEquals(Color.BLUE, checkNotNull(captured.currentAvatar?.invoke()).getPixel(0, 0))
            val owned = dm.copy(group = dm.group.copy(avatarUrl = "https://group.example/picture"))
            val groupPin = app.pinnedConversationPresentation("a", owned, "Group")
            assertNull(groupPin.contact)
            assertNull(groupPin.currentAvatar?.invoke())
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })
            assertNull(captured.currentAvatar?.invoke())
        }

    /** Creates a small synthetic normalized replacement without relying on external media. */
    private fun picture(color: Int) = ContactPictureChange.Replace(contactPicturePng(color))

    /** Builds two signed-in owners with discarded drafts and no native identity side effects. */
    private fun app() =
        WhiteNoiseAppState(
            context = RuntimeEnvironment.getApplication(),
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        /** Starts from no persisted drafts. */
                        override fun read(): Map<String, String> = emptyMap()

                        /** Discards draft writes. */
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

    /** Provides distinct local/self identities so private-contact exclusion can be tested. */
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
