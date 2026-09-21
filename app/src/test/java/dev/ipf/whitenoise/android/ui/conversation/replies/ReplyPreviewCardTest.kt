package dev.ipf.whitenoise.android.ui.conversation.replies

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression coverage for unavailable and typed-attachment reply cards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ReplyPreviewCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** An unavailable quote stays actionable without exposing fabricated identity. */
    @Test
    fun unavailableOriginalIsPersistentClickableAndDoesNotExposeAnIdentity() {
        var clicks = 0
        render(
            senderTitle = "missing-parent-id",
            body = "",
            mediaKind = ReplyMediaKind.None,
            originalUnavailable = true,
            onClick = { clicks++ },
        )

        composeRule.onNodeWithText(context.getString(R.string.reply)).assertIsDisplayed()
        composeRule
            .onNode(
                hasText(context.getString(R.string.reply)) and
                    hasText(context.getString(R.string.toast_original_message_unavailable)),
            ).assertHasClickAction()
        composeRule
            .onNodeWithText(context.getString(R.string.toast_original_message_unavailable))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
            .assertIsDisplayed()
        composeRule.onNodeWithText("missing-parent-id").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    /** Typed document replies share the production format resolver and filename priority. */
    @Test
    fun typedDocumentsUseTheSharedResolverAndKeepTheFilenamePrimary() {
        render(
            senderTitle = "Alice",
            body = "File",
            mediaKind = ReplyMediaKind.Document,
            mediaFileName = "release.apk",
            mediaType = "application/vnd.android.package-archive",
        )

        composeRule.onNodeWithText("release.apk · APK").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.reply_media_document)).assertDoesNotExist()
    }

    /** Legacy opaque documents retain the localized generic label. */
    @Test
    fun legacyOpaqueDocumentKeepsTheLocalizedCoarseFallback() {
        render(
            senderTitle = "Alice",
            body = "File",
            mediaKind = ReplyMediaKind.Document,
        )

        composeRule.onNodeWithText(context.getString(R.string.reply_media_document)).assertIsDisplayed()
    }

    /** Existing photo, video, and voice labels remain unchanged. */
    @Test
    fun establishedPhotoVideoAndVoiceLabelsRemainUnchanged() {
        val cases =
            listOf(
                ReplyMediaKind.Photo to R.string.reply_media_photo,
                ReplyMediaKind.Video to R.string.reply_media_video,
                ReplyMediaKind.Voice to R.string.reply_media_voice,
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    cases.forEach { (kind, _) ->
                        ReplyPreviewCard(
                            senderTitle = "Alice",
                            isOwn = false,
                            body = "ignored",
                            mediaKind = kind,
                            onClick = null,
                            onDismiss = null,
                        )
                    }
                }
            }
        }

        cases.forEach { (_, label) ->
            composeRule.onNodeWithText(context.getString(label)).assertIsDisplayed()
        }
    }

    /** Safe filename and MIME combinations cover known and unknown document types. */
    @Test
    fun safeExtensionAndMimeResolutionCoverPdfMarkdownAndUnknownFiles() {
        val cases =
            listOf(
                Triple("notes.pdf", "application/pdf", "notes.pdf · PDF"),
                Triple("README.md", "text/markdown", "README.md · Markdown"),
                Triple("board.pcb", "application/vnd.acme.machine-part", "board.pcb · PCB"),
            )

        cases.forEach { (fileName, mediaType, expected) ->
            val presentation = resolveAttachmentPresentation(mediaType, fileName)
            val actual = replyAttachmentPreviewText(fileName, presentation)
            assertEquals(expected, actual)
            assertTrue(actual?.startsWith(fileName) == true)
        }
        val hostile =
            replyAttachmentPreviewText(
                "../../report.\u202Eapk",
                resolveAttachmentPresentation("application/octet-stream", "../../report.\u202Eapk"),
            )
        assertEquals("report.apk", hostile)
        assertFalse(hostile.orEmpty().contains(".."))
    }

    /** Reply dismissal uses a48dp target while drawing the20dp inset control. */
    @Test
    fun replyAccessoryLightGeometryAndDismissal() = renderAccessory("reply_accessory_light")

    /** Dark quote geometry uses the same paired native colors and target size. */
    @Test
    fun replyAccessoryDark() = renderAccessory("reply_accessory_dark", dark = true)

    /** AMOLED quote geometry keeps one continuous rounded outline around all four corners. */
    @Test
    fun replyAccessoryAmoled() = renderAccessory("reply_accessory_amoled", dark = true, amoled = true)

    /** Long unavailable/warning text and logical trailing dismissal remain reachable in RTL. */
    @Test
    fun replyAccessoryLargeRtl() = renderAccessory("reply_accessory_large_rtl", rtl = true, scale = 2f)

    /** Captures the real shared quote and checks its excerpt limit and native cancellation. */
    private fun renderAccessory(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        var cancellations = 0
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        val excerpt = "A reply excerpt with enough words to occupy two lines without losing the original content."
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Column(Modifier.width(300.dp).testTag("reply-fixture")) {
                        ReplyPreviewCard(
                            senderTitle = "Alice",
                            isOwn = false,
                            body = excerpt,
                            warning = "The original message may have changed.",
                            mediaKind = ReplyMediaKind.None,
                            onClick = null,
                            onDismiss = { cancellations++ },
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("conversation.composer.remove.target")
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
        composeRule
            .onNodeWithTag("conversation.composer.remove.visual", useUnmergedTree = true)
            .assertWidthIsEqualTo(20.dp)
            .assertHeightIsEqualTo(20.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(excerpt, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        composeRule.onNodeWithTag("reply-fixture").captureRoboImage("src/test/snapshots/$name.png")
        composeRule.onNodeWithTag("conversation.composer.remove.target").performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

    /** Renders a production reply card with overridable attachment metadata. */
    private fun render(
        senderTitle: String,
        body: String,
        mediaKind: ReplyMediaKind,
        mediaFileName: String? = null,
        mediaType: String? = null,
        originalUnavailable: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ReplyPreviewCard(
                    senderTitle = senderTitle,
                    isOwn = false,
                    body = body,
                    mediaKind = mediaKind,
                    mediaFileName = mediaFileName,
                    mediaType = mediaType,
                    originalUnavailable = originalUnavailable,
                    onClick = onClick,
                    onDismiss = null,
                )
            }
        }
    }
}
