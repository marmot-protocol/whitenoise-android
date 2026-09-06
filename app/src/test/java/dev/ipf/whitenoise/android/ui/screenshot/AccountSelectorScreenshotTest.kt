package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.ui.account.ACCOUNT_SELECTOR_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.account.AccountSelectorContent
import dev.ipf.whitenoise.android.ui.account.accountSelectorState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class AccountSelectorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multiAccountSelectorDark() {
        val state =
            accountSelectorState(
                accounts = listOf(account("personal"), account("work")),
                activeAccountRef = "personal",
                refreshing = false,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AccountSelectorContent(
                        state = state,
                        displayName = { accountId -> if (accountId == "hex-personal") "Personal" else "Work" },
                        shortNpub = { accountId -> "npub1${accountId.removePrefix("hex-").padEnd(10, 'x')}" },
                        avatarUrl = { null },
                        unreadCountForAccount = { label -> if (label == "work") 4uL else 0uL },
                        onSwitchAccount = {},
                        onAddAccount = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/account_selector_multi_account_dark.png")
    }

    /** Captures the populated first frame while its lifecycle-bound refresh remains in flight. */
    @Test
    fun populatedAccountSelectorWhileRefreshingDark() {
        val state =
            accountSelectorState(
                accounts = listOf(account("personal"), account("work")),
                activeAccountRef = "personal",
                refreshing = true,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AccountSelectorContent(
                        state = state,
                        displayName = { accountId -> if (accountId == "hex-personal") "Personal" else "Work" },
                        shortNpub = { accountId -> "npub1${accountId.removePrefix("hex-").padEnd(10, 'x')}" },
                        avatarUrl = { null },
                        unreadCountForAccount = { label -> if (label == "work") 4uL else 0uL },
                        onSwitchAccount = {},
                        onAddAccount = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/account_selector_refreshing_with_snapshot_dark.png")
    }

    private fun account(label: String): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "hex-$label",
            localSigning = true,
            signedOut = false,
            running = true,
            externalSigning = false,
        )
}
