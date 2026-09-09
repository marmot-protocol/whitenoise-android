package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Test

/** Keeps plain notification preparation independent of cold Compose renderer initialization. */
class NotificationPreviewColdDependencyTest {
    @Test
    fun notificationResolutionDoesNotInitializeStyledTextOrTheBubbleRenderer() {
        assertPlainDependency("state/NotificationFirstPostResolutionKt")
    }

    @Test
    fun sharedProjectionAndMentionTraversalRemainComposeFree() {
        listOf(
            "MarkdownPreviewTextKt",
            "MarkdownPreviewInlinesKt",
            "MarkdownPreviewProjectionKt",
            "MarkdownPreviewBuilder",
            "MarkdownPreviewProjection",
            "MarkdownMentionsKt",
            "MarkdownTraversalKt",
        ).forEach { assertPlainDependency("ui/$it") }
    }

    /** Examines class references without warming the renderer being excluded from the cold path. */
    private fun assertPlainDependency(className: String) {
        val resource = "/dev/ipf/whitenoise/android/$className.class"
        val bytecode =
            checkNotNull(javaClass.getResourceAsStream(resource)).use {
                it.readBytes().toString(Charsets.ISO_8859_1)
            }
        assertFalse("Plain notifications must not initialize the bubble renderer", "MarkdownRendererKt" in bytecode)
        // The Compose compiler annotates data classes even without executable Compose dependencies.
        val executableReferences = bytecode.replace("androidx/compose/runtime/internal/StabilityInferred", "")
        assertFalse("$className must not initialize Compose", "androidx/compose/" in executableReferences)
    }
}
