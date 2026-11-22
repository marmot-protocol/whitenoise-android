package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings under the app lock, for as long as the shell itself stays composed behind it. On master nothing of
 * Settings was composed then: not its screen, not its Back handler and not its state, so a Back under the lock could
 * not move the Settings screen hidden behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellSettingsLockContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /** Under the lock the Settings screen is not composed and Back does not reach it; unlocking shows it as it was. */
    @Test
    fun underTheLockSettingsComposesNothingAndIgnoresBack() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance) }
            shell.awaitCondition { appearanceComposed() }

            composeRule.runOnIdle { fixture.appState.setAppLockScreenVisibleForTest(true) }
            shell.settle()
            assertFalse("nothing of the Settings screen is composed under the lock", appearanceComposed())
            Espresso.pressBackUnconditionally()
            shell.settle()
            assertEquals(
                "a Back under the lock must not move the Settings screen behind it",
                SettingsDetail.Appearance.name,
                holder.sectionState.settingsDetailName,
            )

            composeRule.runOnIdle { fixture.appState.setAppLockScreenVisibleForTest(false) }
            shell.awaitCondition { appearanceComposed() }
            assertTrue(appearanceComposed())
        }
    }

    /** True while the Appearance screen's own colours group is composed, which no other screen has. */
    private fun appearanceComposed(): Boolean {
        val nodes = composeRule.onAllNodes(hasTestTag(APPEARANCE_COLORS_TAG), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val APPEARANCE_COLORS_TAG = "appearance.colors.group"
    }
}
