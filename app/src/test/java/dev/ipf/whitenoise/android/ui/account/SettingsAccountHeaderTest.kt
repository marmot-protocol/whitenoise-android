package dev.ipf.whitenoise.android.ui.account

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SettingsAccountHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun narrowHeaderExposesFullNpubAndKeepsQrClicksOutOfSelector() {
        var selectorClicks = 0
        var qrClicks = 0

        render(
            onOpenSelector = { selectorClicks += 1 },
            onOpenQr = { qrClicks += 1 },
        )

        composeRule
            .onNodeWithContentDescription("Switch Account")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, FULL_NPUB))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.runOnIdle {
            val clipboard =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(
                FULL_NPUB,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }

        val qrNode = composeRule.onNodeWithTag(SETTINGS_ACCOUNT_QR_TARGET_TAG)
        val qrBounds = qrNode.fetchSemanticsNode().boundsInRoot
        val minimumTouchTargetPx = composeRule.density.run { 48.dp.toPx() }
        assertTrue(qrBounds.width >= minimumTouchTargetPx)
        assertTrue(qrBounds.height >= minimumTouchTargetPx)

        qrNode.performTouchInput {
            click(Offset(x = 1f, y = center.y))
            click(Offset(x = width - 1f, y = center.y))
        }
        composeRule.runOnIdle {
            assertEquals(0, selectorClicks)
            assertEquals(2, qrClicks)
        }

        composeRule.onNodeWithTag(SETTINGS_ACCOUNT_SELECTOR_TARGET_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(2, qrClicks)
        }

        val npubBounds =
            composeRule
                .onNodeWithTag(SETTINGS_ACCOUNT_NPUB_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("npub should consume the available middle column", npubBounds.width > 140f)
        val npubLayout = npubTextLayout()
        assertEquals(1, npubLayout.lineCount)
    }

    @Test
    fun largeFontRtlHeaderKeepsNpubAndQrBoundsDisjoint() {
        render(fontScale = 2f, layoutDirection = LayoutDirection.Rtl)

        val selectorBounds =
            composeRule
                .onNodeWithTag(SETTINGS_ACCOUNT_SELECTOR_TARGET_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val qrBounds =
            composeRule
                .onNodeWithTag(SETTINGS_ACCOUNT_QR_TARGET_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val npubBounds =
            composeRule
                .onNodeWithTag(SETTINGS_ACCOUNT_NPUB_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertFalse("selector and QR targets overlap", selectorBounds.overlaps(qrBounds))
        assertTrue(npubBounds.left >= selectorBounds.left)
        assertTrue(npubBounds.right <= selectorBounds.right)
        assertEquals(1, npubTextLayout().lineCount)
    }

    private fun npubTextLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithTag(SETTINGS_ACCOUNT_NPUB_TAG, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        return layouts.single()
    }

    private fun render(
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onOpenSelector: () -> Unit = {},
        onOpenQr: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme {
                    SettingsAccountHeader(
                        title = "A very long identity name",
                        subtitle = FULL_NPUB,
                        seed = "settings-account-header-test",
                        pictureUrl = null,
                        onOpenAccountSelector = onOpenSelector,
                        onOpenQr = onOpenQr,
                    )
                }
            }
        }
    }

    private companion object {
        const val FULL_NPUB = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    }
}
