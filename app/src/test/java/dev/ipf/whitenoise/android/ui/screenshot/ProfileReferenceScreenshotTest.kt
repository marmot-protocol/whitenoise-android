package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.group.MEMBER_NPROFILE
import dev.ipf.whitenoise.android.ui.group.MEMBER_NPUB
import dev.ipf.whitenoise.android.ui.rememberMarkdownPreviewText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The same native profile references remain readable on both bubble surfaces and inert row previews. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class ProfileReferenceScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Incoming, outgoing and preview profile labels in the light palette. */
    @Test
    fun profileReferencesLight() = capture("profile_references_light.png", dark = false)

    /** The same content and fallback on the dark palette. */
    @Test
    fun profileReferencesDark() = capture("profile_references_dark.png", dark = true)

    /** RTL layout, AMOLED and 200 percent text retain identity and readable wrapping. */
    @Test
    fun profileReferencesAmoledLargeRtl() =
        capture("profile_references_amoled_large_rtl.png", dark = true, amoled = true, largeRtl = true)

    /** Captures resolved external URI/mention labels plus unresolved references using actual theme colors. */
    private fun capture(
        file: String,
        dark: Boolean,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val resolved = document()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                CompositionLocalProvider(LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Surface(modifier = Modifier.width(400.dp).testTag("profile-references")) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                                MarkdownMessageBody(
                                    resolved,
                                    modifier = Modifier.padding(12.dp),
                                    mentionDisplayName = { "Alice" },
                                    isGroupMember = { false },
                                )
                            }
                            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                                MarkdownMessageBody(
                                    resolved,
                                    modifier = Modifier.padding(12.dp),
                                    mentionDisplayName = { "Alice" },
                                    isGroupMember = { true },
                                )
                            }
                            Text(rememberMarkdownPreviewText(resolved) { "Alice" })
                            MarkdownMessageBody(document = resolved, mentionDisplayName = { null }, isGroupMember = { false })
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("profile-references").captureRoboImage("src/test/snapshots/$file")
    }

    /** Uses existing MDK vector identities in both typed native representations. */
    private fun document(): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            listOf(
                MarkdownBlockFfi.Paragraph(
                    listOf(
                        MarkdownInlineFfi.NostrMention(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, MEMBER_NPUB)),
                        MarkdownInlineFfi.Text(" · "),
                        MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, MEMBER_NPROFILE)),
                    ),
                ),
            ),
            false,
            byteArrayOf(),
        )
}
