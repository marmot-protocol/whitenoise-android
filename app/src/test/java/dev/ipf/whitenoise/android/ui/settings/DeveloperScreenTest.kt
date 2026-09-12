package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract of Developer Tools: the switch owns the debugging surfaces, Key Packages never depends on it, and
 * the build facts are the ones this copy was made from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class DeveloperScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val developerModeWrites = mutableListOf<Boolean>()
    private val streamingDebugWrites = mutableListOf<Boolean>()
    private var diagnostics = 0
    private var keyPackages = 0
    private var backCount = 0

    /** The developer switch reports its new value once per tap. */
    @Test
    fun developerSwitchWritesItsNewValue() {
        render(developerMode = false)

        composeRule.onNodeWithTag("developer.mode.switch").assertIsOff().performClick()

        composeRule.runOnIdle { assertEquals(listOf(true), developerModeWrites) }
    }

    /** With developer mode off the debugging surfaces are absent, and the warning is still shown. */
    @Test
    fun debuggingSurfacesAppearOnlyWithDeveloperModeOn() {
        render(developerMode = false)

        composeRule.onNodeWithTag("developer.warning").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.developer_debugging)).assertDoesNotExist()
        composeRule.onNodeWithTag("developer.streaming_debug").assertDoesNotExist()
        composeRule.onNodeWithTag("developer.diagnostics").assertDoesNotExist()
    }

    /** With developer mode on both debugging rows appear and report their own actions. */
    @Test
    fun debuggingRowsReportTheirOwnActions() {
        render(developerMode = true, streamingDebug = true)

        scrollTo("developer.streaming_debug")
        composeRule.onNodeWithTag("developer.streaming_debug").assertIsOn().performClick()
        scrollTo("developer.diagnostics")
        composeRule.onNodeWithTag("developer.diagnostics").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(false), streamingDebugWrites)
            assertEquals(1, diagnostics)
        }
    }

    /** Key Packages is a recovery destination, so it stays reachable while developer mode is off. */
    @Test
    fun keyPackagesStaysReachableWithDeveloperModeOff() {
        render(developerMode = false)

        composeRule.onNodeWithTag("developer.key_packages.row").performClick()

        composeRule.runOnIdle { assertEquals(1, keyPackages) }
    }

    /** The About group states the version, build number and MarmotKit revision of this build. */
    @Test
    fun buildFactsAreShownForThisBuild() {
        render(developerMode = false)

        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasText("0a5ab20"))
        composeRule.onNodeWithText(app.getString(R.string.about_version)).assertExists()
        composeRule.onNodeWithText("1.4.0").assertExists()
        composeRule.onNodeWithText("140").assertExists()
        composeRule.onNodeWithText("0a5ab20").assertExists()
    }

    /** Back returns to Settings through the caller. */
    @Test
    fun backInvokesTheCallerOncePerTap() {
        render(developerMode = false)

        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()

        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    /** Turning developer mode off hides debug controls without resetting the independently stored debug switch. */
    @Test
    fun togglingModePreservesStreamingAndKeyPackageRecovery() {
        val enabled = mutableStateOf(false)
        val streaming = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                DeveloperContent(
                    developerMode = enabled.value,
                    streamingDebug = streaming.value,
                    build = DeveloperBuildFacts("1.4.0", "140", "0a5ab20"),
                    onDeveloperModeChange = { enabled.value = it },
                    onStreamingDebugChange = {
                        streaming.value = it
                        streamingDebugWrites += it
                    },
                    onBack = {},
                    onOpenDiagnostics = {},
                    onOpenKeyPackages = { keyPackages++ },
                )
            }
        }
        composeRule.onNodeWithTag("developer.mode.switch").performClick()
        scrollTo("developer.streaming_debug")
        composeRule.onNodeWithTag("developer.streaming_debug").assertIsOn()
        scrollTo("developer.mode.switch")
        composeRule.onNodeWithTag("developer.mode.switch").performClick()
        composeRule.onNodeWithTag("developer.streaming_debug").assertDoesNotExist()
        scrollTo("developer.key_packages.row")
        composeRule.onNodeWithTag("developer.key_packages.row").performClick()
        scrollTo("developer.mode.switch")
        composeRule.onNodeWithTag("developer.mode.switch").performClick()
        scrollTo("developer.streaming_debug")
        composeRule.onNodeWithTag("developer.streaming_debug").assertIsOn()
        composeRule.runOnIdle {
            assertEquals(emptyList<Boolean>(), streamingDebugWrites)
            assertEquals(1, keyPackages)
        }
    }

    /** The production wrapper writes developer mode while retaining the separately persisted streaming choice. */
    @Test
    fun productionWrapperPreservesIndependentStreamingPreference() {
        app
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "alice",
            )
        appState.updateDeveloperMode(true)
        appState.updateStreamingDebugMode(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                DeveloperScreen(appState, onBack = {}, onOpenDiagnostics = {}, onOpenKeyPackages = {})
            }
        }
        composeRule.onNodeWithTag("developer.mode.switch").assertIsOn().performClick()
        composeRule.runOnIdle {
            assertEquals(false, appState.developerMode)
            assertEquals(true, appState.streamingDebugMode)
        }
        composeRule.onNodeWithTag("developer.mode.switch").performClick()
        scrollTo("developer.streaming_debug")
        composeRule.onNodeWithTag("developer.streaming_debug").assertIsOn()
    }

    /** Staging builds retain their variant badge beside the build facts. */
    @Test
    fun stagingBuildRetainsItsBadge() {
        render(developerMode = false, staging = true)
        scrollTo("developer.staging")
        composeRule.onNodeWithText(app.getString(R.string.settings_staging_badge)).assertExists()
    }

    /** Normal builds never advertise staging. */
    @Test
    fun normalBuildDoesNotShowAStagingBadge() {
        render(developerMode = false)
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasText("0a5ab20"))
        composeRule.onNodeWithTag("developer.staging").assertDoesNotExist()
    }

    /** Brings a lazy row into the compact viewport before interaction. */
    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag(tag))
    }

    /** Renders caller-owned production values without mocking the shared row controls. */
    private fun render(
        developerMode: Boolean,
        streamingDebug: Boolean = false,
        staging: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                DeveloperContent(
                    developerMode = developerMode,
                    streamingDebug = streamingDebug,
                    build = DeveloperBuildFacts("1.4.0", "140", "0a5ab20", staging),
                    onDeveloperModeChange = { developerModeWrites += it },
                    onStreamingDebugChange = { streamingDebugWrites += it },
                    onBack = { backCount++ },
                    onOpenDiagnostics = { diagnostics++ },
                    onOpenKeyPackages = { keyPackages++ },
                )
            }
        }
    }
}
