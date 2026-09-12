package dev.ipf.whitenoise.android.ui.account

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.updateQuickProfileCycling
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
class QuickProfileCycleTopBarNativeTest {
    @get:Rule val composeRule = createComposeRule()

    /** Native generation fencing prevents repeated pending taps from producing duplicate or premature success. */
    @Suppress("LongMethod")
    @Test
    fun repeatedPendingTapUsesNativeFenceAndOneActualDestinationToast() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fixture =
            NotificationBootstrapTestFixture(
                context,
                accounts =
                    listOf(
                        AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true),
                        AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true),
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
            app.updateQuickProfileCycling(true)
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
                        selfUpdateEnabled = false,
                    )
                }
            }
            composeRule.onNodeWithTag("chats.quickSwitch").performClick()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                reads.get() == 1
            }
            composeRule.onNodeWithTag("chats.quickSwitch").performClick()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                reads.get() >= 2
            }
            assertEquals("a", app.activeAccountRef)
            assertEquals(0, ShadowToast.shownToastCount())
            release.countDown()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                app.activeAccountRef == "b" && ShadowToast.shownToastCount() == 1
            }
            assertEquals(
                context.getString(R.string.quick_account_switched, app.accountDisplayNameCached("bb".repeat(32))),
                ShadowToast.getTextOfLatestToast(),
            )
        } finally {
            release.countDown()
            fixture.close()
        }
    }
}
