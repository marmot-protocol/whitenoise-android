package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeHost
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The compact invitation confirmation overlays the transcript below the playback strip. Capture it in light,
 * dark, AMOLED and large-text RTL themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class TopNoticePlacementScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun light() = capture("light", dark = false)

    /** Standard dark theme. */
    @Test
    fun dark() = capture("dark", dark = true)

    /** AMOLED black theme, where banner surfaces need their outline. */
    @Test
    fun amoled() = capture("amoled", dark = true, amoled = true)

    /** Doubled text in a right-to-left locale, where the region stays bounded. */
    @Test
    fun largeRtl() = capture("large_rtl", dark = false, largeRtl = true)

    @Test
    fun withActionableErrorAndPartialResult() = capture("with_actionable_feedback", dark = true, withFeedback = true)

    /** Captures a conversation-scoped success overlay in the hosted shell. */
    private fun capture(
        suffix: String,
        dark: Boolean,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        withFeedback: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notice =
            TransientNotice(
                id = 1L,
                title = AppText.Resource(R.string.toast_invite_accepted),
                conversation = ConversationNoticeDestination(ACCOUNT, GROUP),
                kind = TransientNoticeKind.Confirmation,
            )
        val partial =
            notice
                .copy(
                    id = 2L,
                    title = AppText.Plain("Invited some members"),
                    detail = AppText.Plain("One member still needs an invite"),
                    kind = TransientNoticeKind.Partial,
                ).takeIf { withFeedback }
        val host = TopNoticeHost(SnackbarHostState(), notice = { notice }, partial = { partial })
        composeRule.setContent {
            if (withFeedback) {
                LaunchedEffect(host) {
                    host.snackbarHostState.showSnackbar(
                        ToastSnackbarVisuals(
                            message = context.getString(R.string.toast_send_connection_failed),
                            copyable = true,
                            copyText = "operation=MESSAGE_SEND",
                        ),
                    )
                }
            }
            CompositionLocalProvider(
                LocalTopNoticeHost provides host,
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.fillMaxSize()) {
                        ShellTransientNoticeLayout(notice = notice) { HostedConversation(notice) }
                    }
                }
            }
        }
        if (withFeedback) {
            composeRule.onNodeWithContentDescription(context.getString(R.string.copy)).assertExists()
            composeRule.onNodeWithText("One member still needs an invite").assertIsDisplayed()
        }
        composeRule.onNodeWithText(context.getString(R.string.toast_invite_accepted)).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/top_notice_placement_$suffix.png")
    }

    /** A conversation header, playback strip, the banner row over a transcript, and a composer at the bottom. */
    @Composable
    private fun HostedConversation(notice: TransientNotice) {
        Column(Modifier.fillMaxSize()) {
            WhiteNoiseTopBar(title = "Alpine group", onBack = {})
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("Reading aloud · Alex", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            ConversationTransientNoticeLayout(
                notice = notice,
                accountRef = ACCOUNT,
                groupIdHex = GROUP,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    "Latest message remains visible",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.navigationBarsPadding().padding(16.dp)) {
                    Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "group-a"
    }
}
