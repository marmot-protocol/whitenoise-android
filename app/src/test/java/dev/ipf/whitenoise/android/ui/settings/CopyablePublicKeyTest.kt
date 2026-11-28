package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An identity screen's one key starts short and expands every grouped character inside one copy target (#3565). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w320dp-h780dp-mdpi")
class CopyablePublicKeyTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** At double font scale in RTL the short key expands on its first copy and wraps inside one 48 dp button. */
    @Test
    fun groupedKeyStaysInsideOneComfortableTargetAtLargeRtl() {
        var copies = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(fontScale = 2f) {
                    CopyablePublicKey(
                        npub = NPUB,
                        copied = false,
                        onCopy = { copies += 1 },
                        modifier = Modifier.width(280.dp),
                        targetTestTag = TARGET,
                        textTestTag = TEXT,
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(TEXT, useUnmergedTree = true)
            .assertTextEquals(publicKeyShortForm(NPUB))
        composeRule.onNodeWithTag(TARGET).performTouchInput { click(Offset(12f, height / 2f)) }
        assertEquals(1, copies)

        val target = composeRule.onNodeWithTag(TARGET).getUnclippedBoundsInRoot()
        val text = composeRule.onNodeWithTag(TEXT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("target shorter than 48 dp", target.bottom - target.top >= 48.dp)
        assertTrue("key text escapes its target", text.left >= target.left && text.right <= target.right)
        assertTrue("key text escapes its target", text.top >= target.top && text.bottom <= target.bottom)
        composeRule
            .onNodeWithTag(TEXT, useUnmergedTree = true)
            .assertTextEquals(publicKeyDisplayGroups(NPUB).joinToString(" "))
        composeRule
            .onNodeWithTag(TARGET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(NPUB))))
        composeRule.onNodeWithTag(TEXT, useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(2, copies)
        // A second copy keeps the key expanded.
        composeRule
            .onNodeWithTag(TEXT, useUnmergedTree = true)
            .assertTextEquals(publicKeyDisplayGroups(NPUB).joinToString(" "))
    }

    private companion object {
        const val NPUB = "npub1qqqqqqqqqqqqqqqqqqqqqppppppppqqqqqqqqqqqqqqqqqqqqqqqkrvh2t"
        const val TARGET = "key.target"
        const val TEXT = "key.text"
    }
}
