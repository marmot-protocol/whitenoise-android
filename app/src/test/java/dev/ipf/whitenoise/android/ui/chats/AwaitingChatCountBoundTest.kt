package dev.ipf.whitenoise.android.ui.chats

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.state.monotonicNowMs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The just-linked notice ends by itself when no chat count ever arrives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AwaitingChatCountBoundTest {
    @get:Rule val compose = createComposeRule()

    /** A link with no count stops awaiting one after [CHAT_COUNT_WAIT_MS]. */
    @Test
    fun theWaitEndsAfterItsBound() {
        val linkedAt = monotonicNowMs()
        compose.setContent { Text(if (rememberAwaitingChatCount(linkedAt)) "waiting" else "done") }
        compose.onNodeWithText("waiting").assertExists()

        compose.mainClock.advanceTimeBy(CHAT_COUNT_WAIT_MS + 1_000L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHAT_COUNT_WAIT_MS + 1_000L))
        compose.waitForIdle()
        compose.onNodeWithText("done").assertExists()
    }

    /** No link seen means nothing to await. */
    @Test
    fun noLinkAwaitsNothing() {
        compose.setContent { Text(if (rememberAwaitingChatCount(null)) "waiting" else "done") }
        compose.onNodeWithText("done").assertExists()
    }
}
