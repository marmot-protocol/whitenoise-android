package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

/** Donate opens the foundation website and preserves navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class DonateScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var backCount = 0
    private val openedUris = mutableListOf<String>()

    /** The single action opens the exact HTTPS donation page. */
    @Test
    fun donateOpensFoundationPage() {
        show()
        composeRule.onNodeWithTag("donate.method_selector").assertDoesNotExist()
        composeRule.onNodeWithTag("donate.qr_surface").assertDoesNotExist()
        composeRule.onNodeWithTag("donate.copy_address").assertDoesNotExist()
        composeRule.onNodeWithTag("donate.open").performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf("https://ipf.dev/donate/?utm_source=whitenoise_android&utm_medium=app&utm_campaign=donations"),
                openedUris,
            )
        }
    }

    /** Back invokes the caller once. */
    @Test
    fun backReturnsOnce() {
        show()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    /** Uses an isolated URL handler so the test never launches a browser. */
    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUris.add(uri)
                        }
                    },
            ) {
                WhiteNoiseTheme(darkTheme = false) { DonateScreen(onBack = { backCount++ }) }
            }
        }
    }
}
