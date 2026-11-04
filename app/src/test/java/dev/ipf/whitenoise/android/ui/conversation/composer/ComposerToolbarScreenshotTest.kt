package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerToolbarScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun defaultLight() = capture("default_light", ComposerToolbarSettings())

    @Test fun defaultDark() = capture("default_dark", ComposerToolbarSettings(), dark = true)

    @Test fun defaultAmoled() = capture("default_amoled", ComposerToolbarSettings(), dark = true, amoled = true)

    @Test fun expandedLight() = capture("expanded_light", custom)

    @Test fun expandedDark() = capture("expanded_dark", custom, dark = true)

    @Test fun expandedAmoled() = capture("expanded_amoled", custom, dark = true, amoled = true)

    @Test fun wrappedRtlLargeFont() =
        capture(
            "wrapped_rtl_large",
            ComposerToolbarSettings(
                ComposerShortcut.entries,
                ComposerToolbarLayout.AlwaysExpanded,
            ),
            dark = true,
            amoled = true,
            rtl = true,
            large = true,
        )

    @Test fun editorLight() = capture("editor_light", custom, editor = true)

    @Test fun editorDark() = capture("editor_dark", custom, dark = true, editor = true)

    @Test fun editorAmoled() = capture("editor_amoled", custom, dark = true, amoled = true, editor = true)

    @Test fun editorCompactVoiceAtDoubleFont() =
        capture(
            "editor_compact_voice_double_font",
            ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.VoiceNote)),
            dark = true,
            amoled = true,
            editor = true,
            fontScale = 2f,
        )

    @Test fun editorNarrowOverflowAtDoubleFont() =
        capture(
            "editor_narrow_overflow_double_font",
            ComposerToolbarSettings(ComposerShortcut.entries, ComposerToolbarLayout.AlwaysExpanded),
            dark = true,
            amoled = true,
            editor = true,
            fontScale = 2f,
            width = 240,
        )

    @Test fun inlineEditorAmoled() = capture("inline_editor_amoled", custom, dark = true, amoled = true, inline = true)

    @Test fun inlineEditorShortLargeFont() =
        capture(
            "inline_editor_short_large",
            custom,
            dark = true,
            amoled = true,
            inline = true,
            large = true,
            height = 180,
        )

    @Test fun shortHeightWrappedActions() =
        capture(
            "short_height_wrapped",
            ComposerToolbarSettings(
                ComposerShortcut.entries,
                ComposerToolbarLayout.AlwaysExpanded,
            ),
            dark = true,
            amoled = true,
            large = true,
            height = 180,
        )

    @Suppress("LongMethod", "LongParameterList") // One production render fixture varies only visible case inputs.
    private fun capture(
        name: String,
        settings: ComposerToolbarSettings,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
        editor: Boolean = false,
        inline: Boolean = false,
        height: Int = if (editor) 700 else 360,
        fontScale: Float = if (large) 1.5f else 1f,
        width: Int = 360,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        val appState = emptyAppState()
        val textState = ComposerTextState(TextFieldValue(if (inline) "A draft kept while editing shortcuts" else ""))
        val editState = ComposerToolbarEditState(settings)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    Surface {
                        Box(
                            Modifier.width(width.dp).height(height.dp).testTag("toolbar.snapshot"),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (editor) {
                                ComposerToolbarEditor(editState, false, { _, complete -> complete(false) }, {})
                            } else {
                                ComposerBar(
                                    appState = appState,
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    onCaptureFromCamera = {},
                                    onPickFromGallery = {},
                                    onPickDocument = {},
                                    onShareLocation = {},
                                    onShareUser = {},
                                    onShareContact = {},
                                    onCreatePoll = {},
                                    textState = textState,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        if (inline) {
            composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        }
        if (editor || inline) assertEditorPreview(settings, height, width, fontScale)
        composeRule.onNodeWithTag("toolbar.snapshot").captureRoboImage("src/test/snapshots/composer_toolbar_$name.png")
    }

    private val custom =
        ComposerToolbarSettings(
            listOf(
                ComposerShortcut.Camera,
                ComposerShortcut.Gallery,
                ComposerShortcut.More,
                ComposerShortcut.Emoji,
            ),
            ComposerToolbarLayout.AlwaysExpanded,
        )

    private fun assertEditorPreview(
        settings: ComposerToolbarSettings,
        height: Int,
        width: Int,
        fontScale: Float,
    ) {
        // The real preview must reserve its controls, not merely paint an empty 48dp outline.
        val previewAncestor = hasAnyAncestor(hasTestTag("composer.toolbar.preview"))
        val attachments =
            setOf(
                ComposerShortcut.Camera,
                ComposerShortcut.Gallery,
                ComposerShortcut.Files,
                ComposerShortcut.Location,
                ComposerShortcut.Person,
                ComposerShortcut.Contact,
                ComposerShortcut.Poll,
            )
        val firstShortcut =
            settings.normalized().shortcuts.first {
                it != ComposerShortcut.More || (attachments - settings.shortcuts.toSet()).isNotEmpty()
            }
        val first = composeRule.onNode(hasTestTag("composer.shortcut.${firstShortcut.id}") and previewAncestor)
        if (height < 300) first.performScrollTo()
        first.assertIsDisplayed()
        val surface =
            composeRule
                .onNode(hasTestTag(COMPOSER_PILL_SURFACE_TAG) and previewAncestor)
                .fetchSemanticsNode()
                .boundsInRoot
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        assertTrue(firstBounds.top >= surface.top && firstBounds.bottom <= surface.bottom)
        if (ComposerShortcut.VoiceNote !in settings.shortcuts) {
            val primary =
                composeRule
                    .onNodeWithTag("composer.toolbar.preview.primary")
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals(firstBounds.center.y, primary.center.y, 1f)
            assertTrue(primary.top >= surface.top && primary.bottom <= surface.bottom)
        }
        if (width == 240) {
            // The inert preview still lets the last configured row scroll into view.
            composeRule
                .onNode(hasTestTag("composer.shortcut.poll") and previewAncestor)
                .performScrollTo()
                .assertIsDisplayed()
        }
        if (fontScale >= 2f) {
            val choiceBounds = composeRule.onNodeWithText("Always expanded").getUnclippedBoundsInRoot()
            val minimumChoiceWidth = minOf(96.dp * fontScale, (width - 24 - 32).dp)
            assertTrue(choiceBounds.right - choiceBounds.left >= minimumChoiceWidth)
            val selectedLabel =
                composeRule.onNodeWithTag(
                    "composer.customize.name.${firstShortcut.id}",
                    useUnmergedTree = true,
                )
            val labelBounds = selectedLabel.getUnclippedBoundsInRoot()
            val minimumLabelWidth = minOf(96.dp * fontScale, (width - 24 - 24 - 48 - 16).dp)
            assertTrue(labelBounds.right - labelBounds.left >= minimumLabelWidth)
        }
    }
}
