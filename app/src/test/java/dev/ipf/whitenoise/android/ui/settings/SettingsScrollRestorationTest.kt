package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h560dp-mdpi")
class SettingsScrollRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val help = context.getString(R.string.help)
    private val back = context.getString(R.string.back)

    @Test
    fun toolbarBackRestoresTheSameSettingsViewport() {
        mountSettings()
        val originalBounds = scrollToAndOpenHelp()

        composeRule.onNodeWithContentDescription(back).performClick()

        assertRestoredHelpBounds(originalBounds)
    }

    @Test
    fun systemBackRestoresTheSameSettingsViewport() {
        mountSettings()
        val originalBounds = scrollToAndOpenHelp()

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        assertRestoredHelpBounds(originalBounds)
    }

    @Test
    fun savedStateWhileOnDetailRestoresTheSettingsViewport() {
        val restorationTester = StateRestorationTester(composeRule)
        mountSettings(restorationTester)
        val originalBounds = scrollToAndOpenHelp()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithContentDescription(back).performClick()

        assertRestoredHelpBounds(originalBounds)
    }

    @Test
    fun diagnosticsAndMultiLevelDetailsPreserveTheSettingsViewport() {
        var detail by mutableStateOf<SettingsDetail?>(null)
        var diagnosticsOpen by mutableStateOf(false)
        val appState = testAppState().also { it.updateDeveloperMode(true) }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.6f)) {
                WhiteNoiseTheme {
                    var homeViewport by
                        rememberSaveable(stateSaver = SettingsHomeViewport.Saver) {
                            mutableStateOf(SettingsHomeViewport.Top)
                        }
                    if (diagnosticsOpen) {
                        Text("Diagnostics route")
                    } else {
                        SettingsScreen(
                            appState = appState,
                            onBackToChats = {},
                            onOpenDiagnostics = { diagnosticsOpen = true },
                            onOpenSupportChat = {},
                            detail = detail,
                            onDetailChange = { detail = it },
                            homeViewport = homeViewport,
                            onHomeViewportChange = { homeViewport = it },
                        )
                    }
                }
            }
        }
        val originalBounds = scrollToAndOpenHelp()
        composeRule.onNodeWithText(context.getString(R.string.about_and_licenses)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.developer)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.diagnostics)).performClick()
        composeRule.runOnIdle { diagnosticsOpen = false }

        repeat(3) {
            composeRule.onNodeWithContentDescription(back).performClick()
        }

        assertRestoredHelpBounds(originalBounds)
    }

    @Test
    fun aNewSettingsVisitStartsAtTheTop() {
        var showSettings by mutableStateOf(true)
        var visitId by mutableStateOf(0)
        mountSettings(showSettings = { showSettings }, visitId = { visitId })
        scrollToAndOpenHelp()

        composeRule.runOnIdle { showSettings = false }
        composeRule.runOnIdle {
            visitId += 1
            showSettings = true
        }

        composeRule.onNodeWithText(context.getString(R.string.account)).fetchSemanticsNode()
    }

    @Test
    fun keyedSectionWinsWhenTheSectionSetChanges() {
        val viewport =
            SettingsHomeViewport(
                section = SettingsHomeSection.Support,
                fallbackIndex = 3,
                scrollOffset = 17,
            )
        val sections =
            listOf(
                SettingsHomeSection.Account,
                SettingsHomeSection.Support,
                SettingsHomeSection.BuildInfo,
            )

        assertEquals(1, viewport.resolveIndex(sections))
    }

    @Test
    fun shellEventsPreserveOnlyTheCurrentDiagnosticsVisit() {
        val viewport =
            SettingsHomeViewport(
                section = SettingsHomeSection.Support,
                fallbackIndex = 2,
                scrollOffset = 17,
            )

        assertEquals(
            viewport,
            reduceSettingsHomeViewport(viewport, SettingsHomeViewportEvent.OpenDiagnostics),
        )
        listOf(
            SettingsHomeViewportEvent.OpenNewSettingsVisit,
            SettingsHomeViewportEvent.ExitSettings,
            SettingsHomeViewportEvent.OpenConversation,
            SettingsHomeViewportEvent.ChangeAccount,
        ).forEach { event ->
            assertEquals(event.name, SettingsHomeViewport.Top, reduceSettingsHomeViewport(viewport, event))
        }
    }

    private fun mountSettings(
        restorationTester: StateRestorationTester? = null,
        showSettings: () -> Boolean = { true },
        visitId: () -> Int = { 0 },
    ) {
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.6f)) {
                WhiteNoiseTheme {
                    var homeViewport by
                        rememberSaveable(visitId(), stateSaver = SettingsHomeViewport.Saver) {
                            mutableStateOf(SettingsHomeViewport.Top)
                        }
                    if (showSettings()) {
                        var detail by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }
                        val appState = remember { testAppState() }
                        SettingsScreen(
                            appState = appState,
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
            }
        }
        if (restorationTester == null) {
            composeRule.setContent(content)
        } else {
            restorationTester.setContent(content)
        }
    }

    private fun scrollToAndOpenHelp(): Rect {
        val helpNode = composeRule.onNodeWithText(help)
        helpNode.performScrollTo()
        val bounds = helpNode.fetchSemanticsNode().boundsInRoot
        helpNode.performClick()
        return bounds
    }

    private fun assertRestoredHelpBounds(expected: Rect) {
        composeRule.waitForIdle()
        val actual = composeRule.onNodeWithText(help).fetchSemanticsNode().boundsInRoot
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected.top - actual.top) <= 1f)
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "no-such-account",
        )
}
