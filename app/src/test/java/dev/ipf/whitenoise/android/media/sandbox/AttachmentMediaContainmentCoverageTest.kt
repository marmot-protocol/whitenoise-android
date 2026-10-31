package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the selected attachment routes; remote embedded videos and debug experiments are separate. */
class AttachmentMediaContainmentCoverageTest {
    @Test
    fun attachmentEntryPointsCannotInstantiatePrivilegedCompressedParsers() {
        val files =
            source("media/sandbox").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
                listOf(
                    "audio/AudioWaveformExtractor.kt",
                    "audio/VoicePlaybackController.kt",
                    "audio/VoicePlaybackPlayer.kt",
                    "audio/RawVoicePlaybackPlayer.kt",
                    "audio/AttachmentPcmDecoder.kt",
                    "ui/conversation/media/MediaVideo.kt",
                    "ui/conversation/media/DraftVideoPreview.kt",
                    "ui/conversation/media/LocalVideoSource.kt",
                ).map(::source)
        files.forEach { file ->
            assertFalse(
                "Attachment parser escaped the isolated worker: ${file.name}",
                containsPrivilegedParser(file.readText()),
            )
        }
        assertFalse(
            "The app-UID platform fallback must stay deleted",
            source("media/sandbox/PlatformMediaTransport.kt").exists(),
        )
        assertFalse(
            "Descriptor platform probing must remain fixture/debug-only",
            source("media/sandbox/MediaProbeDecoder.kt").readText().contains("probeMediaFromDescriptor"),
        )
    }

    @Test
    fun automaticMediaUsesItsDedicatedIsolatedServiceWithoutFallback() {
        val facade = source("media/sandbox/IsolatedMediaDecoder.kt").readText()
        val installation = facade.functionBody("installSelected")
        assertTrue(
            "Automatic media must use the native isolated service",
            "MediaAvDecodeService::class.java" in installation,
        )
        assertTrue(
            "Automatic requests must retain the transport lifetime owner",
            "mediaTransport = automaticOwner" in installation,
        )
        assertFalse("Production must not install a platform fallback", "PlatformMediaTransport" in facade)
        val waveform = source("audio/AudioWaveformExtractor.kt").readText()
        assertTrue(
            "Public waveform decoding must use the isolated facade",
            ".waveform(file)" in waveform.functionBody("decode"),
        )
        assertFalse(
            "No production descriptor-to-platform decoder bridge may remain",
            "decodeFromDescriptor" in waveform,
        )
        val service = source("media/sandbox/MediaAvDecodeService.kt").readText()
        assertTrue(
            "Worker admission must require isolated UID",
            "if (!Process.isIsolated()) return true" in service.functionBody("accept"),
        )
        assertTrue(
            "The selected descriptor must enter the native decoder",
            "NativeMediaEngine.nativeOpen(input.fd, offset, length, edge)" in service,
        )
    }

    @Test
    fun localVideoAndTranscriptionEnterOnlyTheIsolatedRawStream() {
        val video = source("ui/conversation/media/LocalVideoSource.kt").readText()
        assertTrue("Local video must select the isolated player", "IsolatedAttachmentPlayer(context, uri)" in video)
        assertTrue("Remote URLs cannot select an attachment parser", "isLocalVideoUri(uri)" in video)
        for (path in listOf("MediaVideo.kt", "DraftVideoPreview.kt")) {
            assertTrue(
                "Every attachment video surface must share its local player factory",
                "localVideoPlayer(context," in source("ui/conversation/media/$path").readText(),
            )
        }
        val decoder = source("audio/AttachmentPcmDecoder.kt").readText()
        assertTrue(
            "Transcription must consume the isolated audio stream",
            "IsolatedMediaDecoder.openAudioStream(file).use" in decoder,
        )
        assertFalse(
            "The PCM caller must not own an original descriptor outside admission",
            "ParcelFileDescriptor" in decoder,
        )
        assertFalse("The PCM caller cannot own a descriptor beyond stream admission", "ParcelFileDescriptor" in decoder)
        val facade = source("media/sandbox/IsolatedMediaDecoder.kt").readText().functionBody("openAudioStream")
        assertTrue("Analysis admission must stay separate from playback", "lane = AvStreamLane.Analysis" in facade)
        assertTrue("The analysis lane must open the selected file", "IsolatedAvStream.openFile(" in facade)
        assertTrue(
            "Transcription cannot request video decode",
            "wantVideo = false" in facade && "wantAudio = true" in facade,
        )
        val renderer = source("media/sandbox/RawAvRenderSession.kt").readText()
        assertFalse(
            "The renderer must not stage an original descriptor outside the lane",
            "MediaSourceAccess" in renderer,
        )
        assertTrue(
            "Playback must consume the same isolated raw protocol",
            "IsolatedAvStream.openUri(context, uri, wantVideo, wantAudio)" in renderer,
        )
    }

    @Test
    fun voicePlaybackFactoryUsesAudioOnlyRawOutputWithControllerOwnedFocus() {
        val controller = source("audio/VoicePlaybackController.kt").readText()
        assertTrue(
            "Voice playback must select its isolated raw player factory",
            "RawVoicePlaybackPlayer.Factory" in controller,
        )
        val player = source("audio/RawVoicePlaybackPlayer.kt").readText()
        assertTrue("Voice playback must use the bounded raw playback engine", "RawAvPlaybackEngine(" in player)
        assertTrue(
            "Voice must never decode an unwanted video track",
            "wantVideo = false" in player && "wantAudio = true" in player,
        )
        assertTrue(
            "The existing voice controller must keep audio-focus ownership",
            "externalAudioFocus = true" in player,
        )
    }

    @Test
    fun parserGuardRejectsAliasedImportsAndQualifiedFactoriesButAllowsRawOutput() {
        for (source in listOf(
            "import android.media.MediaExtractor as SelectedExtractor",
            "val decoder = android.media.MediaMetadataRetriever()",
            "val codec = android.media.MediaCodec.createDecoderByType(mime)",
            "import androidx.media3.exoplayer.ExoPlayer as LocalPlayer",
            "val player = MediaPlayer()",
        )) {
            assertTrue("A compressed-parser alias or factory escaped the guard", containsPrivilegedParser(source))
        }
        assertFalse(
            "Raw PCM output is allowed",
            containsPrivilegedParser("val output = android.media.AudioTrack.Builder()"),
        )
        assertFalse(
            "Historical explanations are not executable parsers",
            containsPrivilegedParser("/* old MediaPlayer route */\n// old MediaExtractor route"),
        )
    }

    private fun containsPrivilegedParser(source: String): Boolean {
        // Import declarations are included, so aliases cannot hide a platform parser.
        val executable =
            source
                .replace(Regex("""(?s)/\*.*?\*/"""), "")
                .lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
        return Regex(
            """\b(?:android\.media\.)?(?:MediaMetadataRetriever|MediaExtractor|MediaPlayer|MediaCodec)\b|\b(?:androidx\.media3\.exoplayer\.)?ExoPlayer\b""",
        ).containsMatchIn(executable)
    }

    private fun source(path: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists) ?: File("src/main/java/dev/ipf/whitenoise/android/$path")
}
