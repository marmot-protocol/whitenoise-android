package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NostrEventCardRendererTest {
    @Test
    fun rendererRegistryBoundsHostileMetadataAndUsesTypedFallbacks() {
        val article =
            event(
                id = "a".repeat(64),
                kind = 30_023,
                tags = listOf(listOf("title", "T".repeat(400)), listOf("summary", "S".repeat(800))),
                content = "# Full article\n\nReadable body",
            ).toCardModel()
        val video =
            event(
                "b".repeat(64),
                kind = 34_235,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://cdn.example/video.mp4",
                            "m video/mp4",
                            "duration 125",
                            "dim 1920x1080",
                        ),
                    ),
            ).toCardModel()
        val release = event("c".repeat(64), kind = 30_063, tags = listOf(listOf("version", "2026.8.1"))).toCardModel()
        val file =
            event(
                "d".repeat(64),
                kind = 1_063,
                tags = listOf(listOf("m", "audio/ogg"), listOf("size", "2048")),
            ).toCardModel()
        val generic = event("e".repeat(64), kind = 7_777, content = "body").toCardModel()

        assertEquals(NostrEventCardKind.Article, article.kind)
        assertEquals(160, article.title?.length)
        assertEquals(420, article.summary?.length)
        assertEquals("# Full article\n\nReadable body", article.readerBody)
        assertEquals(listOf("2:05", "1920x1080"), video.metadata)
        assertEquals("https://cdn.example/video.mp4", video.mediaUrl)
        assertEquals("video/mp4", video.mediaMimeType)
        assertEquals(listOf("2026.8.1"), release.metadata)
        assertEquals(listOf("audio/ogg", "2.0 KB"), file.metadata)
        assertEquals(NostrEventCardKind.Generic, generic.kind)
        assertEquals("body", generic.summary)
    }

    /** Verifies every supported event kind retains its complete accepted body. */
    @Test
    fun supportedKindsRetainTheFullReaderBody() {
        val fullNote = "n".repeat(70_000)
        val note = event("a".repeat(64), kind = 1, content = fullNote).toCardModel()
        val article = event("b".repeat(64), kind = 30_023, content = "Full article").toCardModel()
        val nonReaders =
            listOf(
                event("c".repeat(64), kind = 34_235, content = "Video").toCardModel(),
                event("d".repeat(64), kind = 30_063, content = "Release").toCardModel(),
                event("e".repeat(64), kind = 1_063, content = "File").toCardModel(),
                event("f".repeat(64), kind = 7_777, content = "Generic").toCardModel(),
            )

        assertEquals("n".repeat(420), note.summary)
        assertEquals(fullNote, note.readerBody)
        assertEquals("Full article", article.readerBody)
        assertEquals(listOf("Video", "Release", "File", "Generic"), nonReaders.map { it.readerBody })
    }

    @Test
    fun textBoundsPreserveCompleteUnicodeCodePoints() {
        val emoji = "\uD83D\uDE00"
        val boundedField = ("a".repeat(159) + emoji + "tail").safeField()
        val boundedExcerpt =
            event(
                id = "a".repeat(64),
                content = "b".repeat(419) + emoji + "tail",
            ).toCardModel().summary

        assertEquals("a".repeat(159) + emoji, boundedField)
        assertEquals("b".repeat(419) + emoji, boundedExcerpt)
    }

    @Test
    fun videoPlaybackUrlAcceptsOnlyPublicDefaultPortHttps() {
        assertEquals("https://cdn.example/video.mp4", safeNostrMediaUrl(" https://cdn.example/video.mp4 "))
        assertEquals(null, safeNostrMediaUrl("http://cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://user:pass@cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://cdn.example:8443/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://127.0.0.1/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://[::1]/video.mp4"))
    }

    @Test
    fun hlsVideoMetadataRemainsPlayableWithoutAFileExtension() {
        val video =
            event(
                id = "a".repeat(64),
                kind = 34_236,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://media.example/watch/stream",
                            "m application/vnd.apple.mpegurl",
                        ),
                    ),
            ).toCardModel()

        assertEquals("https://media.example/watch/stream", video.mediaUrl)
        assertEquals("application/vnd.apple.mpegurl", video.mediaMimeType)
    }

    @Test
    fun everyInitialMediaKindUsesTheTypedRenderer() {
        listOf(21, 22, 34_235, 34_236).forEach { kind ->
            assertEquals(NostrEventCardKind.Video, event("a".repeat(64), kind = kind).toCardModel().kind)
        }
        assertEquals(NostrEventCardKind.Note, event("b".repeat(64), kind = 1).toCardModel().kind)
        assertEquals(NostrEventCardKind.Article, event("c".repeat(64), kind = 30_023).toCardModel().kind)
        assertEquals(NostrEventCardKind.Release, event("d".repeat(64), kind = 30_063).toCardModel().kind)
        assertEquals(NostrEventCardKind.File, event("e".repeat(64), kind = 1_063).toCardModel().kind)
    }

    @Test
    fun kindOneNotesExposeDeclaredVideoWithoutChangingTheirKind() {
        val note =
            event(
                id = "a".repeat(64),
                kind = 1,
                tags = listOf(listOf("imeta", "url https://media.example/opaque-resource", "m video/mp4")),
            ).toCardModel()
        assertEquals(NostrEventCardKind.Note, note.kind)
        assertEquals("https://media.example/opaque-resource", note.mediaUrl)
        val untyped =
            event(
                id = "b".repeat(64),
                kind = 1,
                tags = listOf(listOf("imeta", "url https://images.example/photo")),
            ).toCardModel()
        assertEquals(null, untyped.mediaUrl)
    }

    private fun event(
        id: String,
        pubkey: String = "c".repeat(64),
        kind: Int = 1,
        createdAt: Long = 1,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
