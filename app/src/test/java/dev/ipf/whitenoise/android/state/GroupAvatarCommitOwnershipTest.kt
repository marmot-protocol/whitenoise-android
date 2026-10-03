package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAvatarCommitOwnershipTest {
    @Test
    fun editorRetiredWhileWaitingForGroupCommitDoesNotWriteOrReportFailure() =
        runTest {
            val state = appState()
            val group = conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR)
            val controller = ConversationController(state, group)
            val releaseLock = CompletableDeferred<Unit>()
            val holder = launch { state.withGroupCommitLock(ACCOUNT, group.groupIdHex) { releaseLock.await() } }
            runCurrent()
            var current = true
            val result =
                async {
                    controller.updateGroupAvatarUrl(
                        ScopedGroupImageMutation(NEW_AVATAR) { current },
                        commitIfCurrent = { current },
                    )
                }
            runCurrent()
            assertFalse(result.isCompleted)
            current = false
            releaseLock.complete(Unit)
            holder.join()
            assertFalse(result.await())
            assertEquals(OLD_AVATAR, controller.group.avatarUrl)
            // Native access would fail in this fixture and set lastMutationError.
            assertNull(controller.lastMutationError)
            assertFalse(controller.mutationInFlight)
        }

    @Test
    fun alreadyRetiredAttemptDoesNotReachNativeWrite() =
        runTest {
            val controller =
                ConversationController(
                    appState(),
                    conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR),
                )
            assertFalse(
                controller.updateGroupAvatarUrl(
                    ScopedGroupImageMutation(NEW_AVATAR) { false },
                    commitIfCurrent = { false },
                ),
            )
            assertEquals(OLD_AVATAR, controller.group.avatarUrl)
            assertNull(controller.lastMutationError)
        }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT,
        )

    private companion object {
        const val ACCOUNT = "personal"
        const val OLD_AVATAR = "https://blossom.example/old.jpg"
        const val NEW_AVATAR = "https://blossom.example/new.jpg"
    }
}
