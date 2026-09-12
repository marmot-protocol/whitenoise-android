package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.search.GlobalPersonRow
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native-result presentation only; filter examples do not establish production search execution readiness. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GlobalSearchPresentationScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Empty query preserves the prototype's search/microphone field composition. */
    @Test fun emptyLight() = capture("empty_light")

    /** Typed query replaces voice with native Clear. */
    @Test fun typedDark() = capture("typed_dark", query = "alice", dark = true)

    /** AMOLED preserves visible search boundaries without account accent fills. */
    @Test fun typedAmoled() = capture("typed_amoled", query = "alice", dark = true, amoled = true)

    /** Narrow, RTL and large text use the real theme's font scale across roots. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-w320dp-h780dp-mdpi")
    fun typedLargeRtl() = capture("typed_large_rtl", query = "بحث", largeRtl = true)

    /** The actual native identifier profile action has one grouped row. */
    @Test fun identifierLight() = capture("identifier_light", query = "npub1", person = true)

    /** Check-row presentation remains a bounded component, not a claim of native filter availability. */
    @Test fun stagedContentLargeRtl() = capture("staged_content_large_rtl", largeRtl = true, content = true)

    /** Renders through the actual search-mode header; all result callbacks stay inert in screenshots. */
    @Suppress("LongParameterList") // Independent visual dimensions share one screenshot fixture.
    private fun capture(
        name: String,
        query: String = "",
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        person: Boolean = false,
        content: Boolean = false,
    ) {
        val app =
            WhiteNoiseAppState(
                ApplicationProvider.getApplicationContext<Context>(),
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
                { null },
                emptyList(),
                "",
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface {
                        Column(Modifier.fillMaxWidth().testTag("global-search-preview")) {
                            ChatListTopBar(app, true, query, remember { FocusRequester() }, {}, {}, {}, {}, {}, {})
                            if (person) {
                                ChatListSearchSectionHeader("People", "global-search-heading")
                                GlobalPersonRow("npub1" + "a".repeat(58), {})
                            }
                            if (content) {
                                GlobalSearchContentFilterChips(
                                    GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT)),
                                    {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("global-search-preview").captureRoboImage(
            "src/test/snapshots/global_search_presentation_" + name + ".png",
        )
    }
}
