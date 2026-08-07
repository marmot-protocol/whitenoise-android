package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ConnectivityBannerState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListTopBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun oneAccountConnectingDark() {
        render(accountCount = 1)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_one_account_connecting_dark.png")
    }

    @Test
    fun threeAccountsConnectingDark() {
        render(accountCount = 3)
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/chat_list_top_bar_three_accounts_connecting_dark.png")
    }

    private fun render(accountCount: Int) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TAG),
                    ) {
                        ChatListTopBar(
                            appState = remember { testAppState(accountCount) },
                            searchOpen = false,
                            searchQuery = "",
                            searchFocusRequester = remember { FocusRequester() },
                            onSearchQueryChange = {},
                            onSearchOpen = {},
                            onSearchClose = {},
                            onMic = {},
                            onOpenSettings = {},
                            onSwitchAccount = {},
                            connectivityState = ConnectivityBannerState.Connecting,
                        )
                    }
                }
            }
        }
    }

    private fun testAppState(accountCount: Int): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                (1..accountCount).map { index ->
                    AccountSummaryFfi(
                        label = if (index == 1) "personal" else "account-$index",
                        accountIdHex = index.toString(16).padStart(2, '0').repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    )
                },
            activeAccountRef = "personal",
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val TAG = "chat-list-top-bar-screenshot"
    }
}
