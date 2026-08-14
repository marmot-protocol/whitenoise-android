package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListFirstFrameProfileWarmTest {
    @Test
    fun cachedDmProfileIsMaterializedBeforeTheWarmBoundaryCompletes() =
        runTest {
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            var relayRefreshRequests = 0
            val appState =
                appState(
                    profileReader = {
                        readStarted.complete(Unit)
                        releaseRead.await()
                        peerProfile()
                    },
                    profileDisplayNameReader = { PEER_NAME },
                    profileRefreshRequest = { relayRefreshRequests += 1 },
                )

            val warm = async { appState.warmProfilePresentationsBlocking(listOf(PEER_ID)) }
            readStarted.await()

            assertFalse("the first-frame boundary must await the local profile read", warm.isCompleted)
            releaseRead.complete(Unit)
            warm.await()

            assertEquals(PEER_NAME, appState.chatMemberTitleCached(PEER_ID))
            assertEquals(PEER_AVATAR, appState.avatarUrl(PEER_ID))
            assertEquals("local materialization must not start relay refresh", 0, relayRefreshRequests)
        }

    private fun appState(
        profileReader: suspend (String) -> UserProfileMetadataFfi?,
        profileDisplayNameReader: suspend (String) -> String?,
        profileRefreshRequest: suspend (String) -> Unit,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = profileReader,
            profileDisplayNameReader = profileDisplayNameReader,
            profileRefreshRequest = profileRefreshRequest,
        )
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun peerProfile() =
        UserProfileMetadataFfi(
            name = "alice",
            displayName = PEER_NAME,
            about = null,
            picture = PEER_AVATAR,
            nip05 = null,
            lud16 = null,
        )

    private companion object {
        const val ACCOUNT_REF = "primary"
        val ACCOUNT_ID = "11".repeat(32)
        val PEER_ID = "22".repeat(32)
        const val PEER_NAME = "Alice"
        const val PEER_AVATAR = "https://profiles.example/alice.jpg"
    }
}
