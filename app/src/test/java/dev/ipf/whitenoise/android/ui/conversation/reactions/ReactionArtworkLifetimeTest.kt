package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.nativeBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** A mounted image effect must retire with its controller even if the surrounding composition remains mounted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionArtworkLifetimeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Releasing a suspended native lookup after teardown cannot request a second source or publish any artwork. */
    @Test
    @Suppress("UNCHECKED_CAST", "LongMethod") // Keep the mounted lifecycle interleaving and binding continuation together.
    fun clearedControllerStopsTheMountedArtworkLoop() {
        val requests = CopyOnWriteArrayList<String>()
        val pending = AtomicReference<Continuation<Any?>?>()
        val native =
            nativeBoundary { method, args ->
                if (method == "reactionAttachment") {
                    requests += args[2] as String
                    pending.set(args.last() as Continuation<Any?>)
                    COROUTINE_SUSPENDED
                } else {
                    null
                }
            }
        val state =
            conversationTimelineTestAppState(
                ConversationLiveSubscriptions(
                    { _, _, _ -> error("no subscription") },
                    { _, _ -> error("no subscription") },
                ),
                marmotRuntime = AppMarmotRuntime("generated-artwork-lifetime", native),
                marmotIoDispatcher = Dispatchers.Unconfined,
            )
        val controller =
            ConversationController(state, conversationTimelineTestGroup(), conversationTimelineMemberSnapshot())
        var mounted by mutableStateOf(true)
        try {
            composeRule.setContent {
                if (mounted) {
                    val artwork =
                        rememberReactionArtwork(
                            controller,
                            state,
                            listOf(
                                ReactionTally(":one:", 1, false, "first"),
                                ReactionTally(":two:", 1, false, "second"),
                            ),
                        )
                    Text("images:${artwork.size}")
                }
            }
            composeRule.waitUntil(10_000L) { pending.get() != null }
            composeRule.runOnIdle {
                controller.onCleared()
                requireNotNull(pending.getAndSet(null)).resume(null)
            }
            composeRule.waitForIdle()
            assertEquals(listOf("first"), requests)
            composeRule.onNodeWithText("images:0").assertExists()
        } finally {
            composeRule.runOnIdle { mounted = false }
            pending.getAndSet(null)?.resume(null)
            controller.onCleared()
            state.mutationsScope.cancel()
        }
    }
}
