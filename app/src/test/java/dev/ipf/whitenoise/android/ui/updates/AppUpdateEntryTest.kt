package dev.ipf.whitenoise.android.ui.updates

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.updateTestInfo
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the actual top-bar integration plus distribution/version policy without loading profiles or updates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AppUpdateEntryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appState = updateEntryTestAppState(context)
    private val info = mutableStateOf(updateTestInfo())
    private var opened = 0

    /** Stored legacy dismissal does not hide the new nonintrusive availability entry. */
    @Test fun dismissedReleaseStillOpensSettingsWithoutStartingUpdate() {
        info.value = info.value.copy(dismissedVersion = info.value.latestVersion)
        render()
        composeRule.onNodeWithTag("appUpdate.openSettings").performClick()
        composeRule.runOnIdle {
            assertEquals(1, opened)
            assertEquals(AppSelfUpdateState.Idle, appState.appSelfUpdateState)
        }
    }

    /** Unknown and current data omit the entry; discovery of a genuinely newer version adds it. */
    @Test fun entryTracksActualVersionComparison() {
        info.value = info.value.copy(latestVersion = null)
        render()
        composeRule.onNodeWithTag("appUpdate.openSettings").assertDoesNotExist()
        composeRule.runOnIdle { info.value = info.value.copy(latestVersion = info.value.installedVersion) }
        composeRule.onNodeWithTag("appUpdate.openSettings").assertDoesNotExist()
        composeRule.runOnIdle { info.value = info.value.copy(latestVersion = "2099.1.2") }
        composeRule.onNodeWithTag("appUpdate.openSettings").assertExists()
    }

    /** Important status comes from the native release-distance policy, including an old dismissal. */
    @Test fun importantUpdateAnnouncesProductionSeverity() {
        info.value = info.value.copy(releasesBehind = 3, dismissedVersion = info.value.latestVersion)
        render()
        composeRule.onNodeWithTag("appUpdate.openSettings").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.app_update_persistent_title),
            ),
        )
    }

    /** Search owns its own bar actions and never competes with an update entry. */
    @Test fun searchSuppressesUpdateEntry() {
        render(search = true)
        composeRule.onNodeWithTag("appUpdate.openSettings").assertDoesNotExist()
    }

    /** A Play/store-managed capability remains hidden even with a newer release in memory. */
    @Test fun storeManagedTopBarOmitsUpdateEntry() {
        render(enabled = false)
        composeRule.onNodeWithTag("appUpdate.openSettings").assertDoesNotExist()
    }

    /** The production default, not a test override, derives visibility from the installed build flavor. */
    @Test fun defaultTopBarHonorsBuildFlavor() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    appState,
                    false,
                    "",
                    remember { FocusRequester() },
                    {},
                    {},
                    {},
                    {},
                    { opened++ },
                    {},
                    updateInfo = info.value,
                )
            }
        }
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            composeRule.onNodeWithTag("appUpdate.openSettings").assertExists()
        } else {
            composeRule.onNodeWithTag("appUpdate.openSettings").assertDoesNotExist()
        }
    }

    /** Numeric comparison handles older/equal releases and the capability gate without consulting dismissal. */
    @Test fun availabilityPolicyRetainsRealVersionFacts() {
        assertTrue(showAppUpdateEntry(updateTestInfo(), true))
        assertFalse(showAppUpdateEntry(updateTestInfo(), false))
        assertFalse(showAppUpdateEntry(updateTestInfo().copy(latestVersion = "2098.12.31"), true))
        assertFalse(showAppUpdateEntry(updateTestInfo().copy(latestVersion = "2099.1.1"), true))
        assertTrue(showAppUpdateEntry(updateTestInfo().copy(dismissedVersion = "2099.1.2"), true))
    }

    /** Renders the actual production top bar; account-free data avoids any native/profile operation. */
    private fun render(
        enabled: Boolean = true,
        search: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    appState,
                    search,
                    "",
                    remember { FocusRequester() },
                    {},
                    {},
                    {},
                    {},
                    { opened++ },
                    {},
                    updateInfo = info.value,
                    selfUpdateEnabled = enabled,
                )
            }
        }
    }
}

/** A platform-services-disabled state object with no account/profile identities to request. */
internal fun updateEntryTestAppState(context: Context): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore.forContext(context),
        accountIdHexResolver = { null },
        accounts = emptyList(),
        activeAccountRef = "",
    )
