package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prevents unsafe intrinsic sizing across app production sources and infinity arithmetic in UI callers. */
class ComposeConstraintSafetyCoverageTest {
    /** Intrinsic sizing clamps its scalar result before constructing packed constraints. */
    @Test
    fun productionIntrinsicSizingUsesBoundedModifiers() {
        val unsafeIntrinsic =
            Regex("""\.(?:width|height|requiredWidth|requiredHeight)\s*\(\s*IntrinsicSize\s*\.""")
        val root = productionRoot()
        val offenders =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { unsafeIntrinsic.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).path }
                .toList()
        assertTrue("Intrinsic sizing must use the bounded policy: $offenders", offenders.isEmpty())
        val owners =
            mapOf(
                "conversation/messages/MessageBubble.kt" to "boundedIntrinsicWidth",
                "conversation/composer/ComposerAttachmentMenu.kt" to "boundedIntrinsicWidth",
                "conversation/composer/VoiceReviewComposer.kt" to "boundedIntrinsicWidth",
                "conversation/replies/ReplyPreviewCard.kt" to "boundedIntrinsicHeight",
                "MarkdownRenderer.kt" to "boundedIntrinsicHeight",
                "settings/SmartFolderQuickFilterControls.kt" to "boundedIntrinsicHeight",
            )
        owners.forEach { (path, modifier) ->
            assertTrue(
                "$path must retain bounded intrinsic sizing",
                uiRoot().resolve(path).readText().contains(".$modifier("),
            )
        }
    }

    /** The quote's first-frame plaintext follows the budget before Markdown parsing completes. */
    @Test
    fun firstFrameReplyFallbackIsBounded() {
        val source = uiRoot().resolve("conversation/replies/ReplyPreviewCard.kt").readText()
        assertTrue(source.contains("mediaLabel ?: replyFallbackPreviewText(body, mentionDisplayName)"))
        assertTrue(source.contains(".previewTake(MARKDOWN_PREVIEW_MAX_LENGTH)"))
    }

    /** Animated composer insets use the sentinel-aware Compose offset primitive. */
    @Test
    fun composerPaddingPreservesUnboundedConstraints() {
        val source = uiRoot().resolve("conversation/composer/ComposerDeferredPadding.kt").readText()
        assertTrue(source.contains("constraints.offset(-horizontal, -vertical)"))
        assertFalse(source.contains("constraints.maxWidth -"))
        assertFalse(source.contains("constraints.maxHeight -"))
        assertTrue(uiRoot().resolve("conversation/composer/ComposerPills.kt").readText().contains(".deferredPadding("))
    }

    /** Locates known UI owners within the complete production source tree. */
    private fun uiRoot(): File = productionRoot().resolve("dev/ipf/whitenoise/android/ui")

    /** Scans all app production packages from either the Gradle app directory or repository root. */
    private fun productionRoot(): File =
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        ).first { it.isDirectory }
}
