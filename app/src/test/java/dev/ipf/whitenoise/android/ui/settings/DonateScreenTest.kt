package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of Donate: the method selector swaps the address and caption, copy fills the clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class DonateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var backCount = 0

    /** Lightning opens selected; choosing Bitcoin swaps the caption to the silent payment label. */
    @Test
    fun selectorSwapsMethodAndCaption() {
        show()
        composeRule.onNodeWithTag("donate.method.0").assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.donate_lightning_address)).assertExists()
        composeRule.onNodeWithTag("donate.method.1").performClick()
        composeRule.onNodeWithTag("donate.method.1").assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.donate_bitcoin_silent_payment)).assertExists()
    }

    /** The copy capsule puts the shipped address on the clipboard and reports it as copied. */
    @Test
    fun copyCapsuleCopiesTheShippedAddress() {
        show()
        composeRule.onNodeWithTag("donate.copy_address").performClick()
        composeRule.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
            assertEquals(context.getString(R.string.donate_lightning_value), copied)
        }
        composeRule
            .onNodeWithTag("donate.copy_address")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.copied),
                ),
            )
    }

    /** Back invokes the caller once. */
    @Test
    fun backReturnsOnce() {
        show()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    /** Renders Donate in the light theme, counting Back calls. */
    private fun show() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DonateScreen(onBack = { backCount++ }) }
        }
    }
}
