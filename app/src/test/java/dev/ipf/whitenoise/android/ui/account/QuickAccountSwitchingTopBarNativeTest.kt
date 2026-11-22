package dev.ipf.whitenoise.android.ui.account

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.requestQuickAccountSwitchTo
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual cycle-button wiring reaches native activation, including a repeated tap while its local read is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class QuickAccountSwitchingTopBarNativeTest {
    @get:Rule val composeRule = createComposeRule()

    /** The top bar's own fallback switch lands on the tapped account without a success toast. */
    @Test
    fun fallbackSwitchReachesOneDestinationWithoutToast() = assertRepeatedTapSwitchesSilently(callerHandler = false)

    /** A caller-supplied switch handler, as the main shell passes, lands on the tapped account without a toast. */
    @Test
    fun callerHandlerSwitchReachesOneDestinationWithoutToast() = assertRepeatedTapSwitchesSilently(callerHandler = true)

    /** Native generation fencing keeps a repeated pending tap to one destination; neither path shows a toast. */
    @Suppress("LongMethod")
    private fun assertRepeatedTapSwitchesSilently(callerHandler: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fixture =
            NotificationBootstrapTestFixture(
                context,
                accounts =
                    listOf(
                        AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true, false),
                        AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true, false),
                    ),
                emitStartupNotification = false,
                onPresentedChatList = { account ->
                    if (account == "b") {
                        reads.incrementAndGet()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    emptyList()
                },
            )
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            app.updateQuickAccountSwitching(true)
            ShadowToast.reset()
            composeRule.setContent {
                WhiteNoiseTheme {
                    ChatListTopBar(
                        app,
                        false,
                        "",
                        remember { FocusRequester() },
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                        onSwitchToAccount = if (callerHandler) { label -> callerSwitch(app, label) } else null,
                        selfUpdateEnabled = false,
                    )
                }
            }
            composeRule.onNodeWithTag(otherAccountAvatarTag("b"), useUnmergedTree = true).performTouchInput { click() }
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                reads.get() == 1
            }
            composeRule.onNodeWithTag(otherAccountAvatarTag("b"), useUnmergedTree = true).performTouchInput { click() }
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                reads.get() >= 2
            }
            assertEquals("a", app.activeAccountRef)
            release.countDown()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                app.activeAccountRef == "b"
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("b", app.activeAccountRef)
            assertEquals(0, ShadowToast.shownToastCount())
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    /** Mirrors the main shell: re-resolve the target, then hand it to the native activation owner. */
    private fun callerSwitch(
        app: WhiteNoiseAppState,
        targetLabel: String,
    ) {
        app.requestQuickAccountSwitchTo(targetLabel) { target ->
            app.launchMutation {
                app.setActiveAccount(target, preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS)
            }
        }
    }
}
