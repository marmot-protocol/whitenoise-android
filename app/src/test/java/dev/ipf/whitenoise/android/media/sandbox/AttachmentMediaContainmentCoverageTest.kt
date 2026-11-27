package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.invariantGateRepositoryRoot
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards attachment and referenced-media routes; debug experiments are separate. */
class AttachmentMediaContainmentCoverageTest {
    @Test
    fun pdfConstructionStaysInTheIsolatedService() {
        val constructors =
            source(".")
                .walkTopDown()
                .filter {
                    it.extension == "kt" && "PdfRenderer(" in it.readText()
                }.toList()
        org.junit.Assert.assertEquals(listOf("PdfPageDecoder.kt"), constructors.map { it.name })
        val service = source("media/sandbox/MediaDecodeService.kt").readText().functionBody("imageOperation")
        assertTrue(service.indexOf("PdfPageDecoder.execute") in 0 until service.indexOf("readDecoderInput"))
        val viewer = source("ui/conversation/media/PdfDocumentPages.kt").readText()
        assertTrue("transport.retireDomain(domain)" in viewer && "transport.close()" in viewer)
    }

    /** Attachment Entry Points Cannot Instantiate Privileged Compressed Parsers. */
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
                    "ui/conversation/media/MediaVideoGridTile.kt",
                    "ui/conversation/media/MediaVideoCache.kt",
                    "ui/conversation/media/MediaVideoViewer.kt",
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

    /** No Production Source Outside The Isolated Workers Parses Compressed Media. */
    @Test
    fun productionSourcesParseCompressedMediaOnlyInIsolatedWorkers() {
        val root = source(".").canonicalFile
        val sources =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        assertTrue("The production source tree must be scanned", sources.size > 100)
        sources.forEach { (path, text) ->
            if (path !in BUNDLED_TONE_PLAYERS) {
                assertFalse("Audio/video parser outside the isolated worker: $path", containsPrivilegedParser(text))
            }
            if (path !in IMAGE_PARSER_HOSTS) {
                assertFalse("Compressed image parser outside the isolated worker: $path", containsImageParser(text))
            }
        }
        // These helpers decode in the caller's process; only the isolated decode service may call them.
        val helper = Regex("""\b(?:decodeSampledBitmapInDecoder|readExifOrientationInDecoder|rasterizeInDecoder)\(""")
        val callers =
            sources
                .filter { (_, text) -> helper.containsMatchIn(executable(text)) }
                .keys
        assertEquals(IN_DECODER_HELPER_HOSTS, callers)
    }

    /** Referenced Nostr Videos Download Before The Isolated Player Parses Them. */
    @Test
    fun referencedNostrVideosDownloadBeforeTheIsolatedPlayerParsesThem() {
        val player = source("ui/conversation/nostr/NostrVideoPlayer.kt").readText()
        assertTrue(
            "Referenced videos must play their private download through the isolated factory",
            "localVideoPlayer(context, Uri.fromFile(file))" in player.functionBody("rememberNostrVideoPlayer"),
        )
        assertTrue(
            "Each player must delete its download directory after its download ends",
            "withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }" in
                player.functionBody("NostrVideoPlayback"),
        )
        val download = source("ui/conversation/nostr/NostrVideoDownload.kt").readText()
        assertTrue(
            "Downloads must stay on validated public HTTPS",
            "isSafeMediaDestination()" in download && "client: OkHttpClient = nostrMediaHttpClient" in download,
        )
        assertTrue(
            "Downloads must stay within the isolated decoder's input limit",
            "maxBytes: Long = MAX_MEDIA_SOURCE_BYTES" in download,
        )
    }

    /** Automatic Media Uses Its Dedicated Isolated Service Without Fallback. */
    @Test
    fun automaticMediaUsesItsDedicatedIsolatedServiceWithoutFallback() {
        val facade = source("media/sandbox/IsolatedMediaDecoder.kt").readText()
        val installation = facade.functionBody("install")
        assertTrue(
            "Automatic media must use the native isolated service",
            "Intent(context, MediaAvDecodeService::class.java)" in
                source("media/sandbox/IsolatedAutomaticAvTransport.kt").readText().functionBody("connect"),
        )
        assertTrue(
            "Automatic media must reuse its process lifetime owner across account sessions",
            "mediaOwner ?: IsolatedAutomaticAvTransport(" in installation &&
                ").also { mediaOwner = it }" in installation,
        )
        val activation = installation.indexOf("automaticOwner.activateSession()")
        val selection = installation.indexOf("mediaTransport = automaticOwner")
        assertTrue(
            "Automatic requests must activate the retained owner before selecting it",
            activation in 0 until selection,
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
            "NativeMediaEngine.nativeOpen(input.fd, offset, length, edge, playbackColors)" in service,
        )
    }

    /** An unidentified automatic worker receives the identity request before its retire message. */
    @Test
    fun automaticWorkerRetirementRequestsIdentityFirst() {
        val retire = source("media/sandbox/IsolatedAutomaticAvTransport.kt").readText().functionBody("retire")
        val identity = retire.indexOf("if (!selected.identityObserved) runCatching { identify(selected, target) }")
        assertTrue(identity in 0 until retire.indexOf("what = MediaDecodePolicy.RETIRE"))
    }

    /** A local URI must use the local file capability before content-provider admission. */
    @Test
    fun fileUrisBypassProviderAdmission() {
        val access = source("media/sandbox/MediaSourceAccess.kt").readText()
        val uriInput =
            access.kotlinBlockFrom(
                openDelimiter = access.indexOf("fun <T> withMediaUriInput("),
                description = "withMediaUriInput",
                searchForOpening = true,
            )
        assertTrue(
            uriInput.indexOf("uri.path?.takeIf { uri.scheme == \"file\" }") in
                0 until uriInput.indexOf("return filePath?.let { withMediaFileInput(File(it), block) }"),
        )
        assertTrue(uriInput.indexOf("withMediaFileInput(") in 0 until uriInput.indexOf("withProviderDeadline"))
    }

    /** Frame threads can extend the packet count for interleaved audio without raising the byte cap. */
    @Test
    fun preferredVideoScalesOnlyThePacketCountForFrameThreads() {
        val native = File(invariantGateRepositoryRoot(), "native/media-decoder/media_decoder.cpp").readText()
        val preferred =
            native.kotlinBlockFrom(
                openDelimiter = native.indexOf("jlongArray readPreferred("),
                description = "readPreferred",
                searchForOpening = true,
            )
        assertTrue("preferred == 1 && s.wantAudio && selected.codec" in preferred)
        assertTrue("(selected.codec->active_thread_type & FF_THREAD_FRAME)" in preferred)
        assertTrue("kMaxQueuedPackets * (frameThreadedVideo ? s.threads : 1)" in preferred)
        val reserveGuard = preferred.indexOf("if (reserveAudio && s.queuedPackets != 0)")
        assertTrue(reserveGuard in 0 until preferred.indexOf("queuePacket(s, packetLimit)"))
        assertTrue("kMaxQueuedBytes = 8U * 1024 * 1024" in native)
        assertTrue("s.queuedBytes <= kMaxQueuedBytes - retainedBytes && s.queuedPackets < packetLimit" in native)
    }

    /** Local Video And Transcription Enter Only The Isolated Raw Stream. */
    @Test
    fun localVideoAndTranscriptionEnterOnlyTheIsolatedRawStream() {
        val video = source("ui/conversation/media/LocalVideoSource.kt").readText()
        assertTrue("Local video must select the isolated player", "IsolatedAttachmentPlayer(context, uri)" in video)
        assertTrue("Remote URLs cannot select an attachment parser", "isLocalVideoUri(uri)" in video)
        for (path in listOf("MediaVideoViewer.kt", "DraftVideoPreview.kt")) {
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

    /** Voice Playback Factory Uses Audio Only Raw Output With Controller Owned Focus. */
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

    /** Parser Guard Rejects Aliased Imports And Qualified Factories But Allows Raw Output. */
    @Test
    fun parserGuardRejectsAliasedImportsAndQualifiedFactoriesButAllowsRawOutput() {
        for (source in listOf(
            "import android.media.MediaExtractor as SelectedExtractor",
            "val decoder = android.media.MediaMetadataRetriever()",
            "val codec = android.media.MediaCodec.createDecoderByType(mime)",
            "import androidx.media3.exoplayer.ExoPlayer as LocalPlayer",
            "val extractor = androidx.media3.extractor.mp4.Mp4Extractor()",
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

    /** Sonic Allocation Proof Requires The Exact Reviewed Media3 Implementation. */
    @Test
    fun sonicAllocationProofRequiresTheExactReviewedMedia3Implementation() {
        val catalog =
            listOf(File("../gradle/libs.versions.toml"), File("gradle/libs.versions.toml"))
                .first(File::exists)
                .readText()
        val pinned = Regex("""(?m)^media3 = "1\.11\.1"$""").containsMatchIn(catalog)
        assertTrue("Re-prove Sonic allocations before changing Media3", pinned)
        val segment = source("media/sandbox/RawSonicSegment.kt").readText()
        assertTrue(
            "The proof assumes at most ten milliseconds per input",
            "sampleRate / INPUT_QUANTA_PER_SECOND" in segment && "INPUT_QUANTA_PER_SECOND = 100" in segment,
        )
        assertTrue("The proof requires bounded processor output", "177_120" in segment)
        val device = source("media/sandbox/RawPcmDevice.kt").readText()
        assertTrue("Platform time stretching must remain unity", ".setSpeed(1f).setPitch(1f)" in device)
    }

    /**
     * Checks source text for forbidden compressed-parser factories, including qualified and aliased forms.
     */
    private fun containsPrivilegedParser(source: String): Boolean =
        // Import declarations are included, so aliases cannot hide a platform parser.
        Regex(
            """\b(?:android\.media\.)?(?:MediaMetadataRetriever|MediaExtractor|MediaPlayer|MediaCodec)\b|""" +
                """\b(?:androidx\.media3\.exoplayer\.)?ExoPlayer\b|\bandroidx\.media3\.(?:exoplayer|extractor)\b""",
        ).containsMatchIn(executable(source))

    /** Checks source text for platform or bundled still-image, animation, EXIF, SVG and PDF parsers. */
    private fun containsImageParser(source: String): Boolean =
        Regex(
            """\b(?:BitmapFactory|ImageDecoder|BitmapRegionDecoder|ExifInterface)\b|""" +
                """\b(?:PdfRenderer|AnimatedImageDrawable)\b|\bcom\.caverock\.androidsvg\b|\bSVG\.getFrom""",
        ).containsMatchIn(executable(source))

    /** Drops comments, so historical explanations are not mistaken for executable parsers. */
    private fun executable(source: String): String =
        source
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** Loads the checked-in source text used by the containment wiring assertion. */
    private fun source(path: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists) ?: File("src/main/java/dev/ipf/whitenoise/android/$path")

    private companion object {
        /** Plays bundled or system notification tones chosen in Settings, never attachment bytes. */
        val BUNDLED_TONE_PLAYERS = setOf("ui/settings/NotificationTonePreview.kt")

        /**
         * Files allowed to name an image parser: isolated-worker code, the `*InDecoder` helpers below and the encrypted
         * thumbnail cache, which re-decodes only pixels a worker already produced.
         */
        val IMAGE_PARSER_HOSTS =
            setOf(
                "media/MediaPipeline.kt",
                "media/GroupSvgRasterizer.kt",
                "media/PersistentDisplayThumbnails.kt",
                "media/sandbox/PdfPageDecoder.kt",
                "media/sandbox/PlatformMediaDecoder.kt",
                "media/sandbox/ViewerSvgDecoder.kt",
                "media/sandbox/WorkerAnimation.kt",
            )

        /** Definitions and isolated-service call sites of helpers that decode in the caller's process. */
        val IN_DECODER_HELPER_HOSTS =
            setOf(
                "media/MediaPipeline.kt",
                "media/GroupSvgRasterizer.kt",
                "media/sandbox/MediaDecodeService.kt",
                "media/sandbox/PlatformMediaDecoder.kt",
            )
    }
}
