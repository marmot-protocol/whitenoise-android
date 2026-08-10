package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class AccountKeysPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun publicIdentityUsesNpubAndDoesNotRenderAccountIdHex() {
        val appState = appStateWithNpub(CANONICAL_NPUB)

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AccountKeysScreen(appState = appState, onBack = {})
                }
            }
        }

        composeRule
            .onNodeWithText(IdentityFormatter.short(CANONICAL_NPUB, prefix = 10, suffix = 8), substring = true)
            .assertExists()
        composeRule.onNodeWithText(ACCOUNT_HEX).assertDoesNotExist()
    }

    private fun appStateWithNpub(npub: String): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
            )
        seedNpub(appState, ACCOUNT_HEX, npub)
        return appState
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun seedNpub(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
        npub: String,
    ) {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
        field.isAccessible = true
        val cache = field.get(appState) as BoundedNpubCache
        cache.put(accountIdHex, npub)
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val CANONICAL_NPUB = "npub1qy352hw5xrsq5k6x5t5vnpqx4lhfv3q8jqk9x0h5q6x5t5vnpq"
    }
}
