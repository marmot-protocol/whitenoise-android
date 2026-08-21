package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentDownloadProductionWiringTest {
    @Test
    fun visibleMediaPromotesAnAutomaticTransferWhenTheUserTapsIt() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, keyedEffects(image, "interactiveDownloadRequested"))
        assertEquals(2, keyedEffects(video, "interactiveDownloadRequested"))
        assertEquals(1, keyedEffects(voice, "interactiveDownloadRequested"))
        assertTrue(
            "Image loading progress must promote the transfer when tapped",
            ".clickable( onClickLabel = stringResource(R.string.media_tap_to_download), " +
                "onClick = { interactiveDownloadRequested = true }, )" in image,
        )
        assertTrue(
            "Video loading progress must promote the transfer when tapped",
            "loading -> interactiveDownloadRequested = true" in video,
        )
        assertTrue(
            "Voice loading progress must promote the transfer when tapped",
            "if (loading) { interactiveDownloadRequested = true return@combinedClickable }" in voice,
        )
    }

    @Test
    fun restartReevaluatesEveryLatchedVisualMediaDownload() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, keyedRemembers(image, "automaticDownloadsPaused"))
        assertEquals(2, keyedRemembers(video, "automaticDownloadsPaused"))
        assertEquals(1, keyedRemembers(voice, "automaticDownloadsPaused"))
    }

    private fun keyedEffects(
        source: String,
        key: String,
    ): Int = Regex("LaunchedEffect\\([^)]*\\b$key\\b[^)]*\\)").findAll(source).count()

    private fun keyedRemembers(
        source: String,
        key: String,
    ): Int = Regex("remember\\([^)]*\\b$key\\b[^)]*\\)").findAll(source).count()

    private fun source(fileName: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$fileName"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$fileName"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing $fileName source file")

    private fun String.normalized(): String = replace(Regex("\\s+"), " ")
}
