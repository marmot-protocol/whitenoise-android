package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A retained chat controller must never lend another account's private group image to a card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationFirstPostAvatarOwnerTest {
    /** The current controller's same-group image is eligible for first publication. */
    @Test
    fun matchingControllerCarriesItsReadyGroupAvatar() = checkOwner("account-a", expectedImage = true)

    /** Active-account publication can precede rebinding the retained controller. */
    @Test
    fun previousAccountControllerCannotLendItsSameGroupAvatar() = checkOwner("account-b", expectedImage = false)

    /** Reuses decoded encrypted media only from the matching account/group/hash cache key. */
    @Test
    fun matchingControllerCarriesItsReadyEncryptedGroupAvatar() {
        checkOwner("account-a", expectedImage = true, encrypted = true)
    }

    /** A same-group encrypted image owned by another account is never borrowed. */
    @Test
    fun previousAccountCannotLendEncryptedGroupAvatar() {
        checkOwner("account-b", expectedImage = false, encrypted = true)
    }

    /** Exercises AppState's production snapshot boundary with a real retained ChatsController. */
    private fun checkOwner(
        controllerAccount: String,
        expectedImage: Boolean,
        encrypted: Boolean = false,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val fixture = NotificationBootstrapTestFixture(context = context)
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = fixture.appState.draftStore,
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "account-a",
            )
        val chats = ChatsController(state, controllerAccount, memberSnapshotLoader = { _, _ -> emptyList() })
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val url = "https://profiles.example/private-group.png"
        try {
            AvatarImageLoader.putCached(url, image.asImageBitmap())
            GroupAvatarImageLoader.putCached(
                encryptedGroupAvatarCacheKey(controllerAccount, "group-a", "test-hash"),
                image.asImageBitmap(),
            )
            chats.setChatListVisible(false)
            chats.applyChatListRow(
                notificationChatListRow().copy(
                    groupIdHex = "group-a",
                    avatarUrl = url.takeUnless { encrypted },
                    avatar =
                        if (encrypted) {
                            ChatListAvatarFfi("test-hash", "test-key", "test-nonce", "test-upload-key", "image/png")
                        } else {
                            null
                        },
                ),
            )
            chats.setChatListVisible(true)
            state.attachChatsController(chats)
            val snapshot = state.readyNotificationAvatars(fixture.update)
            if (expectedImage) assertSame(image, snapshot.groupAvatarBitmap) else assertNull(snapshot.groupAvatarBitmap)
        } finally {
            state.attachChatsController(null)
            chats.onCleared()
            AvatarImageLoader.clear()
            GroupAvatarImageLoader.clear()
            fixture.close()
        }
    }
}
