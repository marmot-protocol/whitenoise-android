package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the real screen puts the update row, driven by the update state it actually reads.
 *
 * `settingsHomeState` is pure and covered by [SettingsHomeStateTest], and the screenshots render a
 * fixture that supplies the placement itself. Neither reaches the call site, so a wiring that asked
 * the wrong question — a dismissal flag, say, rather than availability — would pass both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SettingsUpdatePlacementTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredUpdateState() {
        updatePreferences().edit().clear().commit()
    }

    /** With a newer release recorded, the row leads: it sits above the account group. */
    @Test
    fun anAvailableUpdateLeadsTheRealSettingsScreen() {
        storeLatestVersion("2999.1.1")
        mountSettings()
        val update = composeRule.onNodeWithTag("settings.app_updates").fetchSemanticsNode().boundsInRoot
        val account = composeRule.onNodeWithTag("settings.section.Account").fetchSemanticsNode().boundsInRoot
        assertTrue("an available update must lead the screen: $update vs $account", update.top < account.top)
    }

    /**
     * With nothing newer recorded the row is not in the first screenful at all: it has moved below the
     * groups, and only appears once the list is scrolled to it.
     *
     * Asserted by presence rather than by comparing bounds, because scrolling the row into view takes
     * the account group out of the semantics tree — the comparison would be between one node and one
     * that no longer exists.
     */
    @Test
    fun withNothingNewerTheRowLeavesTheFirstScreenful() {
        mountSettings()
        composeRule.onNodeWithTag("settings.section.Account").assertExists()
        composeRule.onNodeWithTag("settings.app_updates").assertDoesNotExist()

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("settings.app_updates"))
        composeRule.onNodeWithTag("settings.app_updates").assertExists()
    }

    private fun storeLatestVersion(version: String) {
        updatePreferences().edit().putString("latest_version", version).commit()
    }

    private fun updatePreferences() = context.getSharedPreferences("darkmatter_app_updates", Context.MODE_PRIVATE)

    private fun mountSettings() {
        composeRule.setContent {
            WhiteNoiseTheme {
                var detail by mutableStateOf<SettingsDetail?>(null)
                var homeViewport by mutableStateOf(SettingsHomeViewport.Top)
                SettingsScreen(
                    appState = appState(),
                    onBackToChats = {},
                    onOpenDiagnostics = {},
                    onOpenSupportChat = {},
                    detail = detail,
                    onDetailChange = { detail = it },
                    homeViewport = homeViewport,
                    onHomeViewportChange = { homeViewport = it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "no-such-account",
        )
}
