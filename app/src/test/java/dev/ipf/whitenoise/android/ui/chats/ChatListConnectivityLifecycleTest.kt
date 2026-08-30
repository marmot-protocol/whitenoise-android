package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListConnectionPhase
import dev.ipf.whitenoise.android.state.ChatListConnectionState
import dev.ipf.whitenoise.android.state.beginReadinessRefresh
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListConnectivityLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun healthyStopStartWithZeroRelaySampleRevalidatesWithoutRenderingAConnectivityTransition() {
        val lifecycleOwner = StartedLifecycleOwner()
        val connectionState =
            mutableStateOf(
                ChatListConnectionState(
                    accountRef = ACCOUNT,
                    runtimeGeneration = RUNTIME_GENERATION,
                    bindEpoch = 2L,
                    sessionAttemptId = 3L,
                    evidenceEpoch = 4L,
                    phase = ChatListConnectionPhase.Ready,
                ),
            )
        var revalidationCount = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val foregroundEpoch = rememberConnectivityForegroundEpoch()
                ConnectivityEdgeRefreshEffects(
                    effectOwner = connectionState,
                    activeAccountRef = ACCOUNT,
                    runtimeGeneration = RUNTIME_GENERATION,
                    hasValidatedInternet = true,
                    relaysConnected = false,
                    foregroundEpoch = foregroundEpoch,
                    revalidateConnectionReadiness = {
                        revalidationCount += 1
                        connectionState.value = connectionState.value.beginReadinessRefresh(presentAttempt = false)
                    },
                )
                val target =
                    connectivityBannerTarget(
                        hasValidatedInternet = true,
                        activeAccountRef = ACCOUNT,
                        runtimeGeneration = RUNTIME_GENERATION,
                        connectionState = connectionState.value,
                    )
                WhiteNoiseTheme {
                    ChatListInlineConnectivityIndicator(initialConnectivityBannerState(target).displayed)
                }
            }
        }
        composeRule.waitForIdle()
        val revalidationsBeforeResume = revalidationCount

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertEquals(revalidationsBeforeResume + 1, revalidationCount)
        assertEquals(ChatListConnectionPhase.Validating, connectionState.value.phase)
        composeRule.onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG).assertIsNotDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connecting)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connected)).assertDoesNotExist()
    }

    private class StartedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle = registry

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private companion object {
        const val ACCOUNT = "personal"
        const val RUNTIME_GENERATION = 9
    }
}
