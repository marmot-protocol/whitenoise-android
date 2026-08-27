package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val robot by lazy { AccountKeysRobot() }

    @Test
    fun publicIdentityUsesNpubAndDoesNotRenderAccountIdHex() {
        robot.render()

        composeRule
            .onNodeWithText(IdentityFormatter.short(CANONICAL_NPUB, prefix = 10, suffix = 8), substring = true)
            .assertExists()
        composeRule.onNodeWithText(ACCOUNT_HEX).assertDoesNotExist()
    }

    @Test
    fun destructiveActionIsRenderedAndTraversedAfterRoutineAction() {
        robot.render()

        val signOutNode =
            composeRule
                .onNodeWithTag(SIGN_OUT_ACTION_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val wipeNode =
            composeRule
                .onNodeWithTag(WIPE_ACTION_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()

        assertTrue(
            "The destructive action must render after Sign Out",
            signOutNode.boundsInRoot.top < wipeNode.boundsInRoot.top,
        )
        val dangerGap = wipeNode.boundsInRoot.top - signOutNode.boundsInRoot.bottom
        val minimumDangerGap = with(composeRule.density) { 20.dp.toPx() }
        assertTrue("The destructive action needs visible section separation", dangerGap >= minimumDangerGap)
        assertEquals(1f, signOutNode.config.getOrNull(SemanticsProperties.TraversalIndex))
        assertEquals(2f, wipeNode.config.getOrNull(SemanticsProperties.TraversalIndex))
    }

    @Test
    fun destructiveActionIsTheFinalKeyboardFocusTarget() {
        val focusManager = robot.render()
        val back = composeRule.onNodeWithContentDescription(app.getString(R.string.back))
        val signOut = composeRule.onNodeWithTag(SIGN_OUT_ACTION_TAG)
        val wipe = composeRule.onNodeWithTag(WIPE_ACTION_TAG)

        signOut.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        composeRule.runOnIdle { assertTrue(focusManager.moveFocus(FocusDirection.Next)) }
        wipe.assertIsFocused()
        composeRule.runOnIdle { assertTrue(focusManager.moveFocus(FocusDirection.Next)) }
        back.assertIsFocused()
    }

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
    fun largeFontRtlKeepsTerminalActionInsideTheSafeViewport() {
        robot.render(
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
            contentWindowInsets = WindowInsets(bottom = NAVIGATION_BAR_BOTTOM),
        )
        robot.scrollToWipe()
        val wipe =
            composeRule
                .onNodeWithTag(WIPE_ACTION_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val rootBottom =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom

        val safeBottomGap = rootBottom - wipe.boundsInRoot.bottom
        val minimumSafeBottomGap = with(composeRule.density) { (NAVIGATION_BAR_BOTTOM + 16.dp).toPx() }
        assertTrue(
            "The terminal action must clear the navigation bar plus content spacing",
            safeBottomGap >= minimumSafeBottomGap,
        )
    }

    private inner class AccountKeysRobot {
        fun render(
            fontScale: Float = 1f,
            layoutDirection: LayoutDirection = LayoutDirection.Ltr,
            contentWindowInsets: WindowInsets = WindowInsets(0.dp),
        ): FocusManager {
            lateinit var focusManager: FocusManager
            val appState = appStateWithNpub(CANONICAL_NPUB)
            composeRule.setContent {
                focusManager = LocalFocusManager.current
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    WhiteNoiseTheme {
                        Surface {
                            AccountKeysScreen(
                                appState = appState,
                                onBack = {},
                                contentWindowInsets = contentWindowInsets,
                            )
                        }
                    }
                }
            }
            return focusManager
        }

        fun scrollToWipe() {
            composeRule
                .onNode(hasScrollAction())
                .performScrollToNode(hasTestTag(WIPE_ACTION_TAG))
        }
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
        val NAVIGATION_BAR_BOTTOM = 48.dp
    }
}
