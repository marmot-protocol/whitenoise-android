package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Accepted staging retires its request even when the active account changes around close acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SharePickerMotionOwnerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun accountReplacementBeforeCloseAcceptanceStillRetiresTheRequest() = exercise(true)

    @Test fun accountReplacementDuringExitStillRetiresTheRequest() = exercise(false)

    private fun exercise(beforeAcceptance: Boolean) {
        val accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX), testAccount("second", "b3".repeat(32)))

        fun state(accountRef: String) =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                profiles = mutableMapOf(PEER_A to profile("Alice")),
                accounts = accounts,
                activeAccountRef = accountRef,
            )
        val first = state(ACCOUNT_REF)
        val second = state("second")
        val current = mutableStateOf(first)
        val accepted = CompletableDeferred<Boolean>()
        var stages = 0
        var dismissals = 0
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    ShareChatPickerFullScreen(
                        appState = current.value,
                        requestId = "accepted-share",
                        payload =
                            SharePayload(
                                text = "shared text",
                                streamUris = emptyList(),
                                intentMimeType = "text/plain",
                            ),
                        onDismiss = { dismissals++ },
                        onStage = { _, _ ->
                            stages++
                            accepted.await()
                        },
                        controllerBinder = { _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithText("Alice").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1)).performClick()
        rule.runOnIdle { assertEquals(1, stages) }
        if (beforeAcceptance) {
            rule.runOnIdle { current.value = second }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.runOnIdle { accepted.complete(true) }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle {
            assertEquals(0, dismissals)
            if (!beforeAcceptance) current.value = second
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle {
            assertEquals(1, stages)
            assertEquals(1, dismissals)
        }
    }
}
