package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation contract of Profile Keys: the npub value row, the masked private key, and the isolated wipe action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class AccountKeysPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** The public key row shows the full npub (middle-ellipsized visually) and never the account hex. */
    @Test
    fun publicIdentityUsesNpubAndDoesNotRenderAccountIdHex() {
        render()
        composeRule.onNodeWithTag("profile_keys.public_key_value", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(CANONICAL_NPUB).assertExists()
        composeRule.onNodeWithText(ACCOUNT_HEX).assertDoesNotExist()
    }

    /** The private key starts hidden and announces that state; the copy and export rows follow it. */
    @Test
    fun privateKeyStartsHiddenWithCopyAndExportRowsBelow() {
        render()
        composeRule.onNodeWithContentDescription(app.getString(R.string.private_key_hidden)).assertExists()
        composeRule.onNodeWithContentDescription(app.getString(R.string.show_private_key)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.copy_private_key)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.export_encrypted_private_key)).assertExists()
        composeRule.onNodeWithTag("profile_keys.export_raw").assertExists()
    }

    /** The destructive wipe action renders after the export group with visible section separation. */
    @Test
    fun destructiveActionIsRenderedAfterExportsWithSectionSeparation() {
        render()
        val exportNode = composeRule.onNodeWithTag("profile_keys.export_raw").assertIsDisplayed().fetchSemanticsNode()
        val wipeNode = composeRule.onNodeWithTag(WIPE_ACTION_TAG).assertIsDisplayed().fetchSemanticsNode()
        assertTrue(
            "The destructive action must render after the exports",
            exportNode.boundsInRoot.top < wipeNode.boundsInRoot.top,
        )
        val dangerGap = wipeNode.boundsInRoot.top - exportNode.boundsInRoot.bottom
        val minimumDangerGap = with(composeRule.density) { 20.dp.toPx() }
        assertTrue("The destructive action needs visible section separation", dangerGap >= minimumDangerGap)
    }

    /** Tapping the raw export opens the consequence dialog whose confirm is destructive. */
    @Test
    fun rawExportAsksBeforeWriting() {
        render()
        composeRule.onNodeWithTag("profile_keys.export_raw").performClick()
        composeRule.onNodeWithText(app.getString(R.string.keep_your_private_key_safe)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.keep_your_private_key_safe)).assertDoesNotExist()
    }

    /** An Amber-signed account sees the Amber callout instead of the private key and export groups. */
    @Test
    fun amberAccountsSeeTheCalloutInsteadOfKeyGroups() {
        render(localSigning = false)
        composeRule.onNodeWithTag("profile_keys.amber_info").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.copy_private_key)).assertDoesNotExist()
        composeRule.onNodeWithTag("profile_keys.export_raw").assertDoesNotExist()
    }

    /** At 200 % in RTL the wipe action still clears the navigation bar plus content spacing. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
    fun largeFontRtlKeepsTerminalActionInsideTheSafeViewport() {
        render(
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
            contentWindowInsets = WindowInsets(bottom = NAVIGATION_BAR_BOTTOM),
        )
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(WIPE_ACTION_TAG))
        val wipe = composeRule.onNodeWithTag(WIPE_ACTION_TAG).assertIsDisplayed().fetchSemanticsNode()
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

    private fun render(
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        contentWindowInsets: WindowInsets = WindowInsets(0.dp),
        localSigning: Boolean = true,
    ) {
        val appState = appStateWithNpub(CANONICAL_NPUB, localSigning)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme {
                    AccountKeysScreen(appState = appState, onBack = {}, contentWindowInsets = contentWindowInsets)
                }
            }
        }
    }

    private fun appStateWithNpub(
        npub: String,
        localSigning: Boolean,
    ): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount(localSigning)),
                activeAccountRef = ACCOUNT_REF,
            )
        seedNpub(appState, ACCOUNT_HEX, npub)
        return appState
    }

    private fun activeAccount(localSigning: Boolean) =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = localSigning,
            externalSigning = !localSigning,
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
