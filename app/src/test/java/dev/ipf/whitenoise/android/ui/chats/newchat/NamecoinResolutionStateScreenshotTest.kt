package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel + semantics coverage for the Namecoin `.bit` resolution states added
 * to the invite and new-chat pickers: the Namecoin-specific resolving label,
 * the not-found message, and the resolvers-unreachable message. Rendered via
 * the same production rows the pickers use ([ResolvingContactRow],
 * [NewMessageSearchFeedback]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NamecoinResolutionStateScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Light mode: resolving label, not-found, and unreachable states share one baseline. */
    @Test
    fun namecoinStatesLight() {
        render(darkTheme = false, amoled = false)
        assertStates()
        capture("namecoin_resolution_states_light.png")
    }

    /** AMOLED keeps the status text legible on the black surface. */
    @Test
    fun namecoinStatesAmoled() {
        render(darkTheme = true, amoled = true)
        assertStates()
        capture("namecoin_resolution_states_amoled.png")
    }

    /** 200%-text RTL keeps the long German not-found line inside the viewport. */
    @Test
    @Config(sdk = [36], qualifiers = "de-w360dp-h780dp-mdpi")
    fun namecoinStatesLargeFontRtlDark() {
        render(darkTheme = true, amoled = false, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
        assertStates()
        capture("namecoin_resolution_states_large_font_rtl_dark.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(
                        modifier = Modifier.width(360.dp).testTag(ROOT_TAG),
                        color = if (amoled) Color.Black else MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            // The invite picker's resolving row with the
                            // Namecoin-specific label.
                            ResolvingContactRow(
                                label = context.getString(R.string.namecoin_lookup_resolving),
                            )
                            // The new-chat feedback in the not-found and
                            // resolvers-unreachable identifier-empty states.
                            NewMessageSearchFeedback(
                                searching = false,
                                failed = false,
                                incomplete = false,
                                empty = true,
                                busy = false,
                                onRetry = {},
                                onInvite = {},
                                retryableIdentifier = true,
                                identifierEmptyLabelRes = R.string.namecoin_resolve_failed,
                                identifierEmptyArg = "alice@example.bit",
                            )
                            NewMessageSearchFeedback(
                                searching = false,
                                failed = false,
                                incomplete = false,
                                empty = true,
                                busy = false,
                                onRetry = {},
                                onInvite = {},
                                retryableIdentifier = true,
                                identifierEmptyLabelRes = R.string.namecoin_resolvers_unavailable,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Every Namecoin state renders its localized copy; the generic copy stays out. */
    private fun assertStates() {
        composeRule
            .onNodeWithText(context.getString(R.string.namecoin_lookup_resolving))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.namecoin_resolve_failed, "alice@example.bit"))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.namecoin_resolvers_unavailable))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.no_matches))
            .assertDoesNotExist()
    }

    private fun capture(fileName: String) {
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val ROOT_TAG = "namecoin-resolution-states"
    }
}
