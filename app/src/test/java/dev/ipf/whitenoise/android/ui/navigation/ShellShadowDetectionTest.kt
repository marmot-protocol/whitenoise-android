package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shadow comparison is not vacuous: a route write that bypasses the navigator is caught by the per-composition
 * check of the mounted production shell, with both whole values recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellShadowDetectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = MountedContractShell(composeRule)

    /** An open conversation agrees, then a Settings write without an event is recorded as a composition mismatch. */
    @Test
    fun aRouteWriteThatBypassesTheNavigatorIsCaughtAtTheNextComposition() {
        ShellShadowLog.reset()
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            assertTrue(ShellShadowLog.comparisons > 0)
            assertEquals(0L, ShellShadowLog.mismatchCount)

            composeRule.runOnIdle { holder.sectionState.sectionName = MainSection.Settings.name }
            shell.settle()

            val mismatch = ShellShadowLog.mismatches.first()
            assertEquals("composition", mismatch.origin)
            assertTrue(mismatch.navigator.none { it is ShellRoute.Settings })
            assertTrue(mismatch.legacy.contains(ShellRoute.Settings(null)))
            assertEquals(conversationRoot(mismatch.navigator), conversationRoot(mismatch.legacy))
        }
        ShellShadowLog.reset()
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
    }
}
