package dev.ipf.whitenoise.android.ui.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back from a Settings screen as a reader sees it, frame by frame with the clock held. On master (checked against
 * checkpoint C, before the list pane moved onto the route display) the Settings screen being left is gone from the
 * first frame composed after Back, so its Back handler and its editable state never outlive the step back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellSettingsBackFrameContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /** After Back from Appearance no frame composes Appearance any more, and Settings home follows. */
    @Test
    fun theSettingsScreenLeftByBackIsGoneFromTheFirstFrame() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance) }
            shell.awaitCondition { appearanceComposed() }
            shell.settle()
            assertTrue(appearanceComposed())

            composeRule.mainClock.autoAdvance = false
            val composedAtFrame =
                try {
                    Espresso.pressBack()
                    (1..FRAMES_WATCHED).map {
                        composeRule.mainClock.advanceTimeByFrame()
                        shadowOf(Looper.getMainLooper()).idle()
                        appearanceComposed()
                    }
                } finally {
                    composeRule.mainClock.autoAdvance = true
                }
            shell.settle()

            val lingering = composedAtFrame.withIndex().filter { it.value }.map { it.index + 1 }
            println("shell-settings-back lingering frames=$lingering")
            assertEquals("frames after Back still composing Appearance", emptyList<Int>(), lingering)
            assertEquals(null, holder.sectionState.settingsDetailName)
            assertTrue("Back lands on Settings home", tagComposed(SETTINGS_HOME_CONTENT_TAG))
        }
    }

    /** True while the Appearance screen's own colours group is composed, which no other screen has. */
    private fun appearanceComposed(): Boolean = tagComposed(APPEARANCE_COLORS_TAG)

    /** True while a node carrying [tag] is composed, merged or not. */
    private fun tagComposed(tag: String): Boolean {
        val nodes = composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val APPEARANCE_COLORS_TAG = "appearance.colors.group"

        /** Longer than any route motion, so a lingering outgoing screen would be seen. */
        const val FRAMES_WATCHED = 30
    }
}
