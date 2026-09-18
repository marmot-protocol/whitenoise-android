package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat list's slow-start signal and the terminal member-fetch classification behind #2626 and #2629:
 * a bare spinner gains its copy only for the bind that is still loading, and an unknown group ends retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListStartupTest {
    private val controllers = mutableListOf<ChatsController>()

    @After
    fun tearDown() {
        controllers.forEach(ChatsController::onCleared)
        controllers.clear()
    }

    /** MarmotKit's unknown-group refusal is terminal wherever it sits in the cause chain. */
    @Test
    fun unknownGroupIsTerminal() {
        assertTrue(isTerminalMemberFetchFailure(MarmotKitException.UnknownGroup("group")))
        val wrapped = IllegalStateException("wrapped", MarmotKitException.UnknownGroup("group"))
        assertTrue(isTerminalMemberFetchFailure(wrapped))
    }

    /** Ordinary read failures and other engine errors keep the retry schedule. */
    @Test
    fun otherFailuresAreRetried() {
        assertFalse(isTerminalMemberFetchFailure(IllegalStateException("transient")))
        assertFalse(isTerminalMemberFetchFailure(MarmotKitException.UnknownAccount("account")))
    }

    /** A bind still loading after the threshold reports the slow start for exactly that bind. */
    @Test
    fun slowStartIsReportedForTheLoadingBind() {
        val controller = controller()
        assertTrue(controller.isLoading)
        assertFalse(controller.startupTakingLonger)

        runBlocking { controller.watchSlowChatListStartup(controller.bindEpoch, delayMillis = 0L) }

        assertTrue(controller.startupTakingLonger)
    }

    /** A timer from a superseded bind cannot mark the current one as slow. */
    @Test
    fun staleBindEpochDoesNotReportSlowStart() {
        val controller = controller()

        runBlocking { controller.watchSlowChatListStartup(controller.bindEpoch - 1L, delayMillis = 0L) }

        assertFalse(controller.startupTakingLonger)
    }

    private fun controller(): ChatsController {
        val appState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { null },
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = ACCOUNT_REF,
                            accountIdHex = ACCOUNT_ID,
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                        ),
                    ),
                activeAccountRef = ACCOUNT_REF,
            )
        return ChatsController(appState, ACCOUNT_REF, { Long.MAX_VALUE }) { _, _ -> emptyList() }.also(controllers::add)
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val ACCOUNT_ID = "alice-id"
    }
}
