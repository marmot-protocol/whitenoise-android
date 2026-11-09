package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.media.PresentationMemoryTrim
import dev.ipf.whitenoise.android.notifications.NotificationIdentityImage
import dev.ipf.whitenoise.android.notifications.PinnedConversationCapability
import dev.ipf.whitenoise.android.notifications.notificationMonogramBackgroundColor
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.notifications.setConversationIdentityImage
import dev.ipf.whitenoise.android.notifications.withCurrentIdentityContact
import dev.ipf.whitenoise.android.ui.share.group
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicLong

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
            assertEquals(Color.RED, checkNotNull(captured.currentPicture?.invoke()?.bitmap).getPixel(0, 0))
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.BLUE)) { true })
            assertEquals(Color.BLUE, checkNotNull(captured.currentPicture?.invoke()?.bitmap).getPixel(0, 0))
            val owned = dm.copy(group = dm.group.copy(avatarUrl = "https://group.example/picture"))
            val groupPin = app.pinnedConversationPresentation("a", owned, "Group")
            assertNull(groupPin.contact)
            assertNull(groupPin.currentPicture?.invoke()?.bitmap)
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })
            assertNull(captured.currentPicture?.invoke()?.bitmap)
        }

    /** Eviction preserves a private selection token; Clear and App Lock revoke its launcher retention authority. */
    @Test fun trimmedPrivatePinSelectionIsStableUntilClearOrLock() =
        runBlocking {
            val app = app()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.RED)) { true })
            val dm = ChatListItem(group("dm"), null, "contact", 2, null)
            val captured = app.pinnedConversationPresentation("a", dm, "Local")
            val before = checkNotNull(captured.currentPicture?.invoke())
            assertTrue(before.identity != null)
            AvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
            val evicted = checkNotNull(captured.currentPicture?.invoke())
            assertNull(evicted.bitmap)
            assertTrue(before.identity in evicted.missingIdentities)
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })
            assertTrue(checkNotNull(captured.currentPicture?.invoke()).missingIdentities.isEmpty())
            app.setAppLockScreenVisibleForTest(true)
            assertTrue(checkNotNull(captured.currentPicture?.invoke()).missingIdentities.isEmpty())
        }

    /** Clearing a private choice uses cached native public pixels without reviving private or locked-account pixels. */
    @Test fun clearedPrivatePinKeepsItsCurrentNativePublicFallback() =
        runBlocking {
            val app = app()
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.RED)) { true })
            val asset = storedAsset("public-picture")
            val captured = app.pinnedConversationPresentation("a", directItem(asset), "Local")
            assertEquals(Color.RED, checkNotNull(captured.currentPicture?.invoke()?.bitmap).getPixel(0, 0))
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })
            AvatarImageLoader.loadStored(checkNotNull(asset.cacheKey("a")), AvatarImageLoader.currentCacheLifetime()) {
                contactPicturePng(Color.GREEN)
            }
            assertEquals(Color.GREEN, checkNotNull(captured.currentPicture?.invoke()?.bitmap).getPixel(0, 0))
            assertEquals("contact", captured.contact)
            app.setAppLockScreenVisibleForTest(true)
            assertNull(captured.currentPicture?.invoke()?.bitmap)
        }

    /**
     * #3635: a Clear retires only the contact's private pixels, so the card's final write shows the stored picture
     * their chat row shows instead of initials. The app lock still shows the monogram.
     */
    @Test fun clearedPictureWritesTheStoredPictureTheChatRowShows() =
        runBlocking {
            val app = app()
            val asset = storedAsset("row-picture")
            val chats = ChatsController(app, initialAccountRef = "a", memberSnapshotLoader = { _, _ -> emptyList() })
            chats.publishItemsForTest(listOf(directItem(asset)))
            app.attachChatsController(chats)
            AvatarImageLoader.putCached(checkNotNull(asset.cacheKey("a")), solid(Color.GREEN))
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", picture(Color.RED)) { true })
            val private = checkNotNull(app.contactPictureStore.reference("a", "contact")).cacheKey
            assertEquals("the private picture wins", Color.RED, app.writtenCardPixel("contact"))

            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })

            assertNull("the Clear retired the private pixels", AvatarImageLoader.cachedImage(private))
            assertEquals("the card shows what the row shows", Color.GREEN, app.writtenCardPixel("contact"))
            app.setAppLockScreenVisibleForTest(true)
            assertEquals(notificationMonogramBackgroundColor(SEED), app.writtenCardPixel("contact"))
        }

    /** #3635: a cleared choice shows an archived direct chat's stored picture as well. */
    @Test fun clearedPictureWritesAnArchivedRowsStoredPicture() =
        runBlocking {
            val app = app()
            val asset = storedAsset("archived-picture")
            val chats = ChatsController(app, initialAccountRef = "a", memberSnapshotLoader = { _, _ -> emptyList() })
            chats.publishItemsForTest(visible = emptyList(), archived = listOf(directItem(asset)))
            app.attachChatsController(chats)
            AvatarImageLoader.putCached(checkNotNull(asset.cacheKey("a")), solid(Color.GREEN))

            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })

            assertEquals(Color.GREEN, app.writtenCardPixel("contact"))
        }

    /**
     * #3635: with no chat list holding the row, as for a push into a dead process or another account, a cleared
     * choice shows the stored picture the notification's late refresh decoded for that row. Behind the app lock the
     * refresh decodes and records nothing.
     */
    @Test fun clearedPictureWritesTheStoredPictureALateRefreshDecoded() =
        runBlocking {
            val app = app()
            val asset = storedAsset("late-picture")
            AvatarImageLoader.putCached(checkNotNull(asset.cacheKey("a")), solid(Color.GREEN))
            assertTrue(app.saveContactPrivateDetails("a", "contact", "Local", "", ContactPictureChange.Clear) { true })
            val refresh = app.lateRowIdentity("a", directItem(asset))
            val loader = checkNotNull(refresh.storedLoader)
            assertEquals("contact", refresh.contact)

            app.setAppLockScreenVisibleForTest(true)
            assertNull(loader())
            app.setAppLockScreenVisibleForTest(false)
            assertEquals("nothing recorded", notificationMonogramBackgroundColor(SEED), app.writtenCardPixel("contact"))

            assertEquals(Color.GREEN, checkNotNull(loader()).getPixel(0, 0))
            assertEquals(Color.GREEN, app.writtenCardPixel("contact"))
        }

    /** A narrow approval waits for both broad jobs without invalidating their other pins' pending presentation. */
    @Test fun approvalWaitsForBroadJobsWithoutSupersedingTheirRevision() =
        runBlocking {
            val app = app()
            val revision = app.privateField("pinnedShortcutPresentationRevision") as AtomicLong
            revision.set(7L)
            val publication = Job()
            val avatarRefresh = Job()
            app.setPrivateField("shareShortcutPublishJob", publication)
            app.setPrivateField("pinnedShortcutRefreshJob", avatarRefresh)
            val capability = PinnedConversationCapability("a", "ab".repeat(16), "account-token", "group-token")
            val approval = launch(start = CoroutineStart.UNDISPATCHED) { app.refreshApprovedPinnedShortcut(capability) }
            assertFalse(approval.isCompleted)
            assertEquals(7L, revision.get())
            publication.complete()
            assertFalse(approval.isCompleted)
            // A newer broad publication owns the final write; approval must not query or overwrite its row.
            revision.incrementAndGet()
            avatarRefresh.complete()
            approval.join()
            assertEquals(8L, revision.get())
        }

    /** An inactive owner's late approval cannot cancel the active account's pending pin refresh. */
    @Test fun inactiveApprovalLeavesBroadRevisionUnchanged() =
        runBlocking {
            val app = app()
            val revision = app.privateField("pinnedShortcutPresentationRevision") as AtomicLong
            revision.set(7L)
            val capability = PinnedConversationCapability("b", "ab".repeat(16), "account-token", "group-token")
            app.refreshApprovedPinnedShortcut(capability)
            assertEquals(7L, revision.get())
        }

    /** Observes production scheduling state without replacing the approval path with a parallel test implementation. */
    private fun WhiteNoiseAppState.privateField(name: String): Any? =
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)

    /** Holds the real AppState broad jobs at a deterministic boundary without invoking native or device work. */
    private fun WhiteNoiseAppState.setPrivateField(
        name: String,
        value: Any,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }

    /**
     * Writes a direct chat card naming [contact], drawn with a blue picture, through the final-write identity refresh
     * every card write takes, and returns the top-left pixel of the picture it ends on.
     */
    private fun WhiteNoiseAppState.writtenCardPixel(contact: String): Int {
        val context = RuntimeEnvironment.getApplication()
        val card =
            NotificationCompat
                .Builder(context, "contact-picture-test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setConversationIdentityImage(
                    NotificationIdentityImage(solid(Color.BLUE).asAndroidBitmap(), "Peer", SEED, contact),
                ).build()
        val written = withCurrentIdentityContact(context, card) { contactAvatarChoice("a", it) }
        return checkNotNull(written.getLargeIcon()?.loadDrawable(context)).toBitmap().getPixel(0, 0)
    }

    /** The account-scoped choice every notification and shortcut write reads, called as the presenter calls it. */
    @Suppress("UNCHECKED_CAST")
    private fun WhiteNoiseAppState.contactAvatarChoice(
        account: String,
        contact: String,
    ): Pair<Boolean, Bitmap?> =
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("contactAvatarOverride", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(this, account, contact) as Pair<Boolean, Bitmap?>

    /** MDK's ready stored copy of the peer's picture, under its own [reference]. */
    private fun storedAsset(reference: String) =
        AvatarAssetFfi(
            "peer",
            reference,
            AvatarAvailabilityFfi.READY,
            AvatarAcquisitionStateFfi.IDLE,
            1uL,
            1_024uL,
        )

    /** A direct chat with "contact" whose row shows [asset], the peer's stored picture. */
    private fun directItem(asset: AvatarAssetFfi) =
        ChatListItem(
            group("dm"),
            null,
            "contact",
            2,
            null,
            selectedAvatarAsset = asset,
            selectedPresentation =
                ConversationPresentationFfi(
                    title = PresentationTextFfi.Literal("Peer"),
                    avatar = SelectedAvatarFfi.RemoteImage("https://peer.example/avatar", "peer"),
                    titleSource = PresentationSourceFfi.PEER_PROFILE,
                    avatarSource = PresentationSourceFfi.PEER_PROFILE,
                    peerId = "contact",
                    resolution = PresentationResolutionFfi.CACHED,
                ),
        )

    /** A solid 2x2 image of [color]. */
    private fun solid(color: Int) = Bitmap.createBitmap(2, 2, ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

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
            unlinked = false,
        )

    private companion object {
        const val SEED = "contact-seed"
    }
}
